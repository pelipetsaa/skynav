package com.skynav.presentation

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.skynav.service.NavigationForegroundService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    MainContent()
                }
            }
        }
    }
}

@Composable
private fun MainContent() {
    val context = LocalContext.current
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* results are checked by the collectors at runtime */ }

    if (!showDiagnostics) {
        MapScreen(onOpenDiagnostics = { showDiagnostics = true })
        return
    }

    DiagnosticScreen(
        onOpenMap = { showDiagnostics = false },
        onGrantPermissions = {
            val list = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list += Manifest.permission.POST_NOTIFICATIONS
            }
            permissions.launch(list.toTypedArray())
        },
        onStartNavigation = {
            context.startForegroundService(Intent(context, NavigationForegroundService::class.java))
        },
        onStopNavigation = {
            context.stopService(Intent(context, NavigationForegroundService::class.java))
        },
    )
}
