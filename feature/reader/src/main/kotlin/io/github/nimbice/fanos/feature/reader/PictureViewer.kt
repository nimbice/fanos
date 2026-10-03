package io.github.nimbice.fanos.feature.reader

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility

/** A picture from a chapter, to see full screen: what the page loaded it from, and its description. */
internal data class Picture(val model: Any, val alt: String?)

/**
 * A chapter's picture on black, the whole screen: pinch or double-tap to zoom, and drag about once zoomed; at its own
 * size a swipe up or down puts it away, as × and the back gesture do. Share sends it to another app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PictureViewer(picture: Picture, onClose: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // A swipe at the picture's own size pulls it away: how far it's gone.
    val pulled = remember { Animatable(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    val fade = (abs(pulled.value) / height * 2f).coerceIn(0f, 0.7f)
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - fade))) {
        AsyncImage(
            model = picture.model,
            contentDescription = picture.alt,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { at ->
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    offset = within((center - at) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM, size)
                                    scale = DOUBLE_TAP_ZOOM
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        height = size.height.toFloat().coerceAtLeast(1f)
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var pulling = false
                            do {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if ((scale > 1f || zoom != 1f) && !pulling && centroid != Offset.Unspecified) {
                                    // Zoomed: the point under the fingers stays under them.
                                    val now = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    offset = within(offset + (centroid - center - offset) * (1f - now / scale) + pan, now, size)
                                    scale = now
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                } else if (event.changes.size == 1 && (pulling || abs(pan.y) > abs(pan.x))) {
                                    pulling = true
                                    scope.launch { pulled.snapTo(pulled.value + pan.y) }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (pulling) {
                                if (abs(pulled.value) > size.height * PULL_TO_CLOSE) onClose() else scope.launch { pulled.animateTo(0f) }
                            }
                            if (scale <= 1f) offset = Offset.Zero
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y + pulled.value
                    },
        )
        // Its description, when it has one that describes it (not a file's name).
        picture.alt?.trim()?.takeIf { it.length > 2 && !FILE_NAME.matches(it) && it.lowercase() !in BARE_WORDS }?.let { alt ->
            Text(
                alt,
                color = Color.White.copy(alpha = 0.85f * (1f - fade / 0.7f).coerceIn(0f, 1f)),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                        .padding(horizontal = 24.dp, vertical = 20.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RoundButton(Icons.Filled.Close, "Close", onClose)
            RoundButton(Icons.Filled.Share, "Share") { scope.launch { sharePicture(context, picture.model) } }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.12f), contentColor = Color.White) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = description) }
    }
}

/** [offset] kept so the picture, at [scale], still covers the middle of the screen: no dragging it off. */
private fun within(offset: Offset, scale: Float, size: IntSize): Offset {
    val x = size.width * (scale - 1f) / 2f
    val y = size.height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
}

/**
 * Sends [model]'s picture to another app: loaded as the page loads it, and kept in the cache meanwhile (the one sent
 * before goes then).
 */
internal suspend fun sharePicture(context: Context, model: Any) {
    val request = ImageRequest.Builder(context).data(model).allowHardware(false).build()
    val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    if (bitmap == null) {
        Toast.makeText(context, "Couldn't load the picture", Toast.LENGTH_SHORT).show()
        return
    }
    sendPicture(context, bitmap)
}

/** Sends [bitmap] to another app, through a file in the cache that's given to it to read. */
internal suspend fun sendPicture(context: Context, bitmap: Bitmap, text: String? = null) {
    val file =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, SHARED).apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val png = bitmap.hasAlpha()
            File(dir, if (png) "picture.png" else "picture.jpg").also { out ->
                out.outputStream().use { bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            }
        }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.shared", file)
    val send =
        Intent(Intent.ACTION_SEND)
            .setType(if (file.extension == "png") "image/png" else "image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    text?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, null))
}

/** A picture's description that's only its file's name. */
private val FILE_NAME = Regex("""(?i).*\.(jpe?g|png|gif|webp|avif|bmp|svg)""")

/** Descriptions that describe nothing. */
private val BARE_WORDS = setOf("image", "picture", "img", "photo", "illustration")

/** The cache folder pictures are shared from: the file provider's shared_pictures paths name it. */
private const val SHARED = "shared"

private const val JPEG_QUALITY = 95
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** How far a swipe pulls the picture, as a share of the screen's height, to put it away. */
private const val PULL_TO_CLOSE = 0.15f
