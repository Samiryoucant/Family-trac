package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class UserRole { ADMIN, FAMILY }
enum class NetworkKind { WIFI, CELLULAR, OFFLINE }
enum class LocationSource { FUSED, GPS_PROVIDER, NETWORK_PROVIDER }
enum class MessageType { TEXT, VOICE }
enum class MessageStatus { SENT, DELIVERED, READ }
enum class CallStatus { RINGING, ACTIVE, ENDED, REJECTED, MISSED }
enum class GameStatus { WAITING, ACTIVE, X_WON, O_WON, DRAW }

inline fun <reified T : Enum<T>> safeEnum(raw: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: fallback

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String? = null,
    val photoUrl: String? = null,
    val role: String = "FAMILY",
    val familyId: String? = null,
    val deviceId: String? = null,
    val language: String = "en",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FamilyGroup(
    @DocumentId val id: String = "",
    val adminUid: String = "",
    val adminEmail: String? = null,
    val name: String = "",
    val memberUids: List<String> = emptyList(),
    val lastPairingCode: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PairingCode(
    @DocumentId val id: String = "",
    val code: String = "",
    val familyId: String = "",
    val adminUid: String = "",
    val familyName: String = "",
    val status: String = "ACTIVE",
    val usedByUid: String? = null,
    val usedByDeviceId: String? = null,
    val expiresAt: Timestamp? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val usedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FamilyMember(
    @DocumentId val id: String = "",
    val uid: String = "",
    val familyId: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val role: String = "FAMILY",
    val deviceId: String = "",
    val online: Boolean = false,
    @ServerTimestamp val joinedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PairedDevice(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val adminUid: String = "",
    val ownerUid: String = "",
    val ownerName: String = "",
    val deviceName: String = "",
    val deviceModel: String = "",
    val role: String = "FAMILY",
    val consentGranted: Boolean = false,
    @ServerTimestamp val consentTimestamp: Timestamp? = null,
    val locationSharingEnabled: Boolean = false,
    val locationPermissionGranted: Boolean = false,
    val backgroundPermissionGranted: Boolean = false,
    val batteryPercent: Long = 100L,
    val networkType: String = "OFFLINE",
    val online: Boolean = false,
    val revoked: Boolean = false,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val lastAccuracyMeters: Double? = null,
    val lastLocationAtMs: Long? = null,
    val lastSeenAtMs: Long = 0L,
    val lastSyncAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class LocationRecord(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val adminUid: String = "",
    val ownerUid: String = "",
    val deviceId: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Double = 0.0,
    val altitudeMeters: Double? = null,
    val speedMps: Double? = null,
    val batteryPercent: Long = 100L,
    val networkType: String = "WIFI",
    val source: String = "FUSED",
    val recordedAtMs: Long = 0L,
    @ServerTimestamp val syncedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Conversation(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val type: String = "DIRECT",
    val title: String = "",
    val participantUids: List<String> = emptyList(),
    val lastMessageText: String = "",
    val lastMessageType: String = "TEXT",
    val lastMessageSenderUid: String = "",
    val lastMessageAtMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val conversationId: String = "",
    val familyId: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val participantUids: List<String> = emptyList(),
    val type: String = "TEXT",
    val text: String = "",
    val voiceAudioBase64: String? = null,
    val voiceDurationMs: Long? = null,
    val clientCreatedAtMs: Long = 0L,
    val status: String = "SENT",
    val readByUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CallSession(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val callerUid: String = "",
    val callerName: String = "",
    val calleeUid: String = "",
    val calleeName: String = "",
    val participantUids: List<String> = emptyList(),
    val status: String = "RINGING",
    val offerSdp: String? = null,
    val answerSdp: String? = null,
    val startedAtMs: Long = 0L,
    val endedAtMs: Long? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class IceCandidateModel(
    @DocumentId val id: String = "",
    val senderUid: String = "",
    val participantUids: List<String> = emptyList(),
    val sdpMid: String = "",
    val sdpMLineIndex: Long = 0L,
    val candidate: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class GameSession(
    @DocumentId val id: String = "",
    val familyId: String = "",
    val playerXUid: String = "",
    val playerXName: String = "",
    val playerOUid: String = "",
    val playerOName: String = "",
    val participantUids: List<String> = emptyList(),
    val board: List<String> = List(9) { "" },
    val currentTurn: String = "X",
    val status: String = "ACTIVE",
    val winnerUid: String? = null,
    val moveCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class GameMove(
    @DocumentId val id: String = "",
    val gameId: String = "",
    val playerUid: String = "",
    val symbol: String = "X",
    val cellIndex: Long = 0L,
    val moveNumber: Long = 1L,
    val participantUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
)
