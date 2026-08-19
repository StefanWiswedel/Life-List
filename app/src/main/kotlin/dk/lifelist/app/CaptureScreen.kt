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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
 * Capture, as a moment rather than a home screen.
 *
 * The viewfinder used to *be* the app: cold launch straight into a live camera, which is why
 * nothing felt like it belonged to anything. Now you arrive here on purpose, from your list,
 * by pressing one button — and there is an X in the corner that takes you back, which is what
 * makes it read as a thing you opened rather than the place you live.
 *
 * Full-bleed and dark, because a viewfinder framed inside a paper-coloured card is a viewfinder
 * pretending to be a document. When the camera is the whole screen, the phone's own camera
 * vocabulary — reticle, shutter, close — does the explaining.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CaptureScreen(
    onCapture: (Shot?) -> Unit,
    onClose: () -> Unit,
    addingTo: Int,
    modifier: Modifier = Modifier,
) {
    val permission = rememberPermissionState(Manifest.permission.CAMERA)
    val capture = remember { mutableStateOf<ImageCapture?>(null) }
    var firing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val shutterScale by animateFloatAsState(if (firing) 0.86f else 1f, label = "shutter")

    // The system photo picker: no permission, no gallery access, the user hands over one
    // image and nothing else. Also the only way to test the model on a winter evening.
    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            val bitmap = runCatching { decodeSoftware(context, it) }.getOrNull()
            // A photograph chosen from the gallery usually knows where it was taken, and that
            // beats wherever the phone is standing now — which may be a sofa, three days later.
            onCapture(bitmap?.let { b -> Shot(b, Gallery.coordinatesOf(context, it)) })
        }
    }

    fun fire() {
        val imageCapture = capture.value
        firing = true
        if (imageCapture == null) {
            onCapture(null)
            return
        }
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = runCatching { image.toBitmap() }.getOrNull()
                    image.close()
                    firing = false
                    onCapture(bitmap?.let { Shot(it, fromCamera = true) })
                }

                override fun onError(exception: ImageCaptureException) {
                    firing = false
                    onCapture(null)
                }
            },
        )
    }

    Column(modifier.fillMaxSize().background(Color(0xFF100E0C))) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (permission.status.isGranted) {
                CameraPreview(Modifier.fillMaxSize(), onBound = { capture.value = it })
            } else {
                PermissionPanel(onAllow = { permission.launchPermissionRequest() })
            }

            // A frame to aim inside. Not a crop — the model centre-crops to a square, and this
            // is that square, so what you line up is what it actually sees.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxWidth(0.66f)
                        .aspectRatio(1f)
                        .border(1.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(26.dp))
                )
            }

            Row(
                Modifier.fillMaxWidth().safeDrawingPadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onClose,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.36f),
                        contentColor = Color.White,
                    ),
                ) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                Spacer(Modifier.weight(1f))
                Text(
                    if (addingTo > 0) "Photo ${addingTo + 1}" else "Identify",
                    color = Color.White.copy(alpha = 0.92f),
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }

            Text(
                if (addingTo > 0) "Another angle of the same individual"
                else "Fill the frame with one organism",
                color = Color.White.copy(alpha = 0.86f),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 18.dp, start = 24.dp, end = 24.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF100E0C))
                .safeDrawingPadding()
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                onClick = {
                    pick.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.12f),
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = "Choose a photo",
                        tint = Color.White,
                    )
                }
            }

            Shutter(scale = shutterScale, onClick = ::fire)

            Spacer(Modifier.width(52.dp))
        }
    }
}

/** A shutter that looks like every other shutter: a ring with a disc inside it. */
@Composable
private fun Shutter(scale: Float, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(4.dp, Color.White),
        modifier = Modifier.size(76.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(58.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun PermissionPanel(onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Life List identifies from a photograph, so it needs the camera. " +
                "Nothing leaves the device.",
            color = Color(0xFFEDE6DA),
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onAllow,
            colors = ButtonDefaults.buttonColors(containerColor = Warm.Rust, contentColor = Color.White),
        ) { Text("Allow camera") }
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
