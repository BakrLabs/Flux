package dev.bakrlabs.flux

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            FluxTheme {
                val scope = rememberCoroutineScope()
                val context = LocalContext.current
                val state = remember { FluxState(context.applicationContext, scope) }
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris -> state.addPicked(uris) }

                BackHandler(enabled = state.screen != Screen.Home) {
                    if (state.screen == Screen.Receive) state.stopReceiving() else state.screen = Screen.Home
                }

                when (state.screen) {
                    Screen.Home -> HomeScreen(state)
                    Screen.Send -> SendScreen(state) { picker.launch(arrayOf("*/*")) }
                    Screen.Receive -> ReceiveScreen(state)
                    Screen.Progress -> ProgressScreen(state)
                }
            }
        }
    }
}
