package dk.lifelist.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LifeListTheme { App() } }
    }
}

@Composable
fun App() {
    var showingCamera by remember { mutableStateOf(true) }
    var threshold by remember { mutableFloatStateOf(0.70f) }
    // Rotates through the demo cases so all four answers are reachable on device without a
    // model. Remove when stage 6 exports one.
    var caseIndex by remember { mutableIntStateOf(0) }

    if (showingCamera) {
        CameraScreen(onCapture = { showingCamera = false })
    } else {
        ResultScreen(
            answer = answerFor(Demo.cases[caseIndex].probabilities, threshold),
            threshold = threshold,
            onThresholdChange = { threshold = it },
            onRetake = {
                caseIndex = (caseIndex + 1) % Demo.cases.size
                showingCamera = true
            },
        )
    }
}
