package com.roadguard.app.ui.components

import android.content.Context
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
import com.roadguard.app.data.ml.VideoMlAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VideoPreview(
    videoUri: Uri,
    modifier: Modifier = Modifier,
    videoAnalyzer: VideoMlAnalyzer? = null
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(true) }
    var frameProcessor by remember { mutableStateOf<Job?>(null) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            frameProcessor?.cancel()
            exoPlayer.release()
            videoAnalyzer?.close()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    if (frameProcessor == null && videoAnalyzer != null) {
                        frameProcessor = startFrameProcessing(context, exoPlayer, videoAnalyzer)
                    }
                } else {
                    frameProcessor?.cancel()
                    frameProcessor = null
                }
            }
        }
        exoPlayer.addListener(listener)

        if (exoPlayer.isPlaying && frameProcessor == null && videoAnalyzer != null) {
            frameProcessor = startFrameProcessing(context, exoPlayer, videoAnalyzer)
        }

        onDispose {
            exoPlayer.removeListener(listener)
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
                if (isPlaying) {
                    exoPlayer.pause()
                } else {
                    exoPlayer.play()
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
    context: Context,
    exoPlayer: ExoPlayer,
    videoAnalyzer: VideoMlAnalyzer
): Job {
    return CoroutineScope(Dispatchers.Default).launch {
        val retriever = MediaMetadataRetriever()
        var retrieverInitialized = false
        val frameInterval = 300L
        var lastProcessedPosition = -1L
        var lastFrameTime = 0L

        while (isActive) {
            val currentPos = withContext(Dispatchers.Main) { exoPlayer.currentPosition }
            val isPlaying = withContext(Dispatchers.Main) { exoPlayer.isPlaying }

            if (isPlaying) {
                if (!retrieverInitialized) {
                    try {
                        val uri = withContext(Dispatchers.Main) { exoPlayer.currentMediaItem?.localConfiguration?.uri }
                        if (uri != null) {
                            retriever.setDataSource(context, uri)
                            retrieverInitialized = true
                            android.util.Log.d("VideoPreview", "Retriever initialized with URI: $uri")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VideoPreview", "Failed to init retriever", e)
                    }
                }

                val currentTime = System.currentTimeMillis()
                if (retrieverInitialized && currentPos != lastProcessedPosition && currentTime - lastFrameTime > frameInterval) {
                    lastProcessedPosition = currentPos
                    lastFrameTime = currentTime

                    try {
                        val bitmap = retriever.getFrameAtTime(
                            currentPos * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                        if (bitmap != null) {
                            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 640, 360, true)
                            bitmap.recycle()
                            val argbBitmap = scaledBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            scaledBitmap.recycle()
                            videoAnalyzer.analyzeFrame(argbBitmap, argbBitmap.width, argbBitmap.height)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VideoPreview", "Frame extraction failed", e)
                    }
                }
            }
            delay(100L)
        }

        try {
            retriever.release()
        } catch (e: Exception) {
            android.util.Log.e("VideoPreview", "Failed to release retriever", e)
        }
    }
}
