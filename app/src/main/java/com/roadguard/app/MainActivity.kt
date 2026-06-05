package com.roadguard.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.roadguard.app.ui.screens.MainScreen
import com.roadguard.app.ui.theme.RoadGuardTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // enableEdgeToEdge() ist ab Activity 1.8.0 verfügbar. Wir prüfen
        // die SDK-Version, weil das Window-Insets-Verhalten auf < API 30
        // (Android 11) ohnehin anders ist und enableEdgeToEdge() auf
        // älteren Targets zwar nicht crasht, aber Cosmetic-Diffs erzeugt.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            enableEdgeToEdge()
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        setContent {
            RoadGuardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}
