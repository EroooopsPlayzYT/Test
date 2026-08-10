package com.eroooops.wifispeedtest.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eroooops.wifispeedtest.data.TestResult
import com.eroooops.wifispeedtest.net.Phase
import com.eroooops.wifispeedtest.wifi.WifiStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(viewModel: SpeedTestViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val wifi by viewModel.wifi.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.onPermissionResult() }

    // A ten-second test with the screen dozing off mid-run is a wasted test.
    val view = LocalView.current
    DisposableEffect(state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi Speed Test") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Test settings")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WifiCard(
                wifi = wifi,
                locationGranted = state.locationPermissionGranted,
                onGrantLocation = {
                    permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                },
            )

            SpeedGauge(
                mbps = gaugeValue(state),
                caption = captionFor(state.phase),
                detail = detailFor(state),
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedVisibility(visible = state.running) {
                LinearProgressIndicator(
                    progress = { state.phaseProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }

            MetricsRow(state)

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = { if (state.running) viewModel.stop() else viewModel.start() },
                enabled = wifi.connected || state.running,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(
                    imageVector = if (state.running) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (state.running) "Stop" else "Start test")
            }

            if (!wifi.connected) {
                Text(
                    text = "Connect to a Wi-Fi network to run a test.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            state.error?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            HistorySection(
                history = state.history,
                onClear = viewModel::clearHistory,
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSettings) {
        SettingsSheet(
            config = state.config,
            onDismiss = { showSettings = false },
            onSave = {
                viewModel.updateConfig(it)
                showSettings = false
            },
        )
    }
}

private fun gaugeValue(state: UiState): Double = when (state.phase) {
    Phase.DOWNLOAD, Phase.UPLOAD -> state.instantMbps
    Phase.DONE -> state.downloadMbps ?: 0.0
    else -> 0.0
}

private fun captionFor(phase: Phase): String = when (phase) {
    Phase.IDLE -> "Ready"
    Phase.PING -> "Measuring latency"
    Phase.DOWNLOAD -> "Download"
    Phase.UPLOAD -> "Upload"
    Phase.DONE -> "Download result"
    Phase.ERROR -> "Test failed"
}

private fun detailFor(state: UiState): String? = when (state.phase) {
    Phase.DOWNLOAD -> state.downloadMbps?.let { "avg ${formatSpeed(it)} Mbps" }
    Phase.UPLOAD -> state.uploadMbps?.let { "avg ${formatSpeed(it)} Mbps" }
    Phase.DONE -> "Tap start to run again"
    else -> null
}

@Composable
private fun WifiCard(
    wifi: WifiStatus,
    locationGranted: Boolean,
    onGrantLocation: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalBars(
                    bars = wifi.signalBars,
                    modifier = Modifier.size(28.dp, 22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            !wifi.connected -> "Not connected to Wi-Fi"
                            wifi.ssid != null -> wifi.ssid
                            !locationGranted -> "Wi-Fi network (name hidden)"
                            else -> "Wi-Fi network"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val subtitle = listOfNotNull(
                        wifi.band,
                        wifi.channel?.let { "ch $it" },
                        wifi.standard,
                    ).joinToString(" · ")
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (wifi.connected) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    InfoCell(
                        "Signal",
                        wifi.rssiDbm?.let { "$it dBm" } ?: "—",
                        wifi.signalLabel,
                        Modifier.weight(1f),
                    )
                    InfoCell(
                        "Link rate",
                        wifi.txLinkSpeedMbps?.let { "$it Mbps" } ?: "—",
                        wifi.rxLinkSpeedMbps?.let { "$it Mbps down" },
                        Modifier.weight(1f),
                    )
                    InfoCell(
                        "IP address",
                        wifi.ipv4 ?: "—",
                        null,
                        Modifier.weight(1f),
                    )
                }
            }

            if (wifi.connected && wifi.ssid == null && !locationGranted) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onGrantLocation, modifier = Modifier.align(Alignment.Start)) {
                    Text("Grant location access to show the network name")
                }
            }
        }
    }
}

@Composable
private fun InfoCell(
    label: String,
    value: String,
    hint: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricsRow(state: UiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricTile("Ping", formatMillis(state.pingMs), Modifier.weight(1f))
        MetricTile("Jitter", formatMillis(state.jitterMs), Modifier.weight(1f))
        MetricTile(
            "Download",
            state.downloadMbps?.let { "${formatSpeed(it)} Mbps" } ?: "—",
            Modifier.weight(1f),
        )
        MetricTile(
            "Upload",
            state.uploadMbps?.let { "${formatSpeed(it)} Mbps" } ?: "—",
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HistorySection(history: List<TestResult>, onClear: () -> Unit) {
    if (history.isEmpty()) return

    Spacer(Modifier.height(28.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "History",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClear) {
            Icon(Icons.Default.Delete, contentDescription = "Clear history")
        }
    }

    // Deliberately not a LazyColumn: this sits inside a scrolling Column, and
    // the list is capped at 50 entries.
    history.forEach { result ->
        HistoryRow(result)
    }
}

@Composable
private fun HistoryRow(result: TestResult) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.ssid ?: "Wi-Fi",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = listOfNotNull(
                        formatTimestamp(result.timestamp),
                        result.band,
                        result.rssiDbm?.let { "$it dBm" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "↓ ${formatSpeed(result.downloadMbps)}  ↑ ${formatSpeed(result.uploadMbps)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = formatMillis(result.pingMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
