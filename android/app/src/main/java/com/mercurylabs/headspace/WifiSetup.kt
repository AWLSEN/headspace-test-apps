package com.mercurylabs.headspace

import android.content.Context
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

/**
 * "Configure my Pi over USB" screen.
 *
 * IMPORTANT: This screen does NOT open its own UsbDeviceConnection. The
 * Pi's first CDC-ACM interface (ttyGS0) carries BOTH the IMU stream and
 * wifi-config replies. RecordingService already owns that interface; if
 * we opened a second connection and called claimInterface(force=true),
 * the kernel would yank the endpoint from RecordingService and the IMU
 * file would be silently empty for the rest of the session.
 *
 * Instead we send via RecorderState.acmSender (RecordingService's live
 * AcmSerial) and observe RecorderState.acmResponses (every non-"I:" line
 * from the same reader thread).
 */

class WifiSetupViewModel : androidx.lifecycle.ViewModel() {
    data class State(
        val connected: Boolean = false,
        val sending: Boolean = false,
        val log: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val viewModelScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.MainScope()

    init {
        // Pump RecordingService's response flow into our log.
        viewModelScope.launch {
            RecorderState.acmResponses.collect { line ->
                appendLog("← $line")
                _state.update { it.copy(sending = false) }
            }
        }
        // Mirror connectivity from the recorder state.
        viewModelScope.launch {
            RecorderState.state.collect { snap ->
                val ready = snap.phase != RecorderState.Phase.IDLE && RecorderState.acmSender != null
                if (ready != _state.value.connected) {
                    _state.update { it.copy(connected = ready) }
                }
            }
        }
    }

    /** Sanity ping when the screen opens, if the channel is up. */
    fun pingIfReady() {
        val send = RecorderState.acmSender
        if (send == null) {
            appendLog("waiting for device — plug in the Headspace SPC2 or open the camera screen first")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try { send("PING"); appendLog("→ PING") }
            catch (e: Throwable) { appendLog("ERR ${e.message}") }
        }
    }

    fun sendWifi(ssid: String, password: String) {
        val send = RecorderState.acmSender ?: run { appendLog("not connected yet"); return }
        if (ssid.isBlank()) { appendLog("ERR empty SSID"); return }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(sending = true) }
            try {
                val ssidArg = if (ssid.contains(' ')) "'$ssid'" else ssid
                send("WIFI $ssidArg $password")
                appendLog("→ WIFI <ssid> ***")
            } catch (e: Throwable) {
                appendLog("ERR send: ${e.message}")
                _state.update { it.copy(sending = false) }
            }
        }
    }

    fun sendStatus()    = withSerial { it("STATUS");    appendLog("→ STATUS") }
    fun sendTailscale() = withSerial { it("TAILSCALE"); appendLog("→ TAILSCALE") }

    private fun withSerial(block: ((String) -> Unit) -> Unit) {
        val send = RecorderState.acmSender ?: run { appendLog("not connected yet"); return }
        viewModelScope.launch(Dispatchers.IO) {
            try { block(send) } catch (e: Throwable) { appendLog("ERR ${e.message}") }
        }
    }

    private fun appendLog(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _state.update { it.copy(log = (it.log + "[$ts] $line").takeLast(200)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiSetupScreen(onBack: () -> Unit) {
    val vm: WifiSetupViewModel = viewModel()
    val state by vm.state.collectAsState()
    val recorder by RecorderState.state.collectAsState()
    var ssid by rememberSaveable { mutableStateOf("") }
    var pw   by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.pingIfReady() }

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
                    val name = if (recorder.deviceName.isNotEmpty()) recorder.deviceName
                               else "(waiting for Headspace SPC2…)"
                    Text(name, fontWeight = FontWeight.Medium)
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
