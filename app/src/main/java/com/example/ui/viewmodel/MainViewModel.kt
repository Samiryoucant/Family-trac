package com.example.ui.viewmodel

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppPreferences
import com.example.data.local.CachedDeviceEntity
import com.example.data.local.ConsentLogEntity
import com.example.data.local.FamilyTrackDatabase
import com.example.data.local.LocalAppSettings
import com.example.data.local.LocationRecordEntity
import com.example.data.local.PendingMessageEntity
import com.example.data.local.SyncStatusEntity
import com.example.data.model.CallSession
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.FamilyGroup
import com.example.data.model.FamilyMember
import com.example.data.model.GameSession
import com.example.data.model.LocationRecord
import com.example.data.model.PairedDevice
import com.example.data.model.PairingCode
import com.example.data.model.UserProfile
import com.example.data.remote.CommunicationRepository
import com.example.data.remote.FamilyRepository
import com.example.data.remote.unwrapFirestoreError
import com.example.service.LocationCaptureHelper
import com.example.service.LocationForegroundService
import com.example.service.NearbyCommunicationManager
import com.example.service.NearbyPacket
import com.example.service.NearbyPeer
import com.example.service.NotificationHelper
import com.example.service.SyncScheduler
import com.example.service.VoiceRecorderPlayer
import com.example.service.WebRtcCallManager
import com.example.service.WebRtcCallState
import com.example.ui.theme.ThemePreference
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val appContext: Context,
    val currentUserId: String,
    private val familyRepo: FamilyRepository,
    private val commRepo: CommunicationRepository,
    private val prefs: AppPreferences,
    private val localDb: FamilyTrackDatabase,
    val voiceRecorderPlayer: VoiceRecorderPlayer,
    val webRtcManager: WebRtcCallManager,
    val nearbyManager: NearbyCommunicationManager
) : ViewModel() {

    val localSettings: StateFlow<LocalAppSettings> = prefs.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalAppSettings())

    val userProfileState: StateFlow<UiState<UserProfile?>> = familyRepo.observeUserProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { err ->
            val root = unwrapFirestoreError(err)
            Log.w(TAG, "Profile observe error", root)
            emit(UiState.Error(root.message ?: "Failed to load profile"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val familiesState: StateFlow<UiState<List<FamilyGroup>>> = familyRepo.observeMyFamilies(currentUserId)
        .map<List<FamilyGroup>, UiState<List<FamilyGroup>>> { UiState.Success(it) }
        .catch { err ->
            val root = unwrapFirestoreError(err)
            Log.w(TAG, "Families observe error", root)
            emit(UiState.Error(root.message ?: "Failed to load family"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val activeFamily: StateFlow<FamilyGroup?> = familiesState
        .map { state -> (state as? UiState.Success)?.data?.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isBackendAdmin: StateFlow<Boolean> = activeFamily
        .map { it != null && it.adminUid == currentUserId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val familyMembers: StateFlow<List<FamilyMember>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { familyId ->
            if (familyId.isBlank()) flowOf(emptyList())
            else familyRepo.observeFamilyMembers(familyId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val adminDevices: StateFlow<List<PairedDevice>> = activeFamily
        .flatMapLatest { fam ->
            if (fam == null || fam.adminUid != currentUserId) flowOf(emptyList())
            else familyRepo.observeAdminDevices(fam.id, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val cachedDevices: StateFlow<List<CachedDeviceEntity>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else localDb.cachedDeviceDao().observeDevicesForFamily(famId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val effectiveAdminDevices: StateFlow<List<PairedDevice>> = combine(adminDevices, cachedDevices) { live, cached ->
        live.ifEmpty {
            cached.map { c ->
                PairedDevice(
                    id = c.id,
                    familyId = c.familyId,
                    adminUid = c.adminUid,
                    ownerUid = c.ownerUid,
                    ownerName = c.ownerName,
                    deviceName = c.deviceName,
                    deviceModel = c.deviceModel,
                    role = c.role,
                    consentGranted = c.consentGranted,
                    locationSharingEnabled = c.locationSharingEnabled,
                    locationPermissionGranted = c.locationPermissionGranted,
                    backgroundPermissionGranted = c.backgroundPermissionGranted,
                    batteryPercent = c.batteryPercent.toLong(),
                    networkType = c.networkType,
                    online = c.online,
                    revoked = c.revoked,
                    lastLatitude = c.lastLatitude,
                    lastLongitude = c.lastLongitude,
                    lastAccuracyMeters = c.lastAccuracyMeters,
                    lastLocationAtMs = c.lastLocationAtMs,
                    lastSeenAtMs = c.lastSeenAtMs,
                    lastSyncAtMs = c.lastSyncAtMs
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val ownDevice: StateFlow<PairedDevice?> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else familyRepo.observeOwnDevice(famId, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val activePairingCodes: StateFlow<List<PairingCode>> = isBackendAdmin
        .flatMapLatest { admin ->
            if (!admin) flowOf(emptyList())
            else familyRepo.observeActivePairingCodes(currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val sevenDayLocations: StateFlow<List<LocationRecord>> = activeFamily
        .flatMapLatest { fam ->
            if (fam == null || fam.adminUid != currentUserId) flowOf(emptyList())
            else familyRepo.observeSevenDayLocationHistory(fam.id, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val pendingLocationCount: StateFlow<Int> = localDb.locationRecordDao().observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0)

    val pendingMessageCount: StateFlow<Int> = localDb.pendingMessageDao().observePendingMessageCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 0)

    val syncStatus: StateFlow<SyncStatusEntity?> = localDb.syncStatusDao().observeStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val consentLogs: StateFlow<List<ConsentLogEntity>> = localDb.consentLogDao().observeLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val conversations: StateFlow<List<Conversation>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else commRepo.observeConversations(famId, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _selectedConversationId = MutableStateFlow("conv_group")
    val selectedConversationId: StateFlow<String> = _selectedConversationId.asStateFlow()

    val activeMessages: StateFlow<List<ChatMessage>> = combine(activeFamily, _selectedConversationId) { fam, convId ->
        fam?.id.orEmpty() to convId
    }.flatMapLatest { (famId, convId) ->
        if (famId.isBlank() || convId.isBlank()) flowOf(emptyList())
        else commRepo.observeMessages(famId, convId, currentUserId)
            .catch { err ->
                unwrapFirestoreError(err)
                emit(emptyList())
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val pendingConversationMessages: StateFlow<List<PendingMessageEntity>> = _selectedConversationId
        .flatMapLatest { convId ->
            if (convId.isBlank()) flowOf(emptyList())
            else localDb.pendingMessageDao().observePendingForConversation(convId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val calls: StateFlow<List<CallSession>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else commRepo.observeCalls(famId, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val games: StateFlow<List<GameSession>> = activeFamily
        .map { it?.id.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { famId ->
            if (famId.isBlank()) flowOf(emptyList())
            else commRepo.observeGames(famId, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                    emit(emptyList())
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val _selectedGameId = MutableStateFlow<String?>(null)
    val selectedGameId: StateFlow<String?> = _selectedGameId.asStateFlow()

    // Admin's own phone current coordinates for live distance computation
    private val _adminCurrentLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val adminCurrentLocation: StateFlow<Pair<Double, Double>?> = _adminCurrentLocation.asStateFlow()

    private val _actionBanner = MutableStateFlow<String?>(null)
    val actionBanner: StateFlow<String?> = _actionBanner.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _latestGeneratedCode = MutableStateFlow<PairingCode?>(null)
    val latestGeneratedCode: StateFlow<PairingCode?> = _latestGeneratedCode.asStateFlow()

    private val _pendingConsentAfterJoin = MutableStateFlow(false)
    val pendingConsentAfterJoin: StateFlow<Boolean> = _pendingConsentAfterJoin.asStateFlow()

    // Active Call tracking
    private val _activeCallId = MutableStateFlow<String?>(null)
    val activeCallId: StateFlow<String?> = _activeCallId.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    val nearbyPeers: StateFlow<List<NearbyPeer>> = nearbyManager.peers
    val nearbyPackets: StateFlow<List<NearbyPacket>> = nearbyManager.packets
    val nearbyStatus: StateFlow<String> = nearbyManager.status

    private var iceObserverJob: Job? = null
    private var callTimerJob: Job? = null
    private var appliedRemoteAnswerForCallId: String? = null
    private val seenIceIds = mutableSetOf<String>()
    private var lastNotifiedCallId: String? = null
    private var lastNotifiedMessageId: String? = null

    init {
        viewModelScope.launch {
            prefs.getOrCreateDeviceId()
        }
        // Keep DataStore pairing metadata & Group chat membership synced when family loads
        viewModelScope.launch {
            activeFamily.collect { fam ->
                if (fam != null) {
                    val role = if (fam.adminUid == currentUserId) "ADMIN" else "FAMILY"
                    prefs.setSessionPairing(role = role, familyId = fam.id, adminUid = fam.adminUid)
                    familyRepo.ensureGroupConversationMember(fam.id, fam.memberUids)
                    SyncScheduler.schedulePeriodicSync(appContext)
                }
            }
        }
        // Cache Admin device statuses into Room for offline dashboard viewing
        viewModelScope.launch {
            adminDevices.collect { devices ->
                if (devices.isNotEmpty()) {
                    localDb.cachedDeviceDao().upsertAll(
                        devices.map { d ->
                            CachedDeviceEntity(
                                id = d.id,
                                familyId = d.familyId,
                                adminUid = d.adminUid,
                                ownerUid = d.ownerUid,
                                ownerName = d.ownerName,
                                deviceName = d.deviceName,
                                deviceModel = d.deviceModel,
                                role = d.role,
                                consentGranted = d.consentGranted,
                                locationSharingEnabled = d.locationSharingEnabled,
                                locationPermissionGranted = d.locationPermissionGranted,
                                backgroundPermissionGranted = d.backgroundPermissionGranted,
                                batteryPercent = d.batteryPercent.toInt(),
                                networkType = d.networkType,
                                online = d.online,
                                revoked = d.revoked,
                                lastLatitude = d.lastLatitude,
                                lastLongitude = d.lastLongitude,
                                lastAccuracyMeters = d.lastAccuracyMeters,
                                lastLocationAtMs = d.lastLocationAtMs,
                                lastSeenAtMs = d.lastSeenAtMs,
                                lastSyncAtMs = d.lastSyncAtMs
                            )
                        }
                    )
                }
            }
        }
        // Automatically stop foreground sharing if Admin revokes this device
        viewModelScope.launch {
            ownDevice.collect { device ->
                if (device != null && device.revoked) {
                    LocationForegroundService.stop(appContext)
                    prefs.setConsentAndSharing(consentGranted = false, sharingEnabled = false)
                }
            }
        }
        // Automatically fetch Admin phone coordinates for live distance badges
        viewModelScope.launch {
            isBackendAdmin.collect { admin ->
                if (admin && LocationCaptureHelper.hasForegroundLocationPermission(appContext)) {
                    refreshAdminOwnCoordinates()
                }
            }
        }
        // Observe incoming calls & trigger alert notification + WebRTC remote answer handling
        viewModelScope.launch {
            calls.collect { list ->
                val incomingRinging = list.firstOrNull { it.calleeUid == currentUserId && it.status == "RINGING" }
                if (incomingRinging != null && incomingRinging.id != lastNotifiedCallId) {
                    lastNotifiedCallId = incomingRinging.id
                    NotificationHelper.showAlertNotification(
                        appContext,
                        NotificationHelper.CHANNEL_CALLS,
                        2001,
                        "Incoming Voice Call",
                        "${incomingRinging.callerName} is calling you"
                    )
                }
                val activeId = _activeCallId.value
                if (activeId != null) {
                    val session = list.firstOrNull { it.id == activeId }
                    if (session != null) {
                        if (session.callerUid == currentUserId &&
                            session.status == "ACTIVE" &&
                            !session.answerSdp.isNullOrBlank() &&
                            appliedRemoteAnswerForCallId != session.id
                        ) {
                            appliedRemoteAnswerForCallId = session.id
                            webRtcManager.setRemoteAnswer(session.answerSdp)
                            startCallTimer()
                        } else if (session.status in listOf("ENDED", "REJECTED", "MISSED")) {
                            cleanupCallResources()
                        }
                    }
                }
            }
        }
        // Observe incoming messages for notifications & read receipts
        viewModelScope.launch {
            activeMessages.collect { msgs ->
                val latest = msgs.lastOrNull()
                if (latest != null && latest.senderUid != currentUserId && latest.id != lastNotifiedMessageId) {
                    lastNotifiedMessageId = latest.id
                    if (!latest.readByUids.contains(currentUserId)) {
                        NotificationHelper.showAlertNotification(
                            appContext,
                            NotificationHelper.CHANNEL_MESSAGES,
                            2002,
                            latest.senderName,
                            if (latest.type == "VOICE") "Sent a voice message" else latest.text.take(120)
                        )
                        val famId = activeFamily.value?.id ?: return@collect
                        commRepo.markMessageRead(famId, latest.conversationId, latest.id)
                    }
                }
            }
        }
    }

    fun clearBanner() {
        _actionBanner.value = null
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch {
            prefs.setLanguage(lang)
            val role = if (isBackendAdmin.value) "ADMIN" else "FAMILY"
            familyRepo.upsertUserProfile(role = role, language = lang)
        }
    }

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch {
            prefs.setThemePreference(theme)
        }
    }

    fun saveTurnConfig(url: String, username: String, credential: String) {
        viewModelScope.launch {
            prefs.setTurnConfig(url, username, credential)
            _actionBanner.value = "TURN server settings saved."
        }
    }

    // --- ONBOARDING & PAIRING ---
    fun createFamilyAsAdmin(familyName: String) {
        viewModelScope.launch {
            _isBusy.value = true
            val deviceId = prefs.getOrCreateDeviceId()
            val lang = localSettings.value.language
            val res = familyRepo.createFamilyAsAdmin(familyName, deviceId, lang)
            _isBusy.value = false
            res.onSuccess { fam ->
                prefs.setSessionPairing("ADMIN", fam.id, fam.adminUid)
                _pendingConsentAfterJoin.value = true
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Failed to create family group"
            }
        }
    }

    fun joinFamilyWithCode(rawCode: String, deviceName: String) {
        viewModelScope.launch {
            _isBusy.value = true
            val deviceId = prefs.getOrCreateDeviceId()
            val lang = localSettings.value.language
            val lookupRes = familyRepo.lookupPairingCode(rawCode)
            if (lookupRes.isFailure) {
                _isBusy.value = false
                _actionBanner.value = lookupRes.exceptionOrNull()?.localizedMessage ?: "Invalid pairing code"
                return@launch
            }
            val codeObj = lookupRes.getOrThrow()
            val joinRes = familyRepo.joinFamilyWithPairingCode(codeObj, deviceId, deviceName, lang)
            _isBusy.value = false
            joinRes.onSuccess {
                prefs.setSessionPairing("FAMILY", codeObj.familyId, codeObj.adminUid)
                _pendingConsentAfterJoin.value = true
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Could not pair device"
            }
        }
    }

    fun grantConsentAndEnableSharing() {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val deviceId = prefs.getOrCreateDeviceId()
            prefs.setConsentAndSharing(consentGranted = true, sharingEnabled = true)
            _pendingConsentAfterJoin.value = false

            localDb.consentLogDao().insert(
                ConsentLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    familyId = fam.id,
                    deviceId = deviceId,
                    action = "GRANTED",
                    details = "Explicit user consent granted for location & telemetry sharing",
                    timestampMs = System.currentTimeMillis()
                )
            )
            LocationForegroundService.startIfPermitted(appContext)
            captureLocationAndQueueSync()
        }
    }

    fun skipLocationSharingForNow() {
        viewModelScope.launch {
            val fam = activeFamily.value
            val deviceId = prefs.getOrCreateDeviceId()
            prefs.setConsentAndSharing(consentGranted = false, sharingEnabled = false)
            _pendingConsentAfterJoin.value = false
            LocationForegroundService.stop(appContext)
            if (fam != null) {
                familyRepo.updateOwnDeviceStatus(
                    familyId = fam.id,
                    deviceId = deviceId,
                    consentGranted = false,
                    locationSharingEnabled = false,
                    locationPermissionGranted = LocationCaptureHelper.hasForegroundLocationPermission(appContext),
                    backgroundPermissionGranted = LocationCaptureHelper.hasBackgroundLocationPermission(appContext),
                    batteryPercent = LocationCaptureHelper.getBatteryPercent(appContext),
                    networkType = LocationCaptureHelper.getNetworkType(appContext),
                    online = LocationCaptureHelper.getNetworkType(appContext) != "OFFLINE"
                )
            }
        }
    }

    fun toggleLocationSharing(enable: Boolean) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val deviceId = prefs.getOrCreateDeviceId()
            if (enable && !LocationCaptureHelper.hasForegroundLocationPermission(appContext)) {
                _actionBanner.value = "Grant location permission first to enable sharing."
                return@launch
            }
            prefs.setConsentAndSharing(consentGranted = true, sharingEnabled = enable)
            localDb.consentLogDao().insert(
                ConsentLogEntity(
                    id = UUID.randomUUID().toString(),
                    userId = currentUserId,
                    familyId = fam.id,
                    deviceId = deviceId,
                    action = if (enable) "RESUMED" else "PAUSED",
                    details = if (enable) "User resumed location sharing" else "User paused location sharing",
                    timestampMs = System.currentTimeMillis()
                )
            )
            if (enable) {
                LocationForegroundService.startIfPermitted(appContext)
                captureLocationAndQueueSync()
            } else {
                LocationForegroundService.stop(appContext)
                familyRepo.updateOwnDeviceStatus(
                    familyId = fam.id,
                    deviceId = deviceId,
                    consentGranted = true,
                    locationSharingEnabled = false,
                    locationPermissionGranted = LocationCaptureHelper.hasForegroundLocationPermission(appContext),
                    backgroundPermissionGranted = LocationCaptureHelper.hasBackgroundLocationPermission(appContext),
                    batteryPercent = LocationCaptureHelper.getBatteryPercent(appContext),
                    networkType = LocationCaptureHelper.getNetworkType(appContext),
                    online = LocationCaptureHelper.getNetworkType(appContext) != "OFFLINE"
                )
            }
        }
    }

    // --- LOCATION CAPTURE & SYNC ---
    fun refreshAdminOwnCoordinates() {
        viewModelScope.launch {
            val captured = LocationCaptureHelper.captureBestLocation(appContext)
            if (captured != null) {
                _adminCurrentLocation.value = captured.latitude to captured.longitude
            }
        }
    }

    fun captureLocationAndQueueSync() {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val settings = localSettings.value
            val deviceId = prefs.getOrCreateDeviceId()

            if (settings.consentGranted && settings.locationSharingEnabled &&
                LocationCaptureHelper.hasForegroundLocationPermission(appContext)
            ) {
                val loc = LocationCaptureHelper.captureBestLocation(appContext)
                if (loc != null) {
                    if (fam.adminUid == currentUserId) {
                        _adminCurrentLocation.value = loc.latitude to loc.longitude
                    }
                    localDb.locationRecordDao().insertRecord(
                        LocationRecordEntity(
                            id = UUID.randomUUID().toString(),
                            familyId = fam.id,
                            adminUid = fam.adminUid,
                            ownerUid = currentUserId,
                            deviceId = deviceId,
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            accuracyMeters = loc.accuracyMeters,
                            altitudeMeters = loc.altitudeMeters,
                            speedMps = loc.speedMps,
                            batteryPercent = LocationCaptureHelper.getBatteryPercent(appContext),
                            networkType = LocationCaptureHelper.getNetworkType(appContext),
                            source = loc.source,
                            recordedAtMs = loc.timestampMs,
                            synced = false
                        )
                    )
                }
            }
            SyncScheduler.enqueueImmediateLocationSync(appContext)
        }
    }

    fun generatePairingCode() {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            _isBusy.value = true
            val res = familyRepo.generatePairingCode(fam.id, fam.name)
            _isBusy.value = false
            res.onSuccess { code ->
                _latestGeneratedCode.value = code
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Failed to generate pairing code"
            }
        }
    }

    fun revokeDevice(deviceId: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val res = familyRepo.revokeDevice(fam.id, deviceId)
            res.onSuccess {
                _actionBanner.value = "Device access revoked."
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Failed to revoke device"
            }
        }
    }

    // --- MESSAGING & VOICE NOTES ---
    fun selectConversation(conversationId: String) {
        _selectedConversationId.value = conversationId
    }

    fun openDirectChat(member: FamilyMember) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val myName = Firebase.auth.currentUser?.displayName ?: "Family"
            val res = commRepo.getOrCreateDirectConversation(fam.id, member.uid, member.displayName, myName)
            res.onSuccess { convId ->
                _selectedConversationId.value = convId
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Could not open chat"
            }
        }
    }

    fun sendTextMessage(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val convId = _selectedConversationId.value
            val conv = conversations.value.firstOrNull { it.id == convId }
            val participants = conv?.participantUids?.ifEmpty { fam.memberUids } ?: fam.memberUids
            val senderName = Firebase.auth.currentUser?.displayName ?: "Family Member"
            val messageId = UUID.randomUUID().toString()
            val nowMs = System.currentTimeMillis()

            // Save locally in Room queue first for offline safety
            localDb.pendingMessageDao().insertPending(
                PendingMessageEntity(
                    id = messageId,
                    conversationId = convId,
                    familyId = fam.id,
                    senderUid = currentUserId,
                    senderName = senderName,
                    participantUidsCsv = participants.joinToString(","),
                    type = "TEXT",
                    text = clean,
                    clientCreatedAtMs = nowMs,
                    synced = false
                )
            )

            val res = commRepo.sendMessageIdempotent(
                messageId = messageId,
                familyId = fam.id,
                conversationId = convId,
                senderName = senderName,
                participantUids = participants,
                type = "TEXT",
                text = clean,
                clientCreatedAtMs = nowMs
            )
            if (res.isSuccess) {
                localDb.pendingMessageDao().markSynced(messageId)
            } else {
                SyncScheduler.enqueueImmediateCommsSync(appContext)
            }
        }
    }

    fun sendVoiceNote(file: File, durationMs: Long) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val convId = _selectedConversationId.value
            val conv = conversations.value.firstOrNull { it.id == convId }
            val participants = conv?.participantUids?.ifEmpty { fam.memberUids } ?: fam.memberUids
            val senderName = Firebase.auth.currentUser?.displayName ?: "Family Member"
            val messageId = UUID.randomUUID().toString()
            val nowMs = System.currentTimeMillis()

            localDb.pendingMessageDao().insertPending(
                PendingMessageEntity(
                    id = messageId,
                    conversationId = convId,
                    familyId = fam.id,
                    senderUid = currentUserId,
                    senderName = senderName,
                    participantUidsCsv = participants.joinToString(","),
                    type = "VOICE",
                    text = "Voice note (${durationMs / 1000L}s)",
                    voiceFilePath = file.absolutePath,
                    voiceDurationMs = durationMs,
                    clientCreatedAtMs = nowMs,
                    synced = false
                )
            )

            val base64 = runCatching {
                if (file.length() <= 400_000L) Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) else null
            }.getOrNull()

            if (base64 == null) {
                _actionBanner.value = "Voice note exceeded size limit."
                return@launch
            }

            val res = commRepo.sendMessageIdempotent(
                messageId = messageId,
                familyId = fam.id,
                conversationId = convId,
                senderName = senderName,
                participantUids = participants,
                type = "VOICE",
                text = "Voice note (${durationMs / 1000L}s)",
                voiceAudioBase64 = base64,
                voiceDurationMs = durationMs,
                clientCreatedAtMs = nowMs
            )
            if (res.isSuccess) {
                localDb.pendingMessageDao().markSynced(messageId)
            } else {
                SyncScheduler.enqueueImmediateCommsSync(appContext)
            }
        }
    }

    // --- WEBRTC VOICE CALLS ---
    fun startVoiceCall(member: FamilyMember) {
        val fam = activeFamily.value ?: return
        if (LocationCaptureHelper.getNetworkType(appContext) == "OFFLINE") {
            _actionBanner.value = "Internet connection is required for online voice calls."
            return
        }
        val settings = localSettings.value
        val myName = Firebase.auth.currentUser?.displayName ?: "Family"
        val participants = listOf(currentUserId, member.uid)

        val initialized = webRtcManager.startPeerConnection(
            turnUrl = settings.turnServerUrl,
            turnUsername = settings.turnUsername,
            turnCredential = settings.turnCredential,
            onLocalIceCandidate = { cand ->
                val callId = _activeCallId.value ?: return@startPeerConnection
                viewModelScope.launch {
                    commRepo.sendIceCandidate(
                        fam.id, callId, participants,
                        cand.sdpMid ?: "0", cand.sdpMLineIndex, cand.sdp
                    )
                }
            }
        )
        if (!initialized) return

        webRtcManager.createOffer(
            onOfferCreated = { sdp ->
                viewModelScope.launch {
                    val res = commRepo.createCallSession(fam.id, myName, member.uid, member.displayName, sdp)
                    res.onSuccess { callId ->
                        _activeCallId.value = callId
                        appliedRemoteAnswerForCallId = null
                        observeCallIceCandidates(fam.id, callId)
                    }.onFailure { err ->
                        webRtcManager.closeSession()
                        _actionBanner.value = err.localizedMessage ?: "Failed to start call"
                    }
                }
            },
            onError = { err ->
                webRtcManager.closeSession()
                _actionBanner.value = err
            }
        )
    }

    fun acceptIncomingCall(call: CallSession) {
        val fam = activeFamily.value ?: return
        val offerSdp = call.offerSdp
        if (offerSdp.isNullOrBlank()) {
            _actionBanner.value = "Invalid call offer received."
            return
        }
        val settings = localSettings.value
        _activeCallId.value = call.id
        val initialized = webRtcManager.startPeerConnection(
            turnUrl = settings.turnServerUrl,
            turnUsername = settings.turnUsername,
            turnCredential = settings.turnCredential,
            onLocalIceCandidate = { cand ->
                viewModelScope.launch {
                    commRepo.sendIceCandidate(
                        fam.id, call.id, call.participantUids,
                        cand.sdpMid ?: "0", cand.sdpMLineIndex, cand.sdp
                    )
                }
            }
        )
        if (!initialized) return

        observeCallIceCandidates(fam.id, call.id)
        webRtcManager.acceptOfferAndCreateAnswer(
            offerSdp = offerSdp,
            onAnswerCreated = { answerSdp ->
                viewModelScope.launch {
                    commRepo.updateCallStatus(fam.id, call.id, "ACTIVE", answerSdp = answerSdp)
                    startCallTimer()
                }
            },
            onError = { err ->
                webRtcManager.closeSession()
                _activeCallId.value = null
                _actionBanner.value = err
            }
        )
    }

    fun rejectIncomingCall(call: CallSession) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            commRepo.updateCallStatus(fam.id, call.id, "REJECTED", endedAtMs = System.currentTimeMillis())
        }
    }

    fun endActiveCall() {
        viewModelScope.launch {
            val fam = activeFamily.value
            val callId = _activeCallId.value
            if (fam != null && callId != null) {
                commRepo.updateCallStatus(fam.id, callId, "ENDED", endedAtMs = System.currentTimeMillis())
            }
            cleanupCallResources()
        }
    }

    private fun observeCallIceCandidates(familyId: String, callId: String) {
        iceObserverJob?.cancel()
        seenIceIds.clear()
        iceObserverJob = viewModelScope.launch {
            commRepo.observeIceCandidates(familyId, callId, currentUserId)
                .catch { err ->
                    unwrapFirestoreError(err)
                }
                .collect { cands ->
                    for (c in cands) {
                        if (c.senderUid != currentUserId && seenIceIds.add(c.id)) {
                            webRtcManager.addRemoteIceCandidate(c.sdpMid, c.sdpMLineIndex.toInt(), c.candidate)
                        }
                    }
                }
        }
    }

    private fun startCallTimer() {
        if (callTimerJob?.isActive == true) return
        _callDurationSeconds.value = 0L
        callTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                _callDurationSeconds.value += 1L
            }
        }
    }

    private fun cleanupCallResources() {
        iceObserverJob?.cancel()
        callTimerJob?.cancel()
        _callDurationSeconds.value = 0L
        _activeCallId.value = null
        appliedRemoteAnswerForCallId = null
        seenIceIds.clear()
        webRtcManager.closeSession()
    }

    // --- MULTIPLAYER TIC-TAC-TOE ---
    fun inviteToTicTacToe(opponent: FamilyMember) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val myName = Firebase.auth.currentUser?.displayName ?: "Player"
            val res = commRepo.createGameSession(fam.id, myName, opponent.uid, opponent.displayName)
            res.onSuccess { id ->
                _selectedGameId.value = id
            }.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Could not start game"
            }
        }
    }

    fun selectGame(gameId: String) {
        _selectedGameId.value = gameId
    }

    fun makeTicTacToeMove(game: GameSession, cellIndex: Int) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            val res = commRepo.submitTicTacToeMove(fam.id, game, cellIndex)
            res.onFailure { err ->
                _actionBanner.value = err.localizedMessage ?: "Invalid move"
            }
        }
    }

    fun restartTicTacToe(gameId: String) {
        viewModelScope.launch {
            val fam = activeFamily.value ?: return@launch
            commRepo.restartGame(fam.id, gameId)
        }
    }

    // --- NEARBY OPTIONAL COMMUNICATION ---
    fun startNearbyDiscovery() {
        nearbyManager.startDiscoveryAndServer()
    }

    fun sendNearbyPacket(peerAddress: String, kind: String, payload: String) {
        val myName = Firebase.auth.currentUser?.displayName ?: "Family"
        nearbyManager.sendBluetoothPacket(peerAddress, myName, kind, payload) { _, msg ->
            _actionBanner.value = msg
        }
    }

    suspend fun prepareSignOut() {
        LocationForegroundService.stop(appContext)
        cleanupCallResources()
        voiceRecorderPlayer.release()
        nearbyManager.stop()
        prefs.clearPairingOnSignOut()
    }

    override fun onCleared() {
        super.onCleared()
        cleanupCallResources()
        voiceRecorderPlayer.release()
        nearbyManager.stop()
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}
