package com.roadguard.app.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import java.util.concurrent.Executors
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.roadguard.app.data.ml.MlDetectionAnalyzer
import com.roadguard.app.data.ml.VideoMlAnalyzer
import com.roadguard.app.domain.model.AlertPhase
import com.roadguard.app.domain.model.AlertPolicy
import com.roadguard.app.domain.model.AlertSignal
import com.roadguard.app.domain.model.AlertState
import com.roadguard.app.domain.model.AlertLogEntry
import com.roadguard.app.domain.model.DriveSessionStats
import com.roadguard.app.domain.model.WarningType
import com.roadguard.app.domain.model.isFresh
import com.roadguard.app.ui.audio.AlertTonePlayer
import com.roadguard.app.ui.audio.soundFor
import com.roadguard.app.ui.components.LaneOverlay
import com.roadguard.app.ui.components.SettingsBottomSheet
import com.roadguard.app.ui.components.UpdateBanner
import com.roadguard.app.ui.components.VideoPreview
import com.roadguard.app.ui.theme.DangerRed
import com.roadguard.app.ui.theme.SafeGreen
import com.roadguard.app.ui.theme.WarningYellow
import com.roadguard.app.ui.theme.DarkBackground
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Closes disposed analyzers off the main thread. Process-lifetime on purpose —
 * see the comment at its use site: a scope belonging to the composition can be
 * cancelled before the close it was handed actually runs.
 */
private val closeExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "roadguard-analyzer-close").apply { isDaemon = true }
}

/**
 * A ticking clock for HUD freshness.
 *
 * `remember(key) { System.currentTimeMillis() }` is not enough to clear a stalled
 * HUD: when the pipeline stops publishing there is no further recomposition, so
 * the remembered value is never re-read and the stale reading stays on screen —
 * precisely the case the staleness check exists for. This state re-emits on its
 * own, so the display goes blank a tick after the samples stop.
 */
