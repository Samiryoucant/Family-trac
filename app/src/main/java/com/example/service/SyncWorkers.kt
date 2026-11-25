package com.example.service

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.AppPreferences
import com.example.data.local.FamilyTrackDatabase
import com.example.data.local.SyncStatusEntity
import com.example.data.remote.CommunicationRepository
import com.example.data.remote.FamilyRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.concurrent.TimeUnit

class LocationSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val user = Firebase.auth.currentUser ?: return Result.success()
        val db = FamilyTrackDatabase.getInstance(applicationContext)
        val locDao = db.locationRecordDao()
        val prefs = AppPreferences(applicationContext)
        val settings = prefs.settingsFlow.first()

        if (settings.activeFamilyId.isBlank() || settings.activeAdminUid.isBlank()) {
            return Result.success()
        }

        val repo = FamilyRepository(applicationContext)
        val pending = locDao.getPendingBatch()
        var hadFailure = false

        for (record in pending) {
            val uploadResult = repo.uploadLocationRecordIdempotent(
                recordId = record.id,
                familyId = record.familyId,
                adminUid = record.adminUid,
                deviceId = record.deviceId,
                latitude = record.latitude,
                longitude = record.longitude,
                accuracyMeters = record.accuracyMeters.toDouble(),
                altitudeMeters = record.altitudeMeters,
                speedMps = record.speedMps?.toDouble(),
                batteryPercent = record.batteryPercent,
                networkType = record.networkType,
                source = record.source,
                recordedAtMs = record.recordedAtMs
            )
            if (uploadResult.isSuccess) {
                // Only mark synced AFTER Firestore confirms receipt!
                locDao.markSynced(record.id)
            } else {
                hadFailure = true
                locDao.markSyncFailed(record.id, uploadResult.exceptionOrNull()?.message)
            }
        }

        // Update device status heartbeat & latest synced coordinates
        val deviceId = prefs.getOrCreateDeviceId()
        val latest = locDao.getLatestForDevice(deviceId)
        val nowMs = System.currentTimeMillis()
        val deviceUpdateResult = repo.updateOwnDeviceStatus(
            familyId = settings.activeFamilyId,
            deviceId = deviceId,
            consentGranted = settings.consentGranted,
            locationSharingEnabled = settings.locationSharingEnabled,
            locationPermissionGranted = LocationCaptureHelper.hasForegroundLocationPermission(applicationContext),
            backgroundPermissionGranted = LocationCaptureHelper.hasBackgroundLocationPermission(applicationContext),
            batteryPercent = LocationCaptureHelper.getBatteryPercent(applicationContext),
            networkType = LocationCaptureHelper.getNetworkType(applicationContext),
            online = LocationCaptureHelper.getNetworkType(applicationContext) != "OFFLINE",
            lastLatitude = latest?.latitude,
            lastLongitude = latest?.longitude,
            lastAccuracyMeters = latest?.accuracyMeters?.toDouble(),
            lastLocationAtMs = latest?.recordedAtMs,
            lastSyncAtMs = if (!hadFailure) nowMs else null
        )

        if (!hadFailure && deviceUpdateResult.isSuccess) {
            prefs.setLastSyncMs(nowMs)
            db.syncStatusDao().upsert(
                SyncStatusEntity(
                    lastSuccessfulSyncMs = nowMs,
                    lastSyncAttemptMs = nowMs,
                    lastError = null
                )
            )
        } else {
            // Check if Admin revoked this device; if so, halt foreground sharing cleanly
            val ownDevices = runCatching {
                repo.observeOwnDevice(settings.activeFamilyId, user.uid).first()
            }.getOrDefault(emptyList())
            val isRevoked = ownDevices.firstOrNull { it.id == deviceId }?.revoked == true
            if (isRevoked) {
                prefs.setConsentAndSharing(consentGranted = false, sharingEnabled = false)
                LocationForegroundService.stop(applicationContext)
                return Result.success()
            }
            db.syncStatusDao().upsert(
                SyncStatusEntity(
                    lastSuccessfulSyncMs = settings.lastSyncMs,
                    lastSyncAttemptMs = nowMs,
                    lastError = deviceUpdateResult.exceptionOrNull()?.message ?: "Pending retry"
                )
            )
        }

        // Prune ONLY already-synced local records older than 7 days (never delete unsynced records!)
        val sevenDaysAgoMs = nowMs - 7L * 24L * 60L * 60L * 1000L
        locDao.pruneSyncedOlderThan(sevenDaysAgoMs)

        // Also flush any pending offline messages / game moves
        SyncScheduler.flushOfflineComms(applicationContext, user.uid)

        return if (hadFailure || deviceUpdateResult.isFailure) Result.retry() else Result.success()
    }
}

class CommunicationSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val uid = Firebase.auth.currentUser?.uid ?: return Result.success()
        val ok = SyncScheduler.flushOfflineComms(applicationContext, uid)
        return if (ok) Result.success() else Result.retry()
    }
}

object SyncScheduler {
    private const val PERIODIC_LOCATION_WORK = "familytrack_periodic_location_sync"
    private const val IMMEDIATE_LOCATION_WORK = "familytrack_immediate_location_sync"
    private const val IMMEDIATE_COMMS_WORK = "familytrack_immediate_comms_sync"

    private val connectedConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun schedulePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<LocationSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(connectedConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_LOCATION_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun enqueueImmediateLocationSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setConstraints(connectedConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_LOCATION_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun enqueueImmediateCommsSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<CommunicationSyncWorker>()
            .setConstraints(connectedConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_COMMS_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    suspend fun flushOfflineComms(context: Context, currentUid: String): Boolean {
        val db = FamilyTrackDatabase.getInstance(context)
        val msgDao = db.pendingMessageDao()
        val commRepo = CommunicationRepository(context)
        var allOk = true

        for (pending in msgDao.getUnsyncedMessages()) {
            if (pending.senderUid != currentUid) continue
            val voiceBase64 = pending.voiceFilePath?.let { path ->
                runCatching {
                    val file = File(path)
                    if (file.exists() && file.length() <= 400_000L) {
                        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    } else null
                }.getOrNull()
            }
            val result = commRepo.sendMessageIdempotent(
                messageId = pending.id,
                familyId = pending.familyId,
                conversationId = pending.conversationId,
                senderName = pending.senderName,
                participantUids = pending.participantUidsCsv.split(",").filter { it.isNotBlank() },
                type = pending.type,
                text = pending.text,
                voiceAudioBase64 = voiceBase64,
                voiceDurationMs = if (pending.voiceDurationMs > 0) pending.voiceDurationMs else null,
                clientCreatedAtMs = pending.clientCreatedAtMs
            )
            if (result.isSuccess) {
                msgDao.markSynced(pending.id)
            } else {
                allOk = false
                msgDao.markFailed(pending.id, result.exceptionOrNull()?.message)
                Log.w("SyncScheduler", "Pending message sync failed: ${result.exceptionOrNull()?.message}")
            }
        }
        return allOk
    }
}
