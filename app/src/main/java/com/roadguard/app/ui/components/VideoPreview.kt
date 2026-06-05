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
    // Holder-Pattern: Job ist mutable, mutableStateOf<Job> triggert keine
    // Recomposition (Job hat keine sinnvolle equals-Implementierung).
    // Wir brauchen den Job nur intern zum Canceln, nicht als State.
    val frameProcessorHolder = remember { object { var job: Job? = null } }

    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            playWhenReady = true
        }
    }

    // Cleanup für ALLES, was mit dem ExoPlayer und dem FrameProcessor zu tun
    // hat. Vorher: DisposableEffect(Unit) — der lief NUR beim ersten
    // Disposal, NICHT bei videoUri-Wechsel. Resultat: alter ExoPlayer + alter
    // FrameProcessor-Job wurden nie freigegeben.
    DisposableEffect(exoPlayer) {
        onDispose {
            frameProcessorHolder.job?.cancel()
            frameProcessorHolder.job = null
            exoPlayer.release()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) {
                    if (frameProcessorHolder.job == null && videoAnalyzer != null) {
                        frameProcessorHolder.job = startFrameProcessing(context, exoPlayer, videoAnalyzer)
                    }
                } else {
                    frameProcessorHolder.job?.cancel()
                    frameProcessorHolder.job = null
                }
            }
        }
        exoPlayer.addListener(listener)

        if (exoPlayer.isPlaying && frameProcessorHolder.job == null && videoAnalyzer != null) {
            frameProcessorHolder.job = startFrameProcessing(context, exoPlayer, videoAnalyzer)
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
    // Eigener Scope mit SupervisorJob + Main-DispatchHandler, damit der Job
    // beim Composable-Disposal sauber cancelled wird (via Dispatchers.Main
    // immediate).
    val scope = CoroutineScope(Dispatchers.Default + Job())
    return scope.launch {
        val retriever = MediaMetadataRetriever()
        try {
            var retrieverInitialized = false
            val frameInterval = 300L
            var lastProcessedPosition = -1L
            var lastFrameTime = 0L

            while (isActive) {
                // EIN withContext-Block pro Iteration statt 3 — spart
                // Context-Switches (vorher: 3 Switches pro Loop-Iteration).
                val (currentPos, isPlayingNow, currentUri) = withContext(Dispatchers.Main) {
                    Triple(
                        exoPlayer.currentPosition,
                        exoPlayer.isPlaying,
                        exoPlayer.currentMediaItem?.localConfiguration?.uri
                    )
                }

                if (isPlayingNow) {
                    if (!retrieverInitialized) {
                        try {
                            if (currentUri != null) {
                                retriever.setDataSource(context, currentUri)
                                retrieverInitialized = true
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
                                // === Bitmap-Lifecycle fix ===
                                // createScaledBitmap gibt das Original zurück
                                // wenn die Maße passen — wir dürfen NICHT das
                                // Original recyclen, weil ExoPlayer es noch
                                // referenziert.
                                val targetW = 640
                                val targetH = 360
                                val needsScale = bitmap.width != targetW || bitmap.height != targetH
                                val scaledBitmap = if (needsScale) {
                                    Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                                } else {
                                    bitmap
                                }
                                if (scaledBitmap !== bitmap) bitmap.recycle()

                                val argbBitmap = scaledBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                scaledBitmap.recycle()
                                if (argbBitmap != null) {
                                    try {
                                        videoAnalyzer.analyzeFrame(argbBitmap, argbBitmap.width, argbBitmap.height)
                                    } finally {
                                        argbBitmap.recycle()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("VideoPreview", "Frame extraction failed", e)
                        }
                    }
                }
                delay(300L)
            }
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                android.util.Log.e("VideoPreview", "Failed to release retriever", e)
            }
            scope.cancel()
        }
    }
}
