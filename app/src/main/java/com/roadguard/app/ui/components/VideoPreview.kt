package com.roadguard.app.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.mlkit.vision.common.InputImage
import com.roadguard.app.data.ml.VideoMlAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun VideoPreview(
    videoUri: Uri,
    modifier: Modifier = Modifier,
    videoAnalyzer: VideoMlAnalyzer? = null,
    onLaneUpdate: ((com.roadguard.app.domain.model.LaneInfo) -> Unit)? = null,
    onDistanceUpdate: ((com.roadguard.app.domain.model.VehicleDistance) -> Unit)? = null
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var frameProcessor by remember { mutableStateOf<Job?>(null) }
    var retriever by remember { mutableStateOf<MediaMetadataRetriever?>(null) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        retriever = MediaMetadataRetriever()
        try {
            retriever?.setDataSource(context, videoUri)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        onDispose {
            frameProcessor?.cancel()
            retriever?.release()
            exoPlayer.release()
            videoAnalyzer?.close()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        IconButton(
            onClick = {
                isPlaying = !isPlaying
                if (isPlaying) {
                    exoPlayer.play()
                    frameProcessor?.cancel()
                    frameProcessor = null
                } else {
                    exoPlayer.pause()
                    if (frameProcessor == null && retriever != null && videoAnalyzer != null) {
                        frameProcessor = startFrameProcessing(
                            retriever!!,
                            videoAnalyzer!!,
                            onLaneUpdate,
                            onDistanceUpdate
                        )
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White
            )
        }
    }
}

private fun startFrameProcessing(
    retriever: MediaMetadataRetriever,
    videoAnalyzer: VideoMlAnalyzer,
    onLaneUpdate: ((com.roadguard.app.domain.model.LaneInfo) -> Unit)?,
    onDistanceUpdate: ((com.roadguard.app.domain.model.VehicleDistance) -> Unit)?
): Job {
    return CoroutineScope(Dispatchers.Main).launch {
        var frameTime = 0L
        val frameInterval = 200L

        while (isActive) {
            try {
                val bitmap = retriever.getFrameAtTime(
                    frameTime * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                bitmap?.let {
                    val scaledBitmap = Bitmap.createScaledBitmap(it, 640, 360, true)
                    videoAnalyzer.analyzeFrame(scaledBitmap, scaledBitmap.width, scaledBitmap.height)
                    scaledBitmap.recycle()
                    it.recycle()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            frameTime += frameInterval
            delay(frameInterval)
        }
    }
}
