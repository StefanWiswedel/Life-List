package dk.lifelist.app

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
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
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(onCapture: () -> Unit, modifier: Modifier = Modifier) {
    val permission = rememberPermissionState(Manifest.permission.CAMERA)

    Box(modifier.fillMaxSize().background(Ink.Bone)) {
        if (permission.status.isGranted) {
            CameraPreview(Modifier.fillMaxSize())
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
                .clickable(onClick = onCapture)
        )

        Text(
            "LIFE LIST",
            style = Type.field.copy(color = Ink.Ink),
            modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(20.dp),
        )
    }
}

@Composable
private fun CameraPreview(modifier: Modifier = Modifier) {
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
            }
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
