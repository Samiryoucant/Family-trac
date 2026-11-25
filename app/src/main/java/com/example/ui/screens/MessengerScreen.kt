package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.local.PendingMessageEntity
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.FamilyMember
import com.example.service.VoiceRecorderPlayer
import com.example.ui.i18n.stringsFor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessengerScreen(
    language: String,
    currentUserId: String,
    members: List<FamilyMember>,
    conversations: List<Conversation>,
    selectedConversationId: String,
    messages: List<ChatMessage>,
    pendingMessages: List<PendingMessageEntity>,
    isVoiceFocused: Boolean,
    isRecordingVoice: Boolean,
    playingMessageId: String?,
    voiceRecorderPlayer: VoiceRecorderPlayer,
    onSelectConversation: (String) -> Unit,
    onOpenDirectChat: (FamilyMember) -> Unit,
    onSendText: (String) -> Unit,
    onSendVoiceNote: (File, Long) -> Unit
) {
    val strings = stringsFor(language)
    val context = LocalContext.current
    var draftText by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceRecorderPlayer.startRecording()
        }
    }

    val filteredMessages = remember(messages, searchQuery, isVoiceFocused) {
        messages.filter { msg ->
            val typeOk = if (isVoiceFocused) msg.type == "VOICE" else true
            val queryOk = searchQuery.isBlank() ||
                msg.text.contains(searchQuery, ignoreCase = true) ||
                msg.senderName.contains(searchQuery, ignoreCase = true)
            typeOk && queryOk
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Conversation & Direct Member selector
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedConversationId == "conv_group",
                    onClick = { onSelectConversation("conv_group") },
                    label = { Text(strings.familyGroupChat) }
                )
            }
            items(members.filter { it.uid != currentUserId }, key = { it.uid }) { member ->
                val sorted = listOf(currentUserId, member.uid).sorted()
                val dmId = "dm_${sorted[0].take(16)}_${sorted[1].take(16)}"
                FilterChip(
                    selected = selectedConversationId == dmId,
                    onClick = { onOpenDirectChat(member) },
                    label = { Text(member.displayName) }
                )
            }
        }

        // Message Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(strings.searchMessagesHint) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = strings.searchMessagesHint) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .testTag("message_search_input")
        )

        if (pendingMessages.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = "${strings.offlineQueueBanner} (${pendingMessages.size})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Message History List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filteredMessages.isEmpty() && pendingMessages.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (isVoiceFocused) strings.recordVoiceHint else "Start a conversation with your family.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            items(filteredMessages, key = { it.id }) { msg ->
                val isMine = msg.senderUid == currentUserId
                val isReadByOther = msg.readByUids.any { it != currentUserId }
                MessageBubble(
                    senderName = msg.senderName,
                    text = msg.text,
                    isMine = isMine,
                    isVoice = msg.type == "VOICE",
                    voiceDurationMs = msg.voiceDurationMs ?: 0L,
                    isPlaying = playingMessageId == msg.id,
                    timestampMs = msg.clientCreatedAtMs,
                    receiptLabel = when {
                        isReadByOther -> strings.readLabel
                        msg.status == "DELIVERED" -> strings.deliveredLabel
                        else -> strings.sentLabel
                    },
                    onPlayToggle = {
                        msg.voiceAudioBase64?.let { b64 ->
                            voiceRecorderPlayer.togglePlayback(msg.id, b64)
                        }
                    }
                )
            }

            // Show unsynced offline messages inline with queued indicator
            val syncedIds = filteredMessages.map { it.id }.toSet()
            items(pendingMessages.filter { it.id !in syncedIds }, key = { "pending_${it.id}" }) { p ->
                MessageBubble(
                    senderName = p.senderName,
                    text = p.text,
                    isMine = true,
                    isVoice = p.type == "VOICE",
                    voiceDurationMs = p.voiceDurationMs,
                    isPlaying = false,
                    timestampMs = p.clientCreatedAtMs,
                    receiptLabel = strings.queuedOfflineLabel,
                    isQueuedOffline = true,
                    onPlayToggle = {}
                )
            }
        }

        // Composer Row
        Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (isRecordingVoice) {
                            val result = voiceRecorderPlayer.stopRecording()
                            if (result != null) {
                                onSendVoiceNote(result.first, result.second)
                            }
                        } else {
                            val hasAudio = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasAudio) {
                                voiceRecorderPlayer.startRecording()
                            } else {
                                audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRecordingVoice) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primaryContainer
                        )
                        .testTag("record_voice_button")
                ) {
                    Icon(
                        if (isRecordingVoice) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecordingVoice) strings.stopRecordingLabel else strings.recordVoiceHint,
                        tint = if (isRecordingVoice) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(Modifier.width(8.dp))

                if (isRecordingVoice) {
                    Text(
                        text = strings.stopRecordingLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { draftText = it },
                        placeholder = { Text(strings.typeMessageHint) },
                        maxLines = 3,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_message_input")
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            if (draftText.isNotBlank()) {
                                onSendText(draftText)
                                draftText = ""
                            }
                        },
                        enabled = draftText.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("send_message_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    senderName: String,
    text: String,
    isMine: Boolean,
    isVoice: Boolean,
    voiceDurationMs: Long,
    isPlaying: Boolean,
    timestampMs: Long,
    receiptLabel: String,
    isQueuedOffline: Boolean = false,
    onPlayToggle: () -> Unit
) {
    val timeStr = remember(timestampMs) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestampMs))
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isMine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!isMine) {
                    Text(
                        text = senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(2.dp))
                }
                if (isVoice) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onPlayToggle, modifier = Modifier.size(48.dp)) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play voice note"
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Voice note • ${(voiceDurationMs / 1000L).coerceAtLeast(1)}s",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Text(text = text, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = timeStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isMine) {
                        Icon(
                            if (isQueuedOffline) Icons.Default.Schedule else Icons.Default.DoneAll,
                            contentDescription = receiptLabel,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = receiptLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
