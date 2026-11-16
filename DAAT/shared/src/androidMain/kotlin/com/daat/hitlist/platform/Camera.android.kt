package com.daat.hitlist.platform

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.daat.hitlist.ui.components.Crosshair
import java.io.ByteArrayOutputStream

private const val MAX_EDGE_PX = 1080
private const val JPEG_QUALITY = 80

/** Rotates the frame upright, shrinks it to 1080 px on the long edge and encodes a JPEG. */
private fun ImageProxy.toUploadJpeg(): ByteArray {
    val raw = toBitmap()
    val scale = (MAX_EDGE_PX.toFloat() / maxOf(raw.width, raw.height)).coerceAtMost(1f)
    val matrix = Matrix().apply {
        postScale(scale, scale)
        postRotate(imageInfo.rotationDegrees.toFloat())
    }
    val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    val out = ByteArrayOutputStream(256 * 1024)
    upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    if (upright !== raw) upright.recycle()
    raw.recycle()
    return out.toByteArray()
}

@Composable
actual fun SnipeCamera(
    heading: Double,
    onCaptured: (CapturedPhoto) -> Unit,
    onError: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { ContextCompat.getMainExecutor(context) }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val currentHeading by rememberUpdatedState(heading)
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnError by rememberUpdatedState(onError)
    var capturing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).also { view ->
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        try {
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                        } catch (e: Exception) {
                            currentOnError("NO_PERMISSION")
                        }
                    }, executor)
                }
            },
        )

        Crosshair(Modifier.align(Alignment.Center).size(140.dp), color = Color.White.copy(alpha = 0.85f), stroke = 2.dp)

        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.Filled.Close, contentDescription = "Close camera", tint = Color.White)
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .size(84.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (capturing) Color.Gray else Color(0xFFE53935))
                    .clickable(enabled = !capturing, role = Role.Button, onClickLabel = "Take the shot") {
                        capturing = true
                        // Heading and time are read at the shutter press, not when the JPEG is ready.
                        val headingAtShutter = currentHeading
                        val shutterAt = System.currentTimeMillis()
                        imageCapture.takePicture(
                            executor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val jpeg = try {
                                        image.toUploadJpeg()
                                    } catch (e: Exception) {
                                        null
                                    } finally {
                                        image.close()
                                    }
                                    capturing = false
                                    if (jpeg == null) currentOnError("PHOTO_MISSING")
                                    else currentOnCaptured(CapturedPhoto(jpeg, shutterAt, headingAtShutter))
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    capturing = false
                                    currentOnError("PHOTO_MISSING")
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (capturing) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
            }
        }
    }
}
