package dk.lifelist.app

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/**
 * Decode a gallery image to a *software* bitmap.
 *
 * `ImageDecoder` hands back a hardware bitmap by default, and `getPixels` throws on those —
 * the pixels live on the GPU. The identifier reads pixels, so it must be told otherwise.
 */
fun decodeSoftware(context: Context, uri: Uri): Bitmap {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
    }
}

/**
 * The Identify tab, before there is anything to identify.
 *
 * The old version launched into a raw full-bleed viewfinder with two hand-drawn boxes floating
 * on it, which is what "opens with just a camera" meant. The viewfinder is now framed — it sits
 * in a card, inside the app's own chrome, with a line of text saying what to do and a shutter
 * that looks like a shutter. Same camera, but the app is visibly around it.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CaptureScreen(
    onCapture: (Bitmap?) -> Unit,
    note: String,
    ready: Boolean,
    modifier: Modifier = Modifier,
) {
    val permission = rememberPermissionState(Manifest.permission.CAMERA)
    val capture = remember { mutableStateOf<ImageCapture?>(null) }
    val context = LocalContext.current

    // The system photo picker: no permission, no gallery access, the user hands over one
    // image and nothing else. Also the only way to test the model on a winter evening.
    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { onCapture(runCatching { decodeSoftware(context, it) }.getOrNull()) }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            Modifier.fillMaxWidth().weight(1f),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1916)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (permission.status.isGranted) {
                    CameraPreview(
                        Modifier.fillMaxSize().clip(MaterialTheme.shapes.extraLarge),
                        onBound = { capture.value = it },
                    )
                } else {
                    PermissionPanel(onAllow = { permission.launchPermissionRequest() })
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            if (ready) "Fill the frame with one organism, and hold still."
            else "Getting the model ready — this takes a moment the first time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilledTonalButton(
                onClick = {
                    pick.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(
                    Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Gallery")
            }

            Shutter(
                onClick = {
                    val imageCapture = capture.value
                    if (imageCapture == null) {
                        onCapture(null)
                    } else {
                        imageCapture.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap = runCatching { image.toBitmap() }.getOrNull()
                                    image.close()
                                    onCapture(bitmap)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    onCapture(null)
                                }
                            },
                        )
                    }
                },
            )

            // Balances the shutter so it sits centred, and carries the one line of
            // provenance that used to be buried at the bottom of the result screen.
            Box(Modifier.width(96.dp)) {
                FieldLabel(note.take(28), Modifier.fillMaxWidth())
            }
        }
    }
}

/** A shutter that looks like every other shutter: a ring with a disc inside it. */
@Composable
private fun Shutter(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier.size(76.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            )
            Box(
                Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun PermissionPanel(onAllow: () -> Unit) {
    Column(
        Modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Life List identifies from a photograph, so it needs the camera. " +
                "Nothing leaves the device.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFEDE6DA),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAllow) { Text("Allow camera") }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier,
    onBound: (ImageCapture) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                )
                onBound(capture)
            }
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
