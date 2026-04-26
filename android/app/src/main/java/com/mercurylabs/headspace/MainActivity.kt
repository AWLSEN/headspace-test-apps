package com.mercurylabs.headspace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignored */ }

    /** Devices we've already fired the recording service for. Keyed by
     *  device.deviceId (kernel-assigned per-attach, changes on replug).
     *  Cleared when the device detaches via the broadcast in
     *  RecordingService — but we also drop entries whose device disappears
     *  from the live list, so a quick unplug-replug recovers cleanly. */
    private val handledDeviceIds = mutableSetOf<Int>()
    private var pollJob: Job? = null

    // Android 14+ requires CAMERA runtime grant before we can start a
    // foreground service of type "camera". Without it, startForeground
    // throws SecurityException and the service crashes on creation.
    private var pendingDevice: UsbDevice? = null
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            CrashLog.line(this, "perm", "CAMERA granted=$granted")
            val d = pendingDevice
            pendingDevice = null
            if (granted && d != null) startRecordingFor(d)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.init(this)
        CrashLog.line(this, "MainActivity", "onCreate intent=${intent.action}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleUsbAttach(intent)

        setContent {
            var screen by remember { mutableStateOf("home") }
            when (screen) {
                "home" -> App(
                    onOpenRecording = { openRecording(it) },
                    onOpenWifiSetup = { screen = "wifi" },
                    onOpenDiagnostics = { screen = "diag" },
                )
                "wifi" -> WifiSetupScreen(onBack = { screen = "home" })
                "diag" -> DiagnosticsScreen(onBack = { screen = "home" })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbAttach(intent)
    }

    override fun onResume() {
        super.onResume()
        // 1. One-shot: catch the case where the device was ALREADY plugged
        //    in when the app launched (the system only fires
        //    USB_DEVICE_ATTACHED on the *transition*, not for already-
        //    attached devices). Without this, plugging the phone into the
        //    Pi while the Pi is still booting leaves the app sitting at
        //    the empty state forever, even after the Pi enumerates.
        // 2. Continuous poll: covers the boot-race scenario — phone
        //    plugged in BEFORE Pi finishes booting; we'll catch the
        //    enumeration the moment it completes.
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (true) {
                checkForAttachedDevice()
                delay(2_000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel(); pollJob = null
    }

    private fun checkForAttachedDevice() {
        val mgr = getSystemService(USB_SERVICE) as UsbManager
        val live = mgr.deviceList.values
        // Drop any handled-IDs that are no longer present, so a plug→unplug→
        // replug cycle wakes the service again on the new attach.
        val liveIds = live.map { it.deviceId }.toSet()
        handledDeviceIds.retainAll(liveIds)
        // Pick the first matching Headspace device we haven't already handed off.
        val candidate = live.firstOrNull { d ->
            d.deviceId !in handledDeviceIds && (
                (d.vendorId == 0x1d6b && d.productId == 0x0104) ||
                (d.productName?.contains("Headspace", ignoreCase = true) == true)
            )
        } ?: return
        CrashLog.line(this, "USB",
            "poll: found attached device id=${candidate.deviceId} " +
            "vid=0x%04x pid=0x%04x — handing to service".format(
                candidate.vendorId, candidate.productId))
        handledDeviceIds.add(candidate.deviceId)
        try { RecordingService.start(this, candidate) }
        catch (e: Throwable) { CrashLog.writeException(this, "service.start (poll)", e) }
    }

    private fun handleUsbAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            CrashLog.line(this, "USB", "handleUsbAttach: action=${intent?.action} (ignored)")
            return
        }
        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        if (device == null) {
            CrashLog.line(this, "USB", "USB_DEVICE_ATTACHED with null device extra"); return
        }
        CrashLog.line(this, "USB",
            "attached: vid=${"0x%04x".format(device.vendorId)} " +
            "pid=${"0x%04x".format(device.productId)} " +
            "name='${device.productName}' ifaces=${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val it = device.getInterface(i)
            CrashLog.line(this, "USB",
                "  iface[$i] id=${it.id} alt=${it.alternateSetting} " +
                "class=${it.interfaceClass} sub=${it.interfaceSubclass} " +
                "endpoints=${it.endpointCount}")
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            CrashLog.line(this, "perm", "CAMERA not granted — requesting before service start")
            pendingDevice = device
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        startRecordingFor(device)
    }

    private fun startRecordingFor(device: UsbDevice) {
        if (device.deviceId in handledDeviceIds) {
            CrashLog.line(this, "USB", "attach intent for already-handled device id=${device.deviceId}, ignoring")
            return
        }
        handledDeviceIds.add(device.deviceId)
        try {
            RecordingService.start(this, device)
        } catch (e: Throwable) {
            CrashLog.writeException(this, "RecordingService.start failed", e)
        }
    }

    private fun openRecording(rec: Recording) {
        // For now: launch the system file viewer pointed at the folder.
        // (Future: an in-app preview that plays video.h264 + scrolls IMU.)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.fromFile(rec.dir), "resource/folder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (_: Exception) { /* no folder app */ }
    }
}

@Composable
private fun App(
    onOpenRecording: (Recording) -> Unit,
    onOpenWifiSetup: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val ctx = LocalContext.current
    val rec by RecorderState.state.collectAsState()
    var recordings by remember { mutableStateOf(Recordings.list(ctx)) }
    // Refresh the saved-list whenever recording phase changes back to idle/ready.
    LaunchedEffect(rec.phase) {
        if (rec.phase != RecorderState.Phase.RECORDING) {
            recordings = Recordings.list(ctx)
        }
    }

    MaterialTheme {
        @OptIn(ExperimentalMaterial3Api::class)
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Headspace Recorder", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = onOpenDiagnostics) {
                            Icon(Icons.Filled.BugReport, contentDescription = "Diagnostics")
                        }
                        IconButton(onClick = onOpenWifiSetup) {
                            Icon(Icons.Filled.Wifi, contentDescription = "WiFi setup")
                        }
                    },
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                BigButtonPanel(
                    state = rec,
                    onStart = { RecordingService.startRecording(ctx) },
                    onStop  = { RecordingService.stopRecording(ctx)  },
                )
                rec.lastError?.let { err ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Text("⚠ $err", Modifier.padding(12.dp),
                             color = MaterialTheme.colorScheme.onErrorContainer,
                             fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Past recordings", Modifier.padding(start = 16.dp, top = 8.dp),
                     style = MaterialTheme.typography.titleMedium)
                if (recordings.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No recordings yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(recordings, key = { it.name }) { r ->
                            RecordingRow(r) { onOpenRecording(r) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BigButtonPanel(
    state: RecorderState.Snapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val ready    = state.phase == RecorderState.Phase.DEVICE_READY
    val recording = state.phase == RecorderState.Phase.RECORDING
    val idle     = state.phase == RecorderState.Phase.IDLE

    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        color = when {
            recording -> MaterialTheme.colorScheme.errorContainer
            ready     -> MaterialTheme.colorScheme.primaryContainer
            else      -> MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Column(
            Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // STATUS LINE
            val statusText = when {
                recording -> "● Recording — ${state.deviceName}"
                ready     -> "● Connected — ${state.deviceName}"
                else      -> "○ Plug in the Headspace SPC2"
            }
            Text(statusText, fontWeight = FontWeight.SemiBold,
                 fontSize = 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    recording -> "%.1f Mbps · %d IMU samples (%.0f Hz) · %s".format(
                        state.mbps, state.imuSamples, state.imuHz,
                        state.bytesWritten.humanBytes())
                    ready     -> "Tap RECORD when you're ready"
                    else      -> "Recording starts after you tap RECORD"
                },
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(20.dp))

            // BIG BUTTON
            if (recording) {
                Button(
                    onClick = onStop,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    shape = RoundedCornerShape(36.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("STOP", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    shape = RoundedCornerShape(36.dp),
                ) {
                    Icon(Icons.Filled.FiberManualRecord,
                         contentDescription = null,
                         tint = if (ready) Color(0xFFE53935) else Color.Unspecified)
                    Spacer(Modifier.width(8.dp))
                    Text(if (ready) "RECORD" else "WAITING FOR DEVICE",
                         fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (recording) {
                Spacer(Modifier.height(8.dp))
                Text(state.sessionName,
                     fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))
                // Live preview — 16:9 SurfaceView feeds RecorderState.previewSurface
                Surface(
                    color = Color.Black,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)),
                ) {
                    PreviewSurface()
                }
            }
        }
    }
}

@Composable
private fun PreviewSurface() {
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        RecorderState.previewSurface = h.surface
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                        RecorderState.previewSurface = h.surface
                    }
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        RecorderState.previewSurface = null
                    }
                })
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun RecordingRow(rec: Recording, onClick: () -> Unit) {
    val df = remember { SimpleDateFormat("MMM d · HH:mm:ss 'UTC'", Locale.US) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(df.format(rec.startedAt), fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                rec.name,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            rec.sizeBytes.humanBytes(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview
@Composable
private fun AppPreview() {
    MaterialTheme { App(onOpenRecording = {}, onOpenWifiSetup = {}, onOpenDiagnostics = {}) }
}
