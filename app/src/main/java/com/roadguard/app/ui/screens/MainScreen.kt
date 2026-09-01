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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.roadguard.app.domain.model.WarningType
import com.roadguard.app.ui.components.LaneOverlay
import com.roadguard.app.ui.components.SettingsBottomSheet
import com.roadguard.app.ui.components.UpdateBanner
import com.roadguard.app.ui.components.VideoPreview
import com.roadguard.app.ui.theme.DangerRed
import com.roadguard.app.ui.theme.SafeGreen
import com.roadguard.app.ui.theme.WarningYellow
import com.roadguard.app.ui.theme.DarkBackground
import kotlinx.coroutines.launch

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
    // VideoMlAnalyzer wird nur erzeugt wenn der User tatsächlich ein Video
    // auswählt. Frühere Implementierung erzeugte ihn immer (und damit
    // ObjectDetector + TFLite Interpreter + Speicher), auch im Live-Modus.
    val videoAnalyzer = remember(videoUri) {
        if (videoUri != null) VideoMlAnalyzer(appContext = appContext) else null
    }
    DisposableEffect(videoAnalyzer) {
        onDispose {
            val analyzer = videoAnalyzer ?: return@onDispose
            // Closing can block behind an in-flight frame; keep it off the main
            // thread. The analyzer serializes close() with its detector.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                analyzer.close()
            }
        }
    }

    val laneInfo by viewModel.laneInfo.collectAsState()
    val vehicleDistance by viewModel.vehicleDistance.collectAsState()
    val activeWarning by viewModel.activeWarning.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val updateState by updateViewModel.updateState.collectAsState()

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            videoUri = it
            showVideoPicker = true
        }
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
        updateViewModel.checkForUpdates()
    }

    LaunchedEffect(activeWarning) {
        activeWarning?.let { warning ->
            playWarning(context, warning)
        }
    }

    LaunchedEffect(videoAnalyzer, settings.minFollowingDistanceMeters, settings.laneDepartureSensitivity) {
        videoAnalyzer?.updateVehicleThreshold(settings.minFollowingDistanceMeters)
        videoAnalyzer?.updateLaneSensitivity(settings.laneDepartureSensitivity)
    }

    LaunchedEffect(videoAnalyzer) {
        val analyzer = videoAnalyzer ?: return@LaunchedEffect
        kotlinx.coroutines.coroutineScope {
            launch { analyzer.laneInfo.collect { info -> info?.let { viewModel.updateLaneInfo(it) } } }
            launch { analyzer.vehicleDistance.collect { d -> d?.let { viewModel.updateVehicleDistance(it) } } }
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
                        fillCenter = false
                    )

                    WarningOverlay(
                        laneInfo = laneInfo,
                        vehicleDistance = vehicleDistance,
                        modifier = Modifier.fillMaxSize()
                    )

                    StatusBar(
                        laneInfo = laneInfo,
                        vehicleDistance = vehicleDistance,
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
                    settings = settings
                )

                LaneOverlay(
                    laneInfo = laneInfo,
                    modifier = Modifier.fillMaxSize()
                )

                WarningOverlay(
                    laneInfo = laneInfo,
                    vehicleDistance = vehicleDistance,
                    modifier = Modifier.fillMaxSize()
                )

                StatusBar(
                    laneInfo = laneInfo,
                    vehicleDistance = vehicleDistance,
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
            SettingsBottomSheet(
                settings = viewModel.settings.collectAsState().value,
                onSettingsUpdate = viewModel::updateSettings,
                onDismiss = { showSettings = false }
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
    settings: com.roadguard.app.domain.model.AppSettings
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Key mit appContext: bei Context-Wechsel (z.B. Config-Change mit DI-Swap)
    // wird der Analyzer sauber neu erzeugt. Mit Unit oder ohne Key würde
    // der alte Analyzer geleaked.
    val mlAnalyzer = remember(appContext) {
        MlDetectionAnalyzer(vehicleThreshold = 20f, laneSensitivity = 0.5f, appContext = appContext)
    }

    LaunchedEffect(settings.minFollowingDistanceMeters, settings.laneDepartureSensitivity) {
        mlAnalyzer.updateVehicleThreshold(settings.minFollowingDistanceMeters)
        mlAnalyzer.updateLaneSensitivity(settings.laneDepartureSensitivity)
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
            launch { mlAnalyzer.vehicleDistance.collect { d -> d?.let { onDistanceUpdate(it) } } }
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
    laneInfo: com.roadguard.app.domain.model.LaneInfo?,
    vehicleDistance: com.roadguard.app.domain.model.VehicleDistance?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        laneInfo?.let { lane ->
            if (lane.isDriftingLeft) {
                LaneWarningIndicator(
                    isLeft = true,
                    modifier = Modifier.align(Alignment.CenterStart)
                )
            }
            if (lane.isDriftingRight) {
                LaneWarningIndicator(
                    isLeft = false,
                    modifier = Modifier.align(Alignment.CenterEnd)
                )
            }
        }

        vehicleDistance?.let { dist ->
            if (dist.isTooClose) {
                ForwardCollisionWarning(
                    distance = dist.distanceMeters,
                    ttc = dist.timeToCollision,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(
                color = DangerRed.copy(alpha = 0.9f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "COLLISION WARNING",
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
    modifier: Modifier = Modifier
) {
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
        // Lane Status
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("LANE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            // No lane info yet is not a warning — it only means "not evaluated".
            val isLaneOk = laneInfo == null ||
                (!laneInfo.isDriftingLeft && !laneInfo.isDriftingRight)
            val laneText = if (laneInfo == null) "--" else if (isLaneOk) "OK" else "WARN"
            val laneColor = when {
                laneInfo == null -> Color.Gray
                isLaneOk -> SafeGreen
                else -> WarningYellow
            }
            Text(
                laneText,
                color = laneColor,
                style = MaterialTheme.typography.titleSmall
            )
        }

        // Lane visibility
        if (laneInfo != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("LANES", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    "${if (laneInfo.leftLaneVisible) "L" else "-"}${if (laneInfo.rightLaneVisible) "R" else "-"}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }

        // Center offset
        if (laneInfo != null && kotlin.math.abs(laneInfo.centerOffset) > 5f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("OFFSET", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    String.format(java.util.Locale.US, "%.0fpx", laneInfo.centerOffset),
                    color = when {
                        kotlin.math.abs(laneInfo.centerOffset) > 50f -> DangerRed
                        kotlin.math.abs(laneInfo.centerOffset) > 25f -> WarningYellow
                        else -> SafeGreen
                    },
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }

        // Distance
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("DIST", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Text(
                vehicleDistance?.distanceMeters?.let { String.format(java.util.Locale.US, "%.1fm", it) } ?: "--",
                color = when {
                    vehicleDistance == null -> Color.Gray
                    vehicleDistance.distanceMeters < 15f -> DangerRed
                    vehicleDistance.distanceMeters < 25f -> WarningYellow
                    else -> SafeGreen
                },
                style = MaterialTheme.typography.titleSmall
            )
        }

        // Time to collision
        if (vehicleDistance != null && vehicleDistance.timeToCollision < 60f) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("TTC", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Text(
                    String.format(java.util.Locale.US, "%.1fs", vehicleDistance.timeToCollision),
                    color = when {
                        vehicleDistance.timeToCollision < 2f -> DangerRed
                        vehicleDistance.timeToCollision < 4f -> WarningYellow
                        else -> SafeGreen
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

private fun playWarning(context: Context, warning: WarningType) {
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
            val effect = when (warning) {
                is WarningType.LaneDepartureLeft, is WarningType.LaneDepartureRight ->
                    VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                is WarningType.ForwardCollision ->
                    VibrationEffect.createWaveform(longArrayOf(0, 300, 100, 300), -1)
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
