package com.mercurylabs.headspace

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var lines by remember { mutableStateOf(CrashLog.read(ctx, 500)) }
    var devices by remember { mutableStateOf(snapshotDevices(ctx)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        lines = CrashLog.read(ctx, 500)
                        devices = snapshotDevices(ctx)
                    }) { Text("Refresh") }
                    TextButton(onClick = {
                        CrashLog.clear(ctx); lines = emptyList()
                    }) { Text("Clear") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("USB devices currently visible to this app", style = MaterialTheme.typography.titleSmall)
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    if (devices.isEmpty()) Text("(none)")
                    devices.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                }
            }
            HorizontalDivider()
            Text("Log (newest at the bottom)", style = MaterialTheme.typography.titleSmall)
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                    if (lines.isEmpty()) Text("(empty — plug in the camera and refresh)")
                    lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }
        }
    }
}

private fun snapshotDevices(ctx: Context): List<String> {
    val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    val out = mutableListOf<String>()
    for ((_, d) in mgr.deviceList) {
        out.add("vid=0x%04x pid=0x%04x  '%s'  ifaces=%d  cls=%d"
            .format(d.vendorId, d.productId, d.productName ?: "?",
                    d.interfaceCount, d.deviceClass))
        for (i in 0 until d.interfaceCount) {
            val it = d.getInterface(i)
            out.add("    iface[$i] id=${it.id} alt=${it.alternateSetting} cls=${it.interfaceClass} sub=${it.interfaceSubclass} eps=${it.endpointCount}")
        }
    }
    return out
}