@Composable
private fun rememberFreshnessTick(periodMs: Long = 500L): Long {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(periodMs)
        }
    }
    return now
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel()
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val context = LocalContext.current

    var showSettings by remember { mutableStateOf(false) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var showVideoPicker by remember { mutableStateOf(false) }
    val appContext = context.applicationContext
    // Keys: videoUri only. Do NOT add a configuration key here: the manifest
    // declares configChanges="orientation|screenSize|...", so the composition
    // (and this remember state) survives rotation. Re-creating the analyzer on
    // rotation would leave VideoPreview's frame loop — which is started in a
    // DisposableEffect keyed on exoPlayer and captures the analyzer by value —
    // feeding an already-closed analyzer, silently killing video detection.
    // VideoMlAnalyzer wird nur erzeugt wenn der User tatsächlich ein Video
    // auswählt. Frühere Implementierung erzeugte ihn immer (und damit
    // ObjectDetector + TFLite Interpreter + Speicher), auch im Live-Modus.
    val videoAnalyzer = remember(videoUri) {
        if (videoUri != null) VideoMlAnalyzer(appContext = appContext) else null
    }
    // One process-lifetime close executor: a scope created inside this
    // composition cannot be used to close the analyzer, because Compose
    // disposes effects in reverse declaration order — the scope effect (declared
    // above) is torn down and cancelled in the same pass, so a close() launched
    // into it right before the cancel can be killed before it ever starts and
    // the analyzer (TFLite interpreter, ML Kit detector, native buffers) is
    // leaked. An executor outside the composition always runs the close.
    DisposableEffect(videoAnalyzer) {
        onDispose {
            val analyzer = videoAnalyzer ?: return@onDispose
            // Closing can block behind an in-flight frame; keep it off the main
            // thread. The analyzer serializes close() with its detector.
            closeExecutor.execute {
                try {
                    analyzer.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
    // Keep the display awake: a driving-safety app that lets the screen time out
    // mid-drive is silently useless. Released when MainScreen leaves the tree.
    val activity = context as? android.app.Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val laneInfo by viewModel.laneInfo.collectAsState()
    val vehicleDistance by viewModel.vehicleDistance.collectAsState()
    val alertState by viewModel.alertState.collectAsState()
    val alertSignal by viewModel.alertSignal.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val sessionStats by viewModel.sessionStats.collectAsState()
    val alertHistory by viewModel.alertHistory.collectAsState()
    val updateState by updateViewModel.updateState.collectAsState()

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.clearDetectionState()
            videoUri = it
            showVideoPicker = true
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    // The ToneGenerator lives here — not inside playAlert() — because creating
    // one per alarm would allocate a native AudioTrack on every repeat while a
    // hazard persists. It is released when MainScreen leaves the composition.
    val tonePlayer = remember { AlertTonePlayer() }
    DisposableEffect(tonePlayer) {
        onDispose { tonePlayer.release() }
    }

    // Haptik/Audio nur noch auf dem entprellten Signal — nicht mehr pro Frame.
    // consumeAlertSignal() verhindert, dass dieselbe Emission beim nächsten
    // Recompose erneut vibriert.
    LaunchedEffect(alertSignal, settings.audioAlertsEnabled, settings.vibrationAlertsEnabled) {
        val signal = alertSignal ?: return@LaunchedEffect
        playAlert(
            context,
            signal,
            tonePlayer,
            playSound = settings.audioAlertsEnabled,
            vibrate = settings.vibrationAlertsEnabled
        )
        viewModel.consumeAlertSignal()
    }

    LaunchedEffect(videoAnalyzer, settings.minFollowingDistanceMeters, settings.laneDepartureSensitivity, settings.hoodFraction) {
        videoAnalyzer?.updateVehicleThreshold(settings.minFollowingDistanceMeters)
        videoAnalyzer?.updateLaneSensitivity(settings.laneDepartureSensitivity)
        videoAnalyzer?.updateHoodFraction(settings.hoodFraction)
    }

    LaunchedEffect(videoAnalyzer) {
        val analyzer = videoAnalyzer ?: return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            launch { analyzer.laneInfo.collect { info -> info?.let { viewModel.updateLaneInfo(it) } } }
            launch { analyzer.vehicleDistance.collect { d -> viewModel.updateVehicleDistanceFrom(d) } }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            showVideoPicker && videoUri != null -> {
                val currentVideoUri = videoUri
                if (currentVideoUri != null) {
                    VideoPreview(
                        videoUri = currentVideoUri,
                        modifier = Modifier.fillMaxSize(),
                        videoAnalyzer = videoAnalyzer
                    )

                    LaneOverlay(
                        laneInfo = laneInfo,
                        modifier = Modifier.fillMaxSize(),
                        fillCenter = false,
                        hoodFraction = settings.hoodFraction
                    )

                    WarningOverlay(
                        alertState = alertState,
                        vehicleDistance = vehicleDistance,
                        modifier = Modifier.fillMaxSize()
                    )

                    StatusBar(
                        laneInfo = laneInfo,
                        vehicleDistance = vehicleDistance,
                        alertState = alertState,
                        distanceThreshold = settings.minFollowingDistanceMeters,
                        sessionStats = sessionStats,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 48.dp)
                    )

                    FloatingActionButton(
                        onClick = {
                            videoUri = null
                            showVideoPicker = false
                            viewModel.clearDetectionState()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = "Camera Mode", tint = Color.White)
                    }
                }
            }
            cameraPermissionState.status.isGranted -> {
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    onLaneUpdate = viewModel::updateLaneInfo,
                    onDistanceUpdate = viewModel::updateVehicleDistance,
                    appContext = appContext,
                    settings = settings,
                    onDistanceUpdateFrom = viewModel::updateVehicleDistanceFrom
                )

                LaneOverlay(
                    laneInfo = laneInfo,
                    modifier = Modifier.fillMaxSize(),
                    hoodFraction = settings.hoodFraction
                )

                WarningOverlay(
                    alertState = alertState,
                    vehicleDistance = vehicleDistance,
                    modifier = Modifier.fillMaxSize()
                )

                StatusBar(
                    laneInfo = laneInfo,
                    vehicleDistance = vehicleDistance,
                    alertState = alertState,
                    distanceThreshold = settings.minFollowingDistanceMeters,
                    sessionStats = sessionStats,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp)
                )

                FloatingActionButton(
                    onClick = { videoPickerLauncher.launch("video/*") },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = "Video Mode", tint = Color.White)
                }
            }
            else -> {
                PermissionRequest(
                    onRequestPermission = { cameraPermissionState.launchPermissionRequest() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        FloatingActionButton(
            onClick = { showSettings = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                modifier = Modifier.size(32.dp)
            )
        }

        if (showSettings) {
            var settingsPage by remember { mutableStateOf(0) }
            SettingsBottomSheet(
                settings = settings,
                onSettingsUpdate = viewModel::updateSettings,
                onDismiss = { showSettings = false },
                page = settingsPage,
                onPageChange = { settingsPage = it },
                sessionStats = sessionStats,
                alertHistory = alertHistory,
                onResetSession = viewModel::resetSession
            )
        }

        UpdateBanner(
            updateState = updateState,
            onDismiss = { updateViewModel.dismissUpdate() },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onLaneUpdate: (com.roadguard.app.domain.model.LaneInfo) -> Unit,
    onDistanceUpdate: (com.roadguard.app.domain.model.VehicleDistance) -> Unit,
    appContext: Context? = null,
    settings: com.roadguard.app.domain.model.AppSettings,
    onDistanceUpdateFrom: (com.roadguard.app.domain.model.VehicleDistance?) -> Unit = { it?.let(onDistanceUpdate) }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Key mit appContext: bei Context-Wechsel (z.B. Config-Change mit DI-Swap)
    // wird der Analyzer sauber neu erzeugt. Mit Unit oder ohne Key würde
    // der alte Analyzer geleaked.
    val mlAnalyzer = remember(appContext) {
        MlDetectionAnalyzer(vehicleThreshold = 20f, laneSensitivity = 0.5f, appContext = appContext)
    }

    LaunchedEffect(settings.minFollowingDistanceMeters, settings.laneDepartureSensitivity, settings.hoodFraction) {
        mlAnalyzer.updateVehicleThreshold(settings.minFollowingDistanceMeters)
        mlAnalyzer.updateLaneSensitivity(settings.laneDepartureSensitivity)
        mlAnalyzer.updateHoodFraction(settings.hoodFraction)
    }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    // === Parallele Collectors (eine Pipeline, nicht zwei) ===
    // Gleicher Bug wie in MainScreen: zwei separate LaunchedEffect(mlAnalyzer)-
    // Blöcke mit identischem Key → sequenziell → der zweite lief nie.
    LaunchedEffect(mlAnalyzer) {
        kotlinx.coroutines.coroutineScope {
            launch { mlAnalyzer.laneInfo.collect { laneInfo -> laneInfo?.let { onLaneUpdate(it) } } }
            launch { mlAnalyzer.vehicleDistance.collect { d -> onDistanceUpdateFrom(d) } }
        }
    }

    DisposableEffect(lifecycleOwner, mlAnalyzer) {
        // Lane + TFLite inference is CPU-heavy and must never run on Main.
        // The worker belongs to this binding lifecycle; a rebind therefore never
        // receives a previously shut-down executor.
        val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
            // Daemon threads: a stuck frame must never keep the process alive.
            Thread(runnable, "roadguard-analysis").apply { isDaemon = true }
        }
        var boundAnalysis: ImageAnalysis? = null
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val listener = Runnable {
            try {
                val cameraProviderInstance = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(analysisExecutor, mlAnalyzer)
                        boundAnalysis = it
                    }

                cameraProviderInstance.unbindAll()
                cameraProviderInstance.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        cameraProviderFuture.addListener(listener, mainExecutor)

        onDispose {
            try {
                if (cameraProviderFuture.isDone) {
                    boundAnalysis?.clearAnalyzer()
                    cameraProviderFuture.get().unbindAll()
                } else {
                    cameraProviderFuture.cancel(true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // The analyzer now runs on the worker, so dispose it there. Queuing
            // close() before shutdown() serializes it against in-flight frames —
            // closing from Main while a frame is processed crashes the interpreter.
            analysisExecutor.execute {
                try {
                    mlAnalyzer.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // Orderly shutdown only — the queued close() still runs, and blocking
            // here would stall the main thread for up to a full frame.
            analysisExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

@Composable
fun WarningOverlay(
    alertState: AlertState,
    vehicleDistance: com.roadguard.app.domain.model.VehicleDistance?,
    modifier: Modifier = Modifier
) {
    // Nur bestätigte (ACTIVE) Alerts werden visuell gezeigt — ein einzelner
    // CONFIRMING-Frame darf nicht blinken. So bleibt die Anzeige ruhig bis
    // das Gate wirklich feuert.
    val warning = (alertState as? AlertState.Warning)?.takeIf { it.phase == AlertPhase.ACTIVE }
    // Escalated collisions flash so a driver glancing from the road cannot miss
    // them: the system time (not a remembered value) drives the pulse, so the
    // flash keeps running while the HUD holds the same warning.
    val urgent = warning?.let { it.repeatCount >= AlertPolicy.ESCALATION_REPEATS && it.type is WarningType.ForwardCollision } ?: false
    // The tick re-emits on its own; keying the pulse on a plain clock read would
    // freeze — a held warning produces no further recomposition.
    val pulseNow = rememberFreshnessTick(periodMs = 200L)
    val pulseOn = urgent && (pulseNow / 400L) % 2L == 0L
    Box(modifier = modifier) {
        if (urgent) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DangerRed.copy(alpha = if (pulseOn) 0.18f else 0.06f))
            )
        }
        when (warning?.type) {
            is WarningType.LaneDepartureLeft -> LaneWarningIndicator(
                isLeft = true,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            is WarningType.LaneDepartureRight -> LaneWarningIndicator(
                isLeft = false,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
            else -> {
                // Kein aktiver Lane-Alert — nichts zeigen (verhindert Stale-Blinken)
            }
        }
        if (warning?.type is WarningType.ForwardCollision) {
            val dist = vehicleDistance ?: return@Box
            ForwardCollisionWarning(
                distance = dist.distanceMeters,
                ttc = dist.timeToCollision,
                urgent = urgent,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
fun LaneWarningIndicator(isLeft: Boolean, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .size(80.dp)
            .padding(8.dp)
    ) {
        drawCircle(
            color = WarningYellow,
            radius = size.minDimension / 2,
            style = Stroke(width = 8f)
        )
        val arrowX = if (isLeft) size.width * 0.3f else size.width * 0.7f
        drawLine(
            color = WarningYellow,
            start = Offset(arrowX, size.height * 0.2f),
            end = Offset(arrowX, size.height * 0.8f),
            strokeWidth = 8f
        )
        drawLine(
            color = WarningYellow,
            start = Offset(arrowX, size.height * 0.2f),
            end = Offset(if (isLeft) arrowX + 20f else arrowX - 20f, size.height * 0.4f),
            strokeWidth = 8f
        )
        drawLine(
            color = WarningYellow,
            start = Offset(arrowX, size.height * 0.8f),
            end = Offset(if (isLeft) arrowX + 20f else arrowX - 20f, size.height * 0.6f),
            strokeWidth = 8f
        )
    }
}

@Composable
fun ForwardCollisionWarning(
    distance: Float,
    ttc: Float,
    urgent: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                color = DangerRed.copy(alpha = if (urgent) 1f else 0.9f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (urgent) "COLLISION — BRAKE" else "COLLISION WARNING",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = String.format(java.util.Locale.US, "%.1f m", distance),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge
        )
        if (ttc < 60f) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = String.format(java.util.Locale.US, "TTC: %.1f s", ttc),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun StatusBar(
    laneInfo: com.roadguard.app.domain.model.LaneInfo?,
    vehicleDistance: com.roadguard.app.domain.model.VehicleDistance?,
    alertState: AlertState,
    modifier: Modifier = Modifier,
    distanceThreshold: Float = 20f,
    sessionStats: DriveSessionStats = DriveSessionStats()
) {
    // A stale sample must disappear from the HUD, not just from the alarm: a
    // paused video or a stalled pipeline left the last "DIST 12.4m / TTC 1.8s"
    // on screen after the gate had already dropped the hazard. The tick is a
    // self-updating clock (see rememberFreshnessTick) — keying a plain
    // System.currentTimeMillis() on the samples would never re-run, because a
    // stalled pipeline produces no recomposition at all.
    val now = rememberFreshnessTick()
    val freshLane = laneInfo?.takeIf { it.isFresh(now) }
    val freshDistance = vehicleDistance?.takeIf { it.isFresh(now) }
    val activeType = (alertState as? AlertState.Warning)?.takeIf { it.phase == AlertPhase.ACTIVE }?.type
    Row(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Lane Status — warnt nur wenn das Gate ACTIVE ist, nicht bei jedem raw drift
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LANE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            val laneText = when {
                activeType is WarningType.LaneDepartureLeft || activeType is WarningType.LaneDepartureRight -> "WARN"
                // The gate drops a sample below MIN_LANE_CONFIDENCE without any
                // warning, so reporting "OK" here claimed a healthy lane the
                // safety system had actually decided to ignore.
                freshLane == null -> "--"
                freshLane.confidence < AlertPolicy.MIN_LANE_CONFIDENCE -> "??"
                else -> "OK"
            }
            val laneColor = when (activeType) {
                is WarningType.LaneDepartureLeft, is WarningType.LaneDepartureRight -> WarningYellow
                else -> if (freshLane == null) Color.Gray
                    else if (freshLane.confidence < AlertPolicy.MIN_LANE_CONFIDENCE) WarningYellow
                    else SafeGreen
            }
            Text(
                laneText,
                color = laneColor,
                style = MaterialTheme.typography.titleSmall
            )
        }

        // Lane visibility
        if (freshLane != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("LANES", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    "${if (freshLane.leftLaneVisible) "L" else "-"}${if (freshLane.rightLaneVisible) "R" else "-"}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }

        // Center offset
        if (freshLane != null && kotlin.math.abs(freshLane.centerOffset) > 5f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("OFFSET", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    String.format(java.util.Locale.US, "%.0fpx", freshLane.centerOffset),
                    color = when {
                        kotlin.math.abs(freshLane.centerOffset) > 50f -> DangerRed
                        kotlin.math.abs(freshLane.centerOffset) > 25f -> WarningYellow
                        else -> SafeGreen
                    },
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }

        // Distance — thresholds scale with the user's setting (were hardcoded
        // 15/25 while the real alarm uses minFollowingDistanceMeters).
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DIST", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Text(
                freshDistance?.distanceMeters?.let { String.format(java.util.Locale.US, "%.1fm", it) } ?: "--",
                color = when {
                    activeType is WarningType.ForwardCollision -> DangerRed
                    freshDistance == null -> Color.Gray
                    freshDistance.distanceMeters < distanceThreshold * 0.75f -> DangerRed
                    freshDistance.distanceMeters < distanceThreshold * 1.25f -> WarningYellow
                    else -> SafeGreen
                },
                style = MaterialTheme.typography.titleSmall
            )
        }

        // Time to collision
        if (freshDistance != null && freshDistance.timeToCollision < 60f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TTC", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    String.format(java.util.Locale.US, "%.1fs", freshDistance.timeToCollision),
                    color = when {
                        activeType is WarningType.ForwardCollision -> DangerRed
                        freshDistance.timeToCollision < 2f -> DangerRed
                        freshDistance.timeToCollision < 4f -> WarningYellow
                        else -> SafeGreen
                    },
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }

        // Drive score — one number for "how is this drive going".
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SCORE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            val score = sessionStats.safetyScore
            Text(
                "$score",
                color = when {
                    score >= 80 -> SafeGreen
                    score >= 50 -> WarningYellow
                    else -> DangerRed
                },
                style = MaterialTheme.typography.titleSmall
            )
        }

        // Incident tally (lane departures + collisions).
        if (sessionStats.totalIncidents > 0) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("INCIDENTS", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    "${sessionStats.totalIncidents}",
                    color = if (sessionStats.collisionCount > 0) DangerRed else WarningYellow,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }

        // Gate phase indicator (CONFIRMING = gelb, ACTIVE = rot)
        (alertState as? AlertState.Warning)?.let { warning ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ALERT", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    when (warning.phase) {
                        AlertPhase.CONFIRMING -> "…"
                        AlertPhase.ACTIVE -> when (warning.type) {
                            is WarningType.ForwardCollision -> "COLL"
                            is WarningType.LaneDepartureLeft -> "LEFT"
                            is WarningType.LaneDepartureRight -> "RIGHT"
                        }
                    },
                    color = when (warning.phase) {
                        AlertPhase.CONFIRMING -> WarningYellow
                        AlertPhase.ACTIVE -> DangerRed
                    },
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

@Composable
fun PermissionRequest(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.background(DarkBackground),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Camera Permission Required",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

private fun playAlert(
    context: Context,
    signal: AlertSignal,
    tonePlayer: AlertTonePlayer? = null,
    playSound: Boolean = true,
    vibrate: Boolean = true
) {
    if (playSound) {
        try {
            tonePlayer?.play(soundFor(signal))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    if (!vibrate) return
    try {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        vibrator?.let { v ->
            if (!v.hasVibrator()) return
            val effect = when (signal.type) {
                is WarningType.LaneDepartureLeft, is WarningType.LaneDepartureRight ->
                    VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                is WarningType.ForwardCollision ->
                    if (signal.urgent) {
                        // Escalated collision: longer, more insistent pattern
                        VibrationEffect.createWaveform(longArrayOf(0, 400, 80, 400, 80, 400), -1)
                    } else {
                        VibrationEffect.createWaveform(longArrayOf(0, 300, 100, 300), -1)
                    }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
