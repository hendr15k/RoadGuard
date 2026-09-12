package com.roadguard.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roadguard.app.domain.model.AlertLogEntry
import com.roadguard.app.domain.model.AppSettings
import com.roadguard.app.domain.model.DriveSessionStats
import com.roadguard.app.domain.model.WarningType
import com.roadguard.app.ui.theme.DangerRed
import com.roadguard.app.ui.theme.SafeGreen
import com.roadguard.app.ui.theme.WarningYellow
import java.util.Locale

private val TAB_TITLES = listOf("Settings", "Drive", "Alerts")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    settings: AppSettings,
    onSettingsUpdate: (AppSettings) -> Unit,
    onDismiss: () -> Unit,
    page: Int = 0,
    onPageChange: (Int) -> Unit = {},
    sessionStats: DriveSessionStats = DriveSessionStats(),
    alertHistory: List<AlertLogEntry> = emptyList(),
    onResetSession: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            TabRow(selectedTabIndex = page.coerceIn(0, TAB_TITLES.lastIndex)) {
                TAB_TITLES.forEachIndexed { index, title ->
                    Tab(
                        selected = page == index,
                        onClick = { onPageChange(index) },
                        text = { Text(title) }
                    )
                }
            }

            when (page.coerceIn(0, TAB_TITLES.lastIndex)) {
                0 -> SettingsPage(settings, onSettingsUpdate)
                1 -> DrivePage(sessionStats, onResetSession)
                else -> AlertsPage(alertHistory)
            }
        }
    }
}

