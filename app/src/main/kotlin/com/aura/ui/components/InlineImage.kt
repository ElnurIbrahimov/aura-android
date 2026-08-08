package com.aura.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import android.widget.Toast

private val sharedImageClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

/**
 * Load a bitmap from a URL on a background thread.
 * Returns null on failure. Uses a shared OkHttpClient instance.
 */
private suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        // Not every generated image arrives as an http(s) URL. The OpenAI
        // images schema also allows inline base64, which ImageGenTool decodes
        // to a cache file and hands back as a file:// URI — OkHttp rejects
        // that scheme outright, so those images silently failed to render
        // (a spinner, then an empty box, with the bitmap null).
        if (url.startsWith("file://") || url.startsWith("/")) {
            val path = if (url.startsWith("file://")) android.net.Uri.parse(url).path else url
            return@runCatching path?.let { BitmapFactory.decodeFile(it) }
        }
        val request = Request.Builder().url(url).build()
        val response = sharedImageClient.newCall(request).execute()
        if (!response.isSuccessful) return@withContext null
        val bytes = response.body?.bytes() ?: return@withContext null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.onFailure { Log.w("InlineImage", "image load failed for $url: ${it.message}", it) }.getOrNull()
}

/**
 * How much taller than it is wide an image may render inline.
 *
 * Inline images used to be a fixed 200dp box with [ContentScale.Crop], so a
 * square 1024×1024 generation lost its top and bottom to the frame — you could
 * not see what you had asked for without opening it. Sizing to the bitmap's own
 * ratio shows the whole thing; the cap stops a tall panorama from swallowing
 * the scroll position.
 */
private const val MAX_INLINE_ASPECT = 0.75f

/**
 * Inline image rendered in chat. Sized to the image's own aspect ratio, and
 * tappable to open a full-screen viewer with zoom, save and share.
 */
@Composable
fun InlineImage(
    url: String,
    modifier: Modifier = Modifier,
) {
    val colors = AuraThemeTokens.colors
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var fullScreen by remember { mutableStateOf(false) }

    LaunchedEffect(url) {
        loading = true
        bitmap = loadBitmap(url)
        loading = false
    }

    val loaded = bitmap
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (loaded != null && loaded.height > 0) {
                    Modifier.aspectRatio(
                        (loaded.width.toFloat() / loaded.height).coerceAtLeast(MAX_INLINE_ASPECT),
                    )
                } else {
                    Modifier.height(200.dp)
                },
            )
            .padding(vertical = AuraSpacing.xxs)
            .clip(RoundedCornerShape(AuraSpacing.sm))
            .background(colors.surface2)
            .clickable(enabled = loaded != null) { fullScreen = true },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = colors.actionPrimary,
                strokeWidth = AuraSpacing.tiny,
                modifier = Modifier.padding(AuraSpacing.md),
            )
        } else if (loaded != null) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = "Generated image",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }

    if (fullScreen && loaded != null) {
        ImageViewer(bitmap = loaded, url = url, onDismiss = { fullScreen = false })
    }
}

/**
 * Full-screen viewer: pinch to zoom, save to the gallery, share.
 *
 * This used to be a plain `Box(Modifier.fillMaxSize())` emitted inside the
 * message bubble's column, so "full screen" meant "as much room as the bubble
 * had" — it opened *under* the message rather than over the app. And the zoom
 * gesture wrote `scale` and `offset` that nothing ever read, so pinching moved
 * numbers and not the picture. A [Dialog] gets the window; [graphicsLayer]
 * makes the gesture mean something.
 */
@Composable
private fun ImageViewer(
    bitmap: Bitmap,
    url: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    fun save() = scope.launch {
        val saved = saveImageToGallery(context, bitmap, url)
        Toast.makeText(
            context,
            if (saved) "Saved to Pictures/Aura" else "Couldn't save the image",
            Toast.LENGTH_SHORT,
        ).show()
    }

    // Writing to the gallery needs the legacy storage permission below API 29;
    // from Q onwards MediaStore grants it by owning the row it just inserted.
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            save()
        } else {
            Toast.makeText(context, "Saving needs storage permission", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Generated image (full screen)",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += pan.x
                                offsetY += pan.y
                            } else {
                                // At 1× the image already fits, so a pan would
                                // only strand it off-centre with no way back.
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offsetX = 0f; offsetY = 0f },
                            onTap = { if (scale == 1f) onDismiss() },
                        )
                    },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AuraSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewerButton(Icons.Filled.Close, "Close", onDismiss)
                Box(Modifier.weight(1f))
                ViewerButton(Icons.Filled.Download, "Save to gallery") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        save()
                    } else {
                        storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
                ViewerButton(Icons.Filled.Share, "Share") {
                    scope.launch {
                        if (!shareImage(context, bitmap, url)) {
                            Toast.makeText(context, "Couldn't share the image", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}

/** A round translucent button that stays legible over any part of an image. */
@Composable
private fun ViewerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White),
    ) {
        Icon(icon, contentDescription = label)
    }
}
