package com.example.service

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

class VoiceRecorderPlayer(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var recordStartMs: Long = 0L

    private var player: MediaPlayer? = null
    private val _playingMessageId = MutableStateFlow<String?>(null)
    val playingMessageId: StateFlow<String?> = _playingMessageId.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    fun startRecording(): Boolean {
        if (_isRecording.value) return false
        val dir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
        val file = File(dir, "vn_${UUID.randomUUID()}.m4a")
        return try {
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(20000)
                setMaxDuration(45_000) // Cap 45s (~110KB) so Firestore Base64 stays lightweight
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        runCatching { rec.stop() }
                        releaseRecorder()
                        _isRecording.value = false
                    }
                }
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            currentFile = file
            recordStartMs = System.currentTimeMillis()
            _isRecording.value = true
            true
        } catch (_: Exception) {
            releaseRecorder()
            false
        }
    }

    fun stopRecording(): Pair<File, Long>? {
        val file = currentFile
        if (!_isRecording.value && file == null) return null
        val durationMs = (System.currentTimeMillis() - recordStartMs).coerceIn(500L, 45_000L)
        return try {
            if (recorder != null) {
                runCatching { recorder?.stop() }
                releaseRecorder()
            }
            _isRecording.value = false
            currentFile = null
            if (file != null && file.exists() && file.length() > 0) file to durationMs else null
        } catch (_: Exception) {
            releaseRecorder()
            _isRecording.value = false
            currentFile = null
            if (file != null && file.exists() && file.length() > 0) file to durationMs else null
        }
    }

    fun cancelRecording() {
        runCatching { recorder?.stop() }
        releaseRecorder()
        currentFile?.delete()
        currentFile = null
        _isRecording.value = false
    }

    private fun releaseRecorder() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    fun togglePlayback(messageId: String, audioBase64: String) {
        if (_playingMessageId.value == messageId) {
            stopPlayback()
            return
        }
        stopPlayback()
        runCatching {
            val bytes = Base64.decode(audioBase64, Base64.DEFAULT)
            val tempFile = File(context.cacheDir, "play_${messageId.take(32)}.m4a")
            tempFile.writeBytes(bytes)
            val mp = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener {
                    _playingMessageId.value = null
                    stopPlayback()
                    runCatching { tempFile.delete() }
                }
                setOnErrorListener { _, _, _ ->
                    _playingMessageId.value = null
                    stopPlayback()
                    runCatching { tempFile.delete() }
                    true
                }
                prepare()
                start()
            }
            player = mp
            _playingMessageId.value = messageId
        }.onFailure {
            _playingMessageId.value = null
            stopPlayback()
        }
    }

    fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _playingMessageId.value = null
    }

    fun release() {
        cancelRecording()
        stopPlayback()
    }
}
