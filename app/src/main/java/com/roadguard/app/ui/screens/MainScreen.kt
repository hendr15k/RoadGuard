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
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.roadguard.app.data.ml.MlDetectionAnalyzer
import com.roadguard.app.data.ml.VideoMlAnalyzer
import com.roadguard.app.domain.model.WarningType
import com.roadguard.app.ui.components.SettingsBottomSheet
import com.roadguard.app.ui.components.UpdateBanner
import com.roadguard.app.ui.components.VideoPreview
import com.roadguard.app.ui.theme.DangerRed
import com.roadguard.app.ui.theme.SafeGreen
import com.roadguard.app.ui.theme.WarningYellow
import com.roadguard.app.ui.theme.DarkBackground

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
    val videoAnalyzer = remember { VideoMlAnalyzer(appContext = appContext) }

    val laneInfo by viewModel.laneInfo.collectAsState()
    val vehicleDistance by viewModel.vehicleDistance.collectAsState()
    val activeWarning by viewModel.activeWarning.collectAsState()
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
    }

    LaunchedEffect(activeWarning) {
        activeWarning?.let { warning ->
            playWarning(context, warning)
        }
    }

    LaunchedEffect(videoAnalyzer) {
        videoAnalyzer.laneInfo.collect { laneInfo ->
            laneInfo?.let { viewModel.updateLaneInfo(it) }
        }
    }

    LaunchedEffect(videoAnalyzer) {
        videoAnalyzer.vehicleDistance.collect { distance ->
            distance?.let { viewModel.updateVehicleDistance(it) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            showVideoPicker && videoUri != null -> {
                VideoPreview(
                    videoUri = videoUri!!,
                    modifier = Modifier.fillMaxSize(),
                    videoAnalyzer = videoAnalyzer
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
                    onClick = { showVideoPicker = false },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = "Camera Mode", tint = Color.White)
                }
            }
            cameraPermissionState.status.isGranted -> {
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    onLaneUpdate = viewModel::updateLaneInfo,
                    onDistanceUpdate = viewModel::updateVehicleDistance,
                    appContext = appContext
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
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Text("", color = Color.White)
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
    appContext: Context? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mlAnalyzer = remember { MlDetectionAnalyzer(vehicleThreshold = 20f, laneSensitivity = 0.5f, appContext = appContext) }
    val cameraProvider = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }

    LaunchedEffect(mlAnalyzer) {
        mlAnalyzer.laneInfo.collect { laneInfo ->
            laneInfo?.let { onLaneUpdate(it) }
        }
    }

    LaunchedEffect(mlAnalyzer) {
        mlAnalyzer.vehicleDistance.collect { distance ->
            distance?.let { onDistanceUpdate(it) }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderInstance = cameraProvider.get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(ContextCompat.getMainExecutor(context), mlAnalyzer) }

        try {
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

        onDispose {
            cameraProviderInstance.unbindAll()
            mlAnalyzer.close()
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
            text = "%.1f m".format(distance),
            color = Color.White,
            style = MaterialTheme.typography.titleLarge
        )
        if (ttc < 60f) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "TTC: %.1f s".format(ttc),
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
            val isLaneOk = laneInfo?.isDriftingLeft == false && laneInfo?.isDriftingRight == false
            Text(
                if (isLaneOk) "OK" else "WARN",
                color = if (isLaneOk) SafeGreen else WarningYellow,
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
                    "%.0fpx".format(laneInfo.centerOffset),
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
                vehicleDistance?.distanceMeters?.let { "%.1fm".format(it) } ?: "--",
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
                    "%.1fs".format(vehicleDistance.timeToCollision),
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
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val effect = when (warning) {
            is WarningType.LaneDepartureLeft, is WarningType.LaneDepartureRight ->
                VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
            is WarningType.ForwardCollision ->
                VibrationEffect.createWaveform(longArrayOf(0, 300, 100, 300), -1)
        }
        vibrator.vibrate(effect)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
