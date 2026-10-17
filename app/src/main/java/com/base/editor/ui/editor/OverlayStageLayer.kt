package com.base.editor.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.base.editor.core.Clip
import com.base.editor.core.ClipTransform
import com.base.editor.core.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Аппаратный Compose-слой наложений (фото и видео поверх основного видео).
 * Для видео используется аппаратный декодер ExoPlayer + TextureView, обеспечивающий
 * плавное воспроизведение 60 FPS и синхронный звук.
 */
@OptIn(UnstableApi::class)
@Composable
fun OverlayStageLayer(
    clips: List<Clip>,
    playheadMs: Long,
    isPlaying: Boolean,
    liveClip: LiveClip?,
    effectiveTransform: (Clip) -> ClipTransform,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    for (clip in clips) {
        val t = if (liveClip != null && liveClip.id == clip.id) liveClip.current else effectiveTransform(clip)
        val isVisible = playheadMs in clip.startMs until clip.endMs

        if (clip.type == MediaType.IMAGE) {
            var bitmap by remember(clip.uri) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(clip.uri) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val src = android.graphics.ImageDecoder.createSource(context.contentResolver, Uri.parse(clip.uri))
                        android.graphics.ImageDecoder.decodeBitmap(src) { dec, _, _ ->
                            dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                    }.getOrNull()?.let { bitmap = it }
                }
            }

            val bmp = bitmap
            if (bmp != null && isVisible) {
                Box(
                    modifier = modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = t.x * size.width
                            translationY = t.y * size.height
                            scaleX = if (t.scale > 0.01f) t.scale else 1f
                            scaleY = if (t.scale > 0.01f) t.scale else 1f
                            rotationZ = t.rotationDeg
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else if (clip.type == MediaType.VIDEO) {
            VideoOverlayItem(
                clip = clip,
                transform = t,
                playheadMs = playheadMs,
                isPlaying = isPlaying,
                isVisible = isVisible,
                modifier = modifier,
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoOverlayItem(
    clip: Clip,
    transform: ClipTransform,
    playheadMs: Long,
    isPlaying: Boolean,
    isVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var videoAspect by remember { mutableFloatStateOf(9f / 16f) }

    val player = remember(clip.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(clip.uri))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val rot = videoSize.unappliedRotationDegrees
                    val w = if (rot % 180 == 90) videoSize.height else videoSize.width
                    val h = if (rot % 180 == 90) videoSize.width else videoSize.height
                    videoAspect = (w.toFloat() * videoSize.pixelWidthHeightRatio) / h
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Громкость и скорость воспроизведения
    LaunchedEffect(clip.volume, isVisible) {
        player.volume = if (isVisible) clip.volume else 0f
    }
    LaunchedEffect(clip.speed) {
        player.playbackParameters = PlaybackParameters(clip.speed)
    }

    // Синхронизация Play / Pause
    LaunchedEffect(isPlaying, isVisible) {
        if (!isVisible) {
            if (player.isPlaying) player.pause()
            return@LaunchedEffect
        }
        val targetPos = (((playheadMs - clip.startMs).coerceAtLeast(0L)) * clip.speed).toLong() + clip.srcInMs
        if (isPlaying) {
            player.seekTo(targetPos)
            player.play()
        } else {
            player.pause()
            player.seekTo(targetPos)
        }
    }

    // Синхронизация Seek / Scrubbing
    LaunchedEffect(playheadMs) {
        if (!isVisible) {
            if (player.isPlaying) player.pause()
            return@LaunchedEffect
        }
        val targetPos = (((playheadMs - clip.startMs).coerceAtLeast(0L)) * clip.speed).toLong() + clip.srcInMs
        if (!isPlaying) {
            player.seekTo(targetPos)
        } else {
            val diff = kotlin.math.abs(player.currentPosition - targetPos)
            if (diff > 350L) {
                player.seekTo(targetPos)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(if (isVisible) 1f else 0f)
            .graphicsLayer {
                translationX = transform.x * size.width
                translationY = transform.y * size.height
                scaleX = if (transform.scale > 0.01f) transform.scale else 1f
                scaleY = if (transform.scale > 0.01f) transform.scale else 1f
                rotationZ = transform.rotationDeg
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentSize(Alignment.Center)
                .aspectRatio(videoAspect.coerceIn(0.1f, 10f)),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also { tv ->
                        player.setVideoTextureView(tv)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
