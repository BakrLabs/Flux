package dev.bakrlabs.flux

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

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

                val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LaunchedEffect(Unit) { state.checkForUpdate() }

                BackHandler(enabled = state.screen != Screen.Home) {
                    if (state.screen == Screen.Receive) state.stopReceiving() else state.screen = Screen.Home
                }

                when (state.screen) {
                    Screen.Home -> HomeScreen(state)
                    Screen.Send -> SendScreen(state) { picker.launch(arrayOf("*/*")) }
                    Screen.Receive -> ReceiveScreen(state)
                    Screen.Progress -> ProgressScreen(state)
                    Screen.History -> HistoryScreen(state)
                }

                state.update?.let { UpdateDialog(state, it) }
                state.pairing?.let { PairingDialog(it) }
            }
        }
    }
}
