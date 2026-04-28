package com.mercurylabs.headspace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignored */ }

    private val handledDeviceIds = mutableSetOf<Int>()
    private var pollJob: Job? = null

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
        // Edge-to-edge so the camera preview fills under the status bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleUsbAttach(intent)

        setContent {
            var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                when (val s = screen) {
                    is Screen.Camera -> CameraScreen(
                        onOpenWifi = { screen = Screen.Wifi },
                        onOpenDiagnostics = { screen = Screen.Diag },
                        onOpenRecording = { rec -> screen = Screen.Player(rec.dir.absolutePath) },
                    )
                    is Screen.Wifi -> WifiSetupScreen(onBack = { screen = Screen.Camera })
                    is Screen.Diag -> DiagnosticsScreen(onBack = { screen = Screen.Camera })
                    is Screen.Player -> PlayerScreen(
                        sessionDirPath = s.sessionDirPath,
                        onBack = { screen = Screen.Camera },
                    )
                }
            }
        }
    }

    private sealed class Screen {
        data object Camera : Screen()
        data object Wifi : Screen()
        data object Diag : Screen()
        data class Player(val sessionDirPath: String) : Screen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbAttach(intent)
    }

    override fun onResume() {
        super.onResume()
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
        val liveIds = live.map { it.deviceId }.toSet()
        handledDeviceIds.retainAll(liveIds)
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
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) ?: return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            pendingDevice = device
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        startRecordingFor(device)
    }

    private fun startRecordingFor(device: UsbDevice) {
        if (device.deviceId in handledDeviceIds) return
        handledDeviceIds.add(device.deviceId)
        try { RecordingService.start(this, device) }
        catch (e: Throwable) { CrashLog.writeException(this, "RecordingService.start failed", e) }
    }
}

// ---------- Camera screen (full-screen preview + overlay controls) ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraScreen(
    onOpenWifi: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenRecording: (Recording) -> Unit,
) {
    val ctx = LocalContext.current
    val rec by RecorderState.state.collectAsState()
    var showSheet by remember { mutableStateOf(false) }
    var recordings by remember { mutableStateOf(Recordings.list(ctx)) }
    LaunchedEffect(rec.phase) {
        if (rec.phase != RecorderState.Phase.RECORDING) {
            recordings = Recordings.list(ctx)
        }
    }

    val recording = rec.phase == RecorderState.Phase.RECORDING
    val ready = rec.phase == RecorderState.Phase.DEVICE_READY

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Preview at the camera's native 16:9 aspect, letterboxed inside the
        // portrait phone screen so the image isn't stretched. The surrounding
        // black space is where overlay controls (status, files, record) live.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            PreviewSurface(Modifier.fillMaxSize())
        }

        // Top status badge + actions.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(rec)
            Spacer(Modifier.weight(1f))
            OverlayIconButton(onClick = onOpenDiagnostics) {
                Icon(Icons.Filled.BugReport, contentDescription = "Diagnostics", tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            OverlayIconButton(onClick = onOpenWifi) {
                Icon(Icons.Filled.Wifi, contentDescription = "WiFi", tint = Color.White)
            }
        }

        // Optional error toast in the middle.
        rec.lastError?.let { err ->
            Surface(
                color = Color(0xCC8B0000),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp, start = 16.dp, end = 16.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("⚠ $err", Modifier.padding(10.dp), color = Color.White, fontSize = 13.sp)
            }
        }

        // Bottom controls row: files (left), record (center).
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Files icon
            OverlayIconButton(onClick = { showSheet = true }, large = true) {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = "Recordings",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            // Record button (centered visually because it's the main control)
            RecordButton(
                recording = recording,
                enabled = ready || recording,
                onClick = {
                    if (recording) RecordingService.stopRecording(ctx)
                    else RecordingService.startRecording(ctx)
                },
            )
            Spacer(Modifier.weight(1f))
            // Symmetric placeholder so the record button is truly centered.
            Spacer(Modifier.size(48.dp))
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            RecordingsList(
                recordings = recordings,
                onPick = { r ->
                    showSheet = false
                    onOpenRecording(r)
                },
            )
        }
    }
}

@Composable
private fun StatusBadge(rec: RecorderState.Snapshot) {
    val (dotColor, label) = when (rec.phase) {
        RecorderState.Phase.RECORDING ->
            Color(0xFFE53935) to "REC · ${rec.bytesWritten.humanBytes()} · %.1f Mbps".format(rec.mbps)
        RecorderState.Phase.DEVICE_READY ->
            Color(0xFF66BB6A) to "Ready · ${rec.deviceName}"
        RecorderState.Phase.IDLE ->
            Color(0xFF888888) to "Plug in Headspace SPC2"
    }
    Surface(
        color = Color(0xAA000000),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun OverlayIconButton(
    onClick: () -> Unit,
    large: Boolean = false,
    content: @Composable () -> Unit,
) {
    val size = if (large) 48.dp else 40.dp
    Surface(
        color = Color(0x80000000),
        shape = CircleShape,
        modifier = Modifier.size(size),
    ) {
        Box(Modifier.fillMaxSize().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/** Classic camera record button: white outer ring, red dot when idle, red rounded square when recording. */
@Composable
private fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val outerSize = 76.dp
    val ringColor = if (enabled) Color.White else Color(0x66FFFFFF)
    Box(
        modifier = Modifier
            .size(outerSize)
            .clip(CircleShape)
            .background(Color(0x55000000))
            .border(width = 4.dp, color = ringColor, shape = CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            // Red rounded square (universal "stop" indicator on camera apps)
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFE53935)),
            )
        } else {
            // Solid red dot (universal "record")
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(if (enabled) Color(0xFFE53935) else Color(0x66E53935)),
            )
        }
    }
}

@Composable
private fun PreviewSurface(modifier: Modifier = Modifier) {
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
        modifier = modifier,
    )
}

// ---------- Recordings list (in bottom sheet) ----------

@Composable
private fun RecordingsList(recordings: List<Recording>, onPick: (Recording) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            "Recordings",
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        if (recordings.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                Text("No recordings yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(recordings, key = { it.name }) { r ->
                    RecordingRow(r) { onPick(r) }
                    HorizontalDivider()
                }
            }
        }
    }
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

// ---------- Player screen (ExoPlayer in a full-screen surface) ----------

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PlayerScreen(sessionDirPath: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val videoFile = remember(sessionDirPath) {
        java.io.File(sessionDirPath, "video.mp4")
    }
    val player = remember(sessionDirPath) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.statusBarsPadding().padding(8.dp)) {
            OverlayIconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
        // Filename label
        Text(
            java.io.File(sessionDirPath).name,
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 14.dp),
        )
    }
}
