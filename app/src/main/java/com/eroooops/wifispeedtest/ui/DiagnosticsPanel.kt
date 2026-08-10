package com.eroooops.wifispeedtest.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eroooops.wifispeedtest.net.ProbeResult
import com.eroooops.wifispeedtest.net.ProbeState

/**
 * Per-server probe detail: how far each host got before it stopped.
 *
 * This is the panel that turns "it doesn't work" into an actual diagnosis. A
 * column of hosts that all reach TLS and die there is a filtered network; hosts
 * returning HTTP statuses are a configuration problem; nothing resolving is DNS.
 */
@Composable
fun DiagnosticsPanel(probes: List<ProbeResult>, modifier: Modifier = Modifier) {
    if (probes.isEmpty()) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            val reached = probes.count { it.state == ProbeState.OK }
            Text(
                text = "Server diagnostics — $reached of ${probes.size} reachable",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Where each host stopped: DNS → TCP → TLS → HTTP → body",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            probes.forEach { probe ->
                ProbeRow(probe)
            }
        }
    }
}

@Composable
private fun ProbeRow(probe: ProbeResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = when (probe.state) {
                ProbeState.OK -> MaterialTheme.colorScheme.secondary
                ProbeState.FAILED -> MaterialTheme.colorScheme.error
                ProbeState.RUNNING -> MaterialTheme.colorScheme.tertiary
                ProbeState.PENDING -> MaterialTheme.colorScheme.surfaceVariant
            },
            content = {},
            modifier = Modifier.size(9.dp),
        )
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = probe.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = probe.scheme,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (probe.scheme == "http") {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = probe.summary,
                style = MaterialTheme.typography.bodySmall,
                color = when (probe.state) {
                    ProbeState.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (probe.state != ProbeState.PENDING) {
                StageTrack(probe)
            }
        }
    }
}

/** Four blocks, filled up to the stage the probe actually reached. */
@Composable
private fun StageTrack(probe: ProbeResult) {
    val stages = listOf(
        "dns" to probe.dnsMs,
        "tcp" to probe.tcpMs,
        "tls" to probe.tlsMs,
        "http" to probe.firstByteMs,
    )
    val secure = probe.scheme.equals("https", ignoreCase = true)

    Row(
        modifier = Modifier.padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        stages.forEach { (label, millis) ->
            val skipped = label == "tls" && !secure
            val done = millis != null
            Text(
                text = when {
                    skipped -> "tls —"
                    done -> "$label ${millis}ms"
                    else -> label
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    skipped -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    done -> MaterialTheme.colorScheme.onSurfaceVariant
                    probe.state == ProbeState.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
