package com.skynav.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.skynav.domain.model.CellMeasurement
import com.skynav.domain.wifi.WifiNetwork

@Composable
fun DiagnosticScreen(
    onOpenMap: () -> Unit,
    onGrantPermissions: () -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    vm: DiagnosticViewModel = viewModel(),
) {
    val scan by vm.scan.collectAsState()
    val lookup by vm.lookup.collectAsState()
    val cells = scan.cells

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("SkyNav diagnostics", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Button(onClick = onOpenMap) { Text("Map") }
            Button(onClick = onGrantPermissions) { Text("Permissions") }
            Button(onClick = onStartNavigation) { Text("Start") }
            Button(onClick = onStopNavigation) { Text("Stop") }
        }
        Text("Cells: ${cells.size}, with identity: ${cells.count { it.key != null }}")
        Text(lookup.tower, style = MaterialTheme.typography.bodyMedium)
        Text(lookup.wifiNote, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(lookup.wifi, key = { it.bssid }) { WifiRow(it) }
            items(cells) { CellRow(it, vm.ageMs(it)) }
        }
    }
}

@Composable
private fun WifiRow(network: WifiNetwork) {
    val place = if (network.latitude != null && network.longitude != null) {
        "${network.latitude}, ${network.longitude}"
    } else {
        "no catalog hit"
    }
    val name = network.ssid.ifBlank { "(hidden)" }
    Column {
        Text("$name  ${network.bssid}")
        Text(
            "RSSI=${network.rssiDbm} dBm  ${network.frequencyMhz} MHz  $place",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CellRow(m: CellMeasurement, ageMs: Long) {
    val k = m.key
    val header = if (k != null) {
        "${k.technology} ${k.mcc}/${k.mnc} CI=${k.cellId}"
    } else {
        "identity unavailable"
    }
    Column {
        Text(header + if (m.registered) " [serving]" else "")
        val ta = when (val value = m.timingAdvance) {
            null -> "-"
            0 -> "0 ignored"
            else -> value.toString()
        }
        Text(
            "TAC=${m.tac ?: "-"} PCI=${m.pci ?: "-"} RSRP=${m.rsrpDbm ?: "-"} " +
                "RSRQ=${m.rsrqDb ?: "-"} SINR=${m.sinrDb ?: "-"} TA=$ta age=${ageMs}ms",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
