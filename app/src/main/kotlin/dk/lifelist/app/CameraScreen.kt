package dk.lifelist.app

import android.Manifest
import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import android.graphics.Bitmap
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/**
 * Capture. Deliberately the plainest thing that works.
 *
 * The shutter does not yet produce an identification — stage 6 has exported no model — so it
 * hands back the case the caller asked for. What is real here is the permission flow, the
 * lifecycle binding and the preview surface, which are the parts that break on a real device
 * and cannot be discovered from a screenshot.
 *
 * Not verified on hardware: this container has no camera. Treat every line below as unproven
 * until it has run on the Pixel.
 */
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

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(onCapture: (Bitmap?) -> Unit, modifier: Modifier = Modifier) {
    val permission = rememberPermissionState(Manifest.permission.CAMERA)
    val capture = remember { mutableStateOf<ImageCapture?>(null) }
    val context = LocalContext.current

    // The system photo picker: no permission, no gallery access, the user hands over one
    // image and nothing else. Also the only way to test the model on a winter evening.
    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        onCapture(uri?.let { runCatching { decodeSoftware(context, it) }.getOrNull() })
    }

    Box(modifier.fillMaxSize().background(Ink.Bone)) {
        if (permission.status.isGranted) {
            CameraPreview(Modifier.fillMaxSize(), onBound = { capture.value = it })
        } else {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("CAMERA", style = Type.field)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Life List identifies from a photograph, so it needs the camera. " +
                        "Nothing leaves the device.",
                    style = Type.body,
                )
                Spacer(Modifier.height(22.dp))
                Box(
                    Modifier
                        .border(1.dp, Ink.RuleStrong)
                        .clickable { permission.launchPermissionRequest() }
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text("ALLOW CAMERA", style = Type.field.copy(color = Ink.Rust))
                }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(bottom = 28.dp)
                .size(72.dp)
                .clip(CircleShape)
                .background(Ink.Surface)
                .border(2.dp, Ink.Rust, CircleShape)
                .clickable {
                    val imageCapture = capture.value
                    if (imageCapture == null) {
                        // No camera bound — the demo path, so the shutter still does
                        // something rather than appearing broken.
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
                }
        )

        Text(
            "LIFE LIST",
            style = Type.field.copy(color = Ink.Ink),
            modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(20.dp),
        )

        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .safeDrawingPadding()
                .padding(end = 26.dp, bottom = 48.dp)
                .background(Ink.Surface)
                .border(1.dp, Ink.RuleStrong)
                .clickable {
                    pick.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            Text("FROM GALLERY", style = Type.field.copy(color = Ink.Rust))
        }
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
            // Kept even though nothing reads it yet: binding ImageCapture at preview time is
            // what surfaces "this configuration is not supported on this device", and finding
            // that out at shutter-press is worse.
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
