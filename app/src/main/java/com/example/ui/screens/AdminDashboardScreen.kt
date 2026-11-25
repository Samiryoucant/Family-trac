package com.example.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.SyncStatusEntity
import com.example.data.model.FamilyGroup
import com.example.data.model.PairedDevice
import com.example.data.model.PairingCode
import com.example.service.LocationCaptureHelper
import com.example.service.QrCodeHelper
import com.example.ui.i18n.stringsFor
import com.example.ui.theme.MonoFontFamily
import com.example.ui.theme.StatusLiveGreen
import com.example.ui.theme.StatusOfflineSlate
import com.example.ui.theme.StatusStaleAmber

@Composable
fun AdminDashboardScreen(
    language: String,
    family: FamilyGroup?,
    devices: List<PairedDevice>,
    adminCoordinates: Pair<Double, Double>?,
    pendingLocCount: Int,
    syncStatus: SyncStatusEntity?,
    latestCode: PairingCode?,
    isBusy: Boolean,
    onlyDevicesList: Boolean = false,
    onGenerateCode: () -> Unit,
    onSyncNow: () -> Unit,
    onRevokeDevice: (String) -> Unit
) {
    val strings = stringsFor(language)
    var showQrDialog by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<PairedDevice?>(null) }

    val activeDevices = devices.filter { !it.revoked }
    val onlineCount = activeDevices.count { it.online && (System.currentTimeMillis() - it.lastSeenAtMs) < 15 * 60 * 1000L }
    val offlineCount = (activeDevices.size - onlineCount).coerceAtLeast(0)
    val sharingCount = activeDevices.count { it.consentGranted && it.locationSharingEnabled && it.locationPermissionGranted }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!onlyDevicesList) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = family?.name ?: strings.appName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${strings.lastSyncLabel}: ${LocationCaptureHelper.formatRelativeTime(syncStatus?.lastSuccessfulSyncMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                        if (pendingLocCount > 0) {
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = "${strings.pendingSyncQueue}: $pendingLocCount",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    onGenerateCode()
                                    showQrDialog = true
                                },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("pair_new_device_button")
                            ) {
                                Icon(Icons.Default.QrCode2, contentDescription = strings.generateQrBtn, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(strings.generateQrBtn, maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = onSyncNow,
                                modifier = Modifier.testTag("admin_sync_now_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = strings.syncNowBtn, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(strings.syncNowBtn)
                            }
                        }
                    }
                }
            }

            // 2x2 Summary KPI Grid
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryStatTile(
                        label = strings.totalDevices,
                        value = "${activeDevices.size}",
                        icon = Icons.Default.Devices,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatTile(
                        label = strings.onlineDevices,
                        value = "$onlineCount",
                        icon = Icons.Default.CloudDone,
                        tint = StatusLiveGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SummaryStatTile(
                        label = strings.offlineDevices,
                        value = "$offlineCount",
                        icon = Icons.Default.CloudOff,
                        tint = StatusOfflineSlate,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryStatTile(
                        label = strings.sharingLocation,
                        value = "$sharingCount",
                        icon = Icons.Default.LocationOn,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(strings.navDevices, style = MaterialTheme.typography.headlineSmall)
                    Button(
                        onClick = {
                            onGenerateCode()
                            showQrDialog = true
                        },
                        modifier = Modifier.testTag("devices_pair_qr_button")
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = strings.generateQrBtn)
                        Spacer(Modifier.width(6.dp))
                        Text(strings.generateQrBtn)
                    }
                }
            }
        }

        if (devices.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Devices,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("No paired family devices yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            items(devices, key = { it.id }) { device ->
                DeviceStatusCard(
                    device = device,
                    adminCoordinates = adminCoordinates,
                    language = language,
                    onRequestRevoke = { revokeTarget = device }
                )
            }
        }
    }

    if (showQrDialog) {
        val qrBitmap = remember(latestCode?.code) {
            latestCode?.code?.let { QrCodeHelper.generateQrBitmap("FAMILYTRACK:$it", 480) }
        }
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text(strings.pairingCodeLabel) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Pairing QR Code",
                            modifier = Modifier.size(210.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = latestCode?.code.orEmpty(),
                                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = MonoFontFamily),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            strings.codeExpiresNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text("Generating one-time pairing code...")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Done")
                }
            }
        )
    }

    revokeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text(strings.revokeDeviceBtn) },
            text = { Text("Revoke access for ${target.deviceName} (${target.ownerName})? It will stop syncing immediately.") },
            confirmButton = {
                Button(
                    onClick = {
                        onRevokeDevice(target.id)
                        revokeTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(strings.revokeDeviceBtn) }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SummaryStatTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    Card(modifier = modifier) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun DeviceStatusCard(
    device: PairedDevice,
    adminCoordinates: Pair<Double, Double>?,
    language: String,
    onRequestRevoke: () -> Unit
) {
    val strings = stringsFor(language)
    val nowMs = System.currentTimeMillis()
    val isLive = !device.revoked &&
        device.locationSharingEnabled &&
        LocationCaptureHelper.isLocationLive(device.lastLocationAtMs, nowMs)
    val isOnline = !device.revoked && device.online && (nowMs - device.lastSeenAtMs) < 15 * 60 * 1000L

    val distanceText = if (adminCoordinates != null && device.lastLatitude != null && device.lastLongitude != null) {
        val dist = LocationCaptureHelper.distanceMeters(
            adminCoordinates.first, adminCoordinates.second,
            device.lastLatitude, device.lastLongitude
        )
        LocationCaptureHelper.formatDistance(dist)
    } else null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_card_${device.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.deviceName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${device.ownerName} • ${device.deviceModel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val badgeColor = when {
                    device.revoked -> MaterialTheme.colorScheme.errorContainer
                    isLive -> StatusLiveGreen.copy(alpha = 0.16f)
                    else -> StatusStaleAmber.copy(alpha = 0.18f)
                }
                val badgeTextColor = when {
                    device.revoked -> MaterialTheme.colorScheme.onErrorContainer
                    isLive -> StatusLiveGreen
                    else -> StatusStaleAmber
                }
                Surface(color = badgeColor, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = when {
                            device.revoked -> strings.revokedBadge
                            isLive -> strings.liveLocationBadge
                            else -> strings.lastKnownBadge
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeTextColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BatteryChargingFull, contentDescription = strings.batteryLabel, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${device.batteryPercent}%", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Wifi, contentDescription = device.networkType, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isOnline) device.networkType else "OFFLINE", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (device.locationPermissionGranted) Icons.Default.GpsFixed else Icons.Default.GpsOff,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (device.locationPermissionGranted) StatusLiveGreen else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (device.locationPermissionGranted) strings.gpsPermissionOk else strings.gpsPermissionMissing,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${strings.lastSeenLabel}: ${LocationCaptureHelper.formatRelativeTime(device.lastSeenAtMs, nowMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${strings.lastSyncLabel}: ${LocationCaptureHelper.formatRelativeTime(device.lastSyncAtMs, nowMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (device.lastLatitude != null && device.lastLongitude != null) {
                Text(
                    text = buildString {
                        append("%.5f, %.5f".format(device.lastLatitude, device.lastLongitude))
                        device.lastAccuracyMeters?.let { append(" • ±${it.toInt()}m") }
                        if (distanceText != null) append(" • ${strings.distanceFromYou}: $distanceText")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Text(
                    text = strings.noLocationYet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!device.revoked && device.role != "ADMIN") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        onClick = onRequestRevoke,
                        modifier = Modifier.testTag("revoke_device_${device.id}")
                    ) {
                        Icon(Icons.Default.Block, contentDescription = strings.revokeDeviceBtn, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.revokeDeviceBtn)
                    }
                }
            }
        }
    }
}
