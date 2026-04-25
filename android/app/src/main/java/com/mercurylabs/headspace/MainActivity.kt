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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Folder
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleUsbAttach(intent)

        setContent { App(onOpenRecording = { openRecording(it) }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbAttach(intent)
    }

    private fun handleUsbAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) ?: return
        // Plug-and-play: hand off to the foreground service immediately.
        RecordingService.start(this, device)
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
private fun App(onOpenRecording: (Recording) -> Unit) {
    val ctx = LocalContext.current
    var recordings by remember { mutableStateOf(Recordings.list(ctx)) }
    // Refresh the list whenever the activity resumes (e.g. after recording stops).
    LaunchedEffect(Unit) { recordings = Recordings.list(ctx) }

    MaterialTheme {
        @OptIn(ExperimentalMaterial3Api::class)
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Headspace Recorder", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        IconButton(onClick = { recordings = Recordings.list(ctx) }) {
                            Icon(Icons.Filled.Folder, contentDescription = "Refresh")
                        }
                    },
                )
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                StatusCard()
                if (recordings.isEmpty()) {
                    EmptyState()
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
private fun StatusCard() {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Icon(
                Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = Color(0xFFE53935),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Plug in the Headspace SPC2", fontWeight = FontWeight.SemiBold)
                Text(
                    "Recording starts automatically · saves to app folder",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Connect the camera via USB-OTG to start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

@Preview
@Composable
private fun AppPreview() {
    MaterialTheme { App(onOpenRecording = {}) }
}
