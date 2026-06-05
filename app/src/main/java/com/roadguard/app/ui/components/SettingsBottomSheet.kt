package com.roadguard.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.roadguard.app.domain.model.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    settings: AppSettings,
    onSettingsUpdate: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Lane Departure Warning")
                Switch(
                    checked = settings.laneWarningEnabled,
                    onCheckedChange = {
                        onSettingsUpdate(settings.copy(laneWarningEnabled = it))
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Forward Collision Warning")
                Switch(
                    checked = settings.collisionWarningEnabled,
                    onCheckedChange = {
                        onSettingsUpdate(settings.copy(collisionWarningEnabled = it))
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Slider mit onValueChangeFinished: SharedPrefs (und damit
            // DataStore-Disk-IO) wird NUR am Ende des Drags geschrieben,
            // nicht für jeden Pixel der Bewegung. Vorher: 30+ IO-Calls
            // pro Sekunde Slider-Drag.
            var sliderValue by remember(settings.minFollowingDistanceMeters) {
                mutableStateOf(settings.minFollowingDistanceMeters)
            }
            Text(
                String.format(
                    java.util.Locale.US,
                    "Minimum Following Distance: %.0f m",
                    sliderValue
                )
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    onSettingsUpdate(settings.copy(minFollowingDistanceMeters = sliderValue))
                },
                valueRange = 10f..50f,
                steps = 7
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
