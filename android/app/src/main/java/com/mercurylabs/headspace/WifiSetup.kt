package com.mercurylabs.headspace

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "configure my Pi over USB" screen.
 *
 * Lifecycle:
 *   1. Screen opens → look up the Headspace device on USB; request permission.
 *   2. Open ACM serial alongside (don't disturb the UVC stream).
 *   3. User enters SSID + password; tapping SEND fires `WIFI <ssid> <pw>`.
 *   4. Pi handler responds; we surface the response in the log pane.
 *
 * Future: also support `STATUS`, `TAILSCALE`, file upload, etc — same channel.
 */

class WifiSetupViewModel : androidx.lifecycle.ViewModel() {
    data class State(
        val deviceName: String = "(searching for Headspace SPC2…)",
        val connected: Boolean = false,
        val sending: Boolean = false,
        val log: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var serial: AcmSerial? = null
    private var conn: android.hardware.usb.UsbDeviceConnection? = null

    fun connect(ctx: Context, device: UsbDevice) {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!mgr.hasPermission(device)) {
                    appendLog("requesting USB permission…")
                    requestPermission(ctx, device, mgr)
                    return@launch
                }
                val c = mgr.openDevice(device) ?: throw Exception("openDevice failed")
                conn = c
                val s = AcmSerial.open(c, device) ?: run {
                    c.close(); throw Exception("no CDC-ACM interface — device isn't composite UVC+ACM?")
                }
                serial = s
                _state.update { it.copy(deviceName = device.productName ?: "USB Device", connected = true) }
                appendLog("ACM serial opened — ready")
                s.startReader { line ->
                    appendLog("← $line")
                    _state.update { it.copy(sending = false) }
                }
                // Sanity-ping the Pi handler so the user sees response right away.
                s.sendLine("PING")
                appendLog("→ PING")
            } catch (e: Throwable) {
                Log.e("WifiSetupVM", "connect failed", e)
                appendLog("ERR ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun requestPermission(ctx: Context, device: UsbDevice, mgr: UsbManager) {
        val action = "com.mercurylabs.headspace.WIFI_USB_PERMISSION"
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(action).setPackage(ctx.packageName), flags)
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                ctx.unregisterReceiver(this)
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    appendLog("permission granted, opening…")
                    connect(c, device)
                } else {
                    appendLog("ERR USB permission denied")
                }
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ctx.registerReceiver(rcv, filter, Context.RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(rcv, filter)
        mgr.requestPermission(device, pi)
    }

    fun sendWifi(ssid: String, password: String) {
        val s = serial ?: run { appendLog("not connected yet"); return }
        if (ssid.isBlank()) { appendLog("ERR empty SSID"); return }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(sending = true) }
            try {
                // Quote SSID if it contains a space (matches wifi-config.sh parser)
                val ssidArg = if (ssid.contains(' ')) "'$ssid'" else ssid
                val cmd = "WIFI $ssidArg $password"
                appendLog("→ WIFI <ssid> ***")
                s.sendLine(cmd)
            } catch (e: Throwable) {
                appendLog("ERR send: ${e.message}")
                _state.update { it.copy(sending = false) }
            }
        }
    }

    fun sendStatus()    = withSerial { it.sendLine("STATUS"); appendLog("→ STATUS") }
    fun sendTailscale() = withSerial { it.sendLine("TAILSCALE"); appendLog("→ TAILSCALE") }

    private fun withSerial(block: (AcmSerial) -> Unit) {
        val s = serial ?: run { appendLog("not connected yet"); return }
        viewModelScope.launch(Dispatchers.IO) {
            try { block(s) } catch (e: Throwable) { appendLog("ERR ${e.message}") }
        }
    }

    private fun appendLog(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _state.update { it.copy(log = (it.log + "[$ts] $line").takeLast(200)) }
    }

    override fun onCleared() {
        serial?.close()
        try { conn?.close() } catch (_: Throwable) {}
        super.onCleared()
    }

    // viewModelScope is provided by lifecycle-viewmodel-ktx, but to keep
    // the dependency footprint tiny we just inline what we need.
    private val viewModelScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.MainScope()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiSetupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm: WifiSetupViewModel = viewModel()
    val state by vm.state.collectAsState()
    var ssid by rememberSaveable { mutableStateOf("") }
    var pw   by rememberSaveable { mutableStateOf("") }

    // On screen entry: locate the Headspace device + connect.
    LaunchedEffect(Unit) {
        val mgr = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = mgr.deviceList.values.firstOrNull {
            it.productName?.contains("Headspace", ignoreCase = true) == true ||
                (it.vendorId == 0x1d6b && it.productId == 0x0104)
        }
        if (dev != null) vm.connect(ctx, dev)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(tonalElevation = 1.dp) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.connected) "● " else "○ ",
                         color = if (state.connected) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                 else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.deviceName, fontWeight = FontWeight.Medium)
                }
            }

            OutlinedTextField(
                value = ssid, onValueChange = { ssid = it },
                label = { Text("WiFi SSID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pw, onValueChange = { pw = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.sendWifi(ssid, pw) },
                    enabled = state.connected && !state.sending,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.sending) CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Send WiFi")
                }
                OutlinedButton(onClick = vm::sendStatus, enabled = state.connected) { Text("Status") }
                OutlinedButton(onClick = vm::sendTailscale, enabled = state.connected) { Text("Tailscale") }
            }

            HorizontalDivider()

            Text("Log", style = MaterialTheme.typography.labelLarge)
            Surface(
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Column(
                    Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                ) {
                    state.log.forEach { line ->
                        Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
