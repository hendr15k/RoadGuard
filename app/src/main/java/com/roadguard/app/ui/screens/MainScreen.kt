package com.roadguard.app.ui.screens

import android.Manifest
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.roadguard.app.domain.model.WarningType
import com.roadguard.app.ui.components.SettingsBottomSheet
import com.roadguard.app.ui.theme.DangerRed
import com.roadguard.app.ui.theme.SafeGreen
import com.roadguard.app.ui.theme.WarningYellow
import com.roadguard.app.ui.theme.DarkBackground

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val context = LocalContext.current

    var showSettings by remember { mutableStateOf(false) }

    val laneInfo by viewModel.laneInfo.collectAsState()
    val vehicleDistance by viewModel.vehicleDistance.collectAsState()
    val activeWarning by viewModel.activeWarning.collectAsState()

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

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onLaneUpdate = viewModel::updateLaneInfo,
                onDistanceUpdate = viewModel::updateVehicleDistance
            )

            WarningOverlay(
                laneInfo = laneInfo,
                vehicleDistance = vehicleDistance,
                modifier = Modifier.fillMaxSize()
            )

            StatusBar(
                distance = vehicleDistance?.distanceMeters,
                isLaneOk = laneInfo?.isDriftingLeft == false && laneInfo?.isDriftingRight == false,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
            )

            FloatingActionButton(
                onClick = { showSettings = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Text("⚙", color = Color.White)
            }
        } else {
            PermissionRequest(
                onRequestPermission = { cameraPermissionState.launchPermissionRequest() },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showSettings) {
            SettingsBottomSheet(
                settings = viewModel.settings.collectAsState().value,
                onSettingsUpdate = viewModel::updateSettings,
                onDismiss = { showSettings = false }
            )
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onLaneUpdate: (com.roadguard.app.domain.model.LaneInfo) -> Unit,
    onDistanceUpdate: (com.roadguard.app.domain.model.VehicleDistance) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mlAnalyzer = remember {
        MlDetectionAnalyzer(
            vehicleThreshold = 20f,
            laneSensitivity = 0.5f
        )
    }

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

    DisposableEffect(Unit) {
        onDispose {
            mlAnalyzer.close()
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        modifier = modifier,
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { it.setAnalyzer(ContextCompat.getMainExecutor(context), mlAnalyzer) }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(context))
        }
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
            .size(60.dp)
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
            strokeWidth = 6f
        )
        drawLine(
            color = WarningYellow,
            start = Offset(arrowX, size.height * 0.2f),
            end = Offset(if (isLeft) arrowX + 15f else arrowX - 15f, size.height * 0.4f),
            strokeWidth = 6f
        )
    }
}

@Composable
fun ForwardCollisionWarning(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "⚠️ COLLISION WARNING",
            color = DangerRed,
            style = MaterialTheme.typography.headlineMedium
        )
    }
}

@Composable
fun StatusBar(
    distance: Float?,
    isLaneOk: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("LANE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Text(
                if (isLaneOk) "OK" else "WARNING",
                color = if (isLaneOk) SafeGreen else WarningYellow
            )
        }

        Column {
            Text("DISTANCE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Text(
                distance?.let { "%.1fm".format(it) } ?: "--",
                color = when {
                    distance == null -> Color.Gray
                    distance < 15f -> DangerRed
                    distance < 25f -> WarningYellow
                    else -> SafeGreen
                }
            )
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