@Composable
private fun SettingsPage(
    settings: AppSettings,
    onSettingsUpdate: (AppSettings) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ToggleRow("Lane Departure Warning", settings.laneWarningEnabled) {
            onSettingsUpdate(settings.copy(laneWarningEnabled = it))
        }
        ToggleRow("Forward Collision Warning", settings.collisionWarningEnabled) {
            onSettingsUpdate(settings.copy(collisionWarningEnabled = it))
        }
        ToggleRow("Audio Alerts", settings.audioAlertsEnabled) {
            onSettingsUpdate(settings.copy(audioAlertsEnabled = it))
        }
        ToggleRow("Vibration Alerts", settings.vibrationAlertsEnabled) {
            onSettingsUpdate(settings.copy(vibrationAlertsEnabled = it))
        }

        Spacer(modifier = Modifier.height(16.dp))

        var sensitivityValue by remember { mutableStateOf(settings.laneDepartureSensitivity) }
        LaunchedEffect(settings.laneDepartureSensitivity) {
            sensitivityValue = settings.laneDepartureSensitivity
        }
        Text(
            String.format(Locale.US, "Lane Departure Sensitivity: %.1f", sensitivityValue)
        )
        Slider(
            value = sensitivityValue,
            onValueChange = { sensitivityValue = it },
            onValueChangeFinished = {
                onSettingsUpdate(settings.copy(laneDepartureSensitivity = sensitivityValue))
            },
            valueRange = AppSettings.MIN_SENSITIVITY..AppSettings.MAX_SENSITIVITY,
            steps = 9
        )
        HelpText(
            "Higher = narrower drift window, earlier warnings (multiplies the " +
                "frame-relative 4% window by 1.5 − sensitivity)."
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Slider mit onValueChangeFinished: SharedPrefs (und damit
        // DataStore-Disk-IO) wird NUR am Ende des Drags geschrieben,
        // nicht für jeden Pixel der Bewegung.
        var distanceValue by remember { mutableStateOf(settings.minFollowingDistanceMeters) }
        LaunchedEffect(settings.minFollowingDistanceMeters) {
            distanceValue = settings.minFollowingDistanceMeters
        }
        Text(
            String.format(Locale.US, "Minimum Following Distance: %.0f m", distanceValue)
        )
        Slider(
            value = distanceValue,
            onValueChange = { distanceValue = it },
            onValueChangeFinished = {
                onSettingsUpdate(settings.copy(minFollowingDistanceMeters = distanceValue))
            },
            valueRange = AppSettings.MIN_FOLLOWING_DISTANCE_M..AppSettings.MAX_FOLLOWING_DISTANCE_M,
            steps = 7
        )

        Spacer(modifier = Modifier.height(16.dp))

        var repeatValue by remember { mutableStateOf(settings.alertRepeatSeconds) }
        LaunchedEffect(settings.alertRepeatSeconds) {
            repeatValue = settings.alertRepeatSeconds
        }
        Text(
            String.format(Locale.US, "Alert repeat: %.1f s", repeatValue)
        )
        Slider(
            value = repeatValue,
            onValueChange = { repeatValue = it },
            onValueChangeFinished = {
                onSettingsUpdate(settings.copy(alertRepeatSeconds = repeatValue))
            },
            valueRange = AppSettings.MIN_REPEAT_SECONDS..AppSettings.MAX_REPEAT_SECONDS,
            steps = 8
        )
        HelpText(
            "Shorter = more frequent reminders. Collision always repeats at 1.0 s " +
                "and escalates after 3 repeats."
        )

        Spacer(modifier = Modifier.height(16.dp))

        var hoodValue by remember { mutableStateOf(settings.hoodFraction) }
        LaunchedEffect(settings.hoodFraction) {
            hoodValue = settings.hoodFraction
        }
        Text(
            String.format(Locale.US, "Hood zone: %.0f%%", hoodValue * 100f)
        )
        Slider(
            value = hoodValue,
            onValueChange = { hoodValue = it },
            onValueChangeFinished = {
                onSettingsUpdate(settings.copy(hoodFraction = hoodValue))
            },
            valueRange = AppSettings.MIN_HOOD_FRACTION..AppSettings.MAX_HOOD_FRACTION,
            steps = 24
        )
        HelpText(
            "Bottom of the camera image covered by your car's hood. " +
                "That band is dimmed in the preview and ignored by lane " +
                "detection, so hood edges/reflections are not read as road. " +
                "Raise it until the shaded zone matches your bonnet."
        )

        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { onSettingsUpdate(settings.copy(alertRepeatSeconds = 3f)) },
            enabled = settings.alertRepeatSeconds != 3f,
            modifier = Modifier.align(Alignment.End)
        ) { Text("Reset repeat to default") }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun DrivePage(
    stats: DriveSessionStats,
    onResetSession: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ScoreCard(stats)

        Spacer(modifier = Modifier.height(16.dp))

        StatRow("Drive time", formatDuration(stats.durationMs))
        StatRow("Lane departures", "${stats.laneDepartureCount}")
        StatRow("Collision warnings", "${stats.collisionCount}")
        StatRow("Urgent escalations", "${stats.urgentCollisionCount}")
        StatRow(
            "Time under warning",
            "${formatDuration(stats.warningTimeMs)} " +
                String.format(Locale.US, "(%.0f%%)", stats.warningTimeFraction * 100f)
        )

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onResetSession,
            modifier = Modifier.align(Alignment.End)
        ) { Text("Reset current drive") }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ScoreCard(stats: DriveSessionStats) {
    val score = stats.safetyScore
    val (label, color) = when {
        score >= 80 -> "Good" to SafeGreen
        score >= 50 -> "Caution" to WarningYellow
        else -> "Risky" to DangerRed
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Safety Score", style = MaterialTheme.typography.labelMedium)
        Text(
            "$score",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(label, color = color, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun AlertsPage(history: List<AlertLogEntry>) {
    if (history.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No alerts recorded yet. Warnings appear here as they happen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(history, key = { "${it.atMs}-${it.type}-${it.repeatIndex}" }) { entry ->
            AlertLogRow(entry)
        }
    }
}

@Composable
private fun AlertLogRow(entry: AlertLogEntry) {
    val color = when (entry.type) {
        is WarningType.ForwardCollision -> DangerRed
        is WarningType.LaneDepartureLeft, is WarningType.LaneDepartureRight -> WarningYellow
    }
    val title = when (entry.type) {
        is WarningType.ForwardCollision -> if (entry.urgent) "Collision (urgent)" else "Collision warning"
        is WarningType.LaneDepartureLeft -> "Lane departure — left"
        is WarningType.LaneDepartureRight -> "Lane departure — right"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall)
            if (entry.repeatIndex > 0) {
                Text(
                    "repeat #${entry.repeatIndex}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            formatClockTime(entry.atMs),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HelpText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        val hours = minutes / 60
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun formatClockTime(atMs: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(atMs))
