package com.skynav.presentation

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skynav.BuildConfig
import com.skynav.data.session.NavigationHub
import com.skynav.domain.geo.LatLon
import com.skynav.domain.inertial.label
import com.skynav.service.NavigationForegroundService
import kotlin.math.PI

@Composable
fun MapScreen(
    onOpenDiagnostics: () -> Unit,
    vm: MapViewModel = viewModel(),
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsState()
    val fix by NavigationHubFixes()
    var query by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(Unit) {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.READ_PHONE_STATE)
            if (BuildConfig.MAPILLARY_TOKEN.isNotBlank()) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
        context.startForegroundService(Intent(context, NavigationForegroundService::class.java))
    }

    val gpsOn by NavigationHub.gpsEnabled.collectAsState()
    val snapshot = fix?.snapshot
    val guide = fix?.guide
    val moving = (guide?.speedMps ?: 0.0) > 0.5
    val view = LocalView.current
    DisposableEffect(moving) {
        val window = (view.context as? Activity)?.window
        if (moving) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val position = guide?.let { LatLon(it.latitude, it.longitude) }
        ?: snapshot?.let { LatLon(it.position.latitude, it.position.longitude) }
    val accuracyM = guide?.accuracyM ?: snapshot?.position?.accuracyM
    val headingRad = guide?.headingDeg?.let { (PI / 2.0) - Math.toRadians(it) } ?: snapshot?.headingRad
    val headingSigma = if (guide?.headingDeg != null) 0.2 else snapshot?.headingSigmaRad

    StreetCamera(enabled = BuildConfig.MAPILLARY_TOKEN.isNotBlank() && cameraGranted)

    Box(modifier = Modifier.fillMaxSize()) {
        OsmMap(
            position = position,
            accuracyM = accuracyM,
            headingRad = headingRad,
            headingSigmaRad = headingSigma,
            route = ui.route?.points.orEmpty(),
            follow = follow,
            onGesture = { follow = false },
            onLongPress = { NavigationHub.requestManual(it) },
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Куда ехать") },
                )
                Button(onClick = { vm.search(query) }, enabled = !ui.searching) { Text("Найти") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                TextButton(onClick = onOpenDiagnostics) { Text("Датчики") }
                TextButton(onClick = NavigationHub::requestReverse) { Text("Разворот") }
                TextButton(onClick = NavigationHub::requestPauseToggle) {
                    Text(if (guide?.paused == true) "Продолжить" else "Пауза")
                }
                TextButton(onClick = { NavigationHub.setGpsEnabled(!gpsOn) }) {
                    Text(if (gpsOn) "GPS выкл" else "GPS вкл")
                }
                if (!follow) TextButton(onClick = { follow = true }) { Text("Следовать") }
                if (ui.route != null) TextButton(onClick = vm::clearRoute) { Text("Сбросить") }
            }
            if (ui.places.isNotEmpty()) {
                Surface {
                    LazyColumn(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        items(ui.places, key = { "${it.latitude},${it.longitude},${it.name}" }) { place ->
                            TextButton(onClick = { vm.select(place, position) }) {
                                Text(place.name, maxLines = 2)
                            }
                        }
                    }
                }
            }
            val banner = when {
                guide?.needsMark == true -> "Отметьте положение на дороге"
                ui.guidance.isNotEmpty() -> ui.guidance
                position == null -> "Ждём оценку по сотам и Wi-Fi"
                ui.status.isNotEmpty() -> ui.status
                else -> ""
            }
            val source = guide?.source?.label()
            val detail = buildString {
                if (source != null) append("$source. ")
                if (!gpsOn) append("GPS выключен. ")
                if (accuracyM != null) append("Точность ${accuracyM.toInt()} м. ")
                if (guide?.speedLost == true) append("Нет скорости с телефона. ")
                if (guide?.streetPhoto == true) append("Снимок улицы. ")
                append("© OpenStreetMap")
                if (BuildConfig.MAPILLARY_TOKEN.isNotBlank()) append(" © Mapillary, CC BY-SA")
            }
            Surface(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (banner.isNotEmpty()) Text(banner)
                    Text(detail)
                }
            }
        }
    }
}

@Composable
private fun NavigationHubFixes() = com.skynav.data.session.NavigationHub.fixes.collectAsState()
