package com.eroooops.beamformer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eroooops.beamformer.audio.CaptureVerdict
import com.eroooops.beamformer.audio.GeometrySource
import com.eroooops.beamformer.audio.ProbeReport
import com.eroooops.beamformer.ui.theme.Amber
import com.eroooops.beamformer.ui.theme.Mint
import com.eroooops.beamformer.ui.theme.Rose
import com.eroooops.beamformer.ui.theme.Sky
import java.util.Locale

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                trailing?.invoke()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun StatRow(label: String, value: String, tint: Color? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = tint ?: MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun LevelBar(label: String, db: Double, tint: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDb(db),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(meterFraction(db))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint),
            )
        }
    }
}

/**
 * What the device turned out to be capable of.
 *
 * The verdict comes first and in plain words. On most phones it is bad news, and
 * putting it behind a disclosure would make everything below it dishonest.
 */
@Composable
fun ProbePanel(
    report: ProbeReport?,
    expanded: Boolean,
    probing: Boolean,
    onToggle: () -> Unit,
    onShare: () -> Unit,
    onRerun: () -> Unit,
) {
    SectionCard(
        title = "Microphone capability",
        trailing = {
            Row {
                TextButton(onClick = onShare) { Text("Share", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = onToggle) {
                    Text(
                        if (expanded) "Hide" else "Details",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
    ) {
        if (probing) {
            Text(
                "Probing every source, channel count and sample rate...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        if (report == null) {
            Text(
                "Not probed yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRerun) { Text("Probe now") }
            return@SectionCard
        }

        val headline: String
        val detail: String
        val tint: Color
        when (report.verdict) {
            CaptureVerdict.REAL_MULTICHANNEL -> {
                headline = "Two independent channels"
                detail = "This device hands over genuinely different microphone signals, " +
                    "so the beamforming below is real."
                tint = Mint
            }

            CaptureVerdict.DUPLICATED_MONO -> {
                headline = "Stereo accepted, mono delivered"
                detail = "The device accepted a two-channel request and returned the same " +
                    "signal twice. There is no arrival-time difference to measure, so no " +
                    "direction can be found. Switched to simulation."
                tint = Rose
            }

            CaptureVerdict.MONO_ONLY -> {
                headline = "One channel only"
                detail = "Android is mixing the microphones together before this app sees " +
                    "them. Beamforming needs the separate signals, so this device cannot do " +
                    "it. Switched to simulation."
                tint = Amber
            }

            CaptureVerdict.UNAVAILABLE -> {
                headline = "No usable capture"
                detail = "Nothing opened, or every stream returned digital silence — another " +
                    "app may be holding the microphone."
                tint = Rose
            }

            CaptureVerdict.NO_PERMISSION -> {
                headline = "Microphone permission needed"
                detail = "Grant it and probe again."
                tint = Amber
            }
        }

        Text(headline, style = MaterialTheme.typography.titleMedium, color = tint)
        Spacer(Modifier.height(4.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!expanded) return@SectionCard

        Spacer(Modifier.height(12.dp))
        StatRow("Device", report.deviceLabel)
        StatRow("Android", "${report.androidRelease} (API ${android.os.Build.VERSION.SDK_INT})")
        StatRow(
            "Claims UNPROCESSED",
            when (report.claimsUnprocessedSupport) {
                true -> "yes"
                false -> "no"
                null -> "did not say"
            },
        )
        report.chosen?.let { chosen ->
            StatRow("Using", "${chosen.sourceName} · ${chosen.channels} ch · ${chosen.sampleRate} Hz")
            chosen.routedDevice?.let { StatRow("Routed via", it) }
            chosen.correlation?.let {
                StatRow("Channel correlation", String.format(Locale.US, "%.5f", it))
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "ATTEMPTS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        report.attempts.filter { it.opened }.take(14).forEach { attempt ->
            val summary = buildString {
                append("${attempt.sourceName} ${attempt.channels}ch ${attempt.sampleRate}Hz — ")
                append(if (attempt.delivered) "audio" else "silent")
                if (attempt.identicalSamples) append(", identical channels")
                attempt.correlation?.let { append(String.format(Locale.US, ", r=%.4f", it)) }
            }
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = if (attempt.usableForBeamforming) {
                    Mint
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        if (report.microphones.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "REPORTED MICROPHONES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            report.microphones.forEach { mic ->
                Text(
                    if (mic.positionKnown) {
                        String.format(
                            Locale.US,
                            "%s — x %.3f, y %.3f, z %.3f m",
                            mic.description, mic.x, mic.y, mic.z,
                        )
                    } else {
                        "${mic.description} — position not disclosed"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onRerun) { Text("Probe again") }
    }
}

/**
 * The array's physical limits, in numbers.
 *
 * These explain everything else on screen: how narrow the beam can be, why the
 * direction display stops at a particular frequency, and why the answer is a
 * cone rather than a point.
 */
@Composable
fun ArrayPanel(
    spacingMetres: Double,
    geometrySource: GeometrySource,
    geometryNote: String,
    aliasFrequencyHz: Double,
    scanBandHz: Pair<Double, Double>?,
    directivityIndexDb: Double,
    whiteNoiseGainDb: Double,
    routedDevice: String?,
) {
    SectionCard(title = "Array") {
        StatRow(
            "Spacing",
            formatMillimetres(spacingMetres) + when (geometrySource) {
                GeometrySource.REPORTED_BY_DEVICE -> " (from device)"
                GeometrySource.ASSUMED -> " (assumed)"
                GeometrySource.SIMULATED -> " (simulated)"
            },
        )
        StatRow("Aliasing above", formatHz(aliasFrequencyHz))
        scanBandHz?.let {
            StatRow("Direction band", "${formatHz(it.first)} – ${formatHz(it.second)}")
        }
        StatRow("Directivity index", formatSignedDb(directivityIndexDb), tint = Sky)
        StatRow("Noise amplification", formatSignedDb(whiteNoiseGainDb), tint = Amber)
        routedDevice?.let { StatRow("Capturing from", it) }

        Spacer(Modifier.height(8.dp))
        Text(
            geometryNote,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Two microphones measure one thing: the arrival-time difference along the line " +
                "between them. Every direction at the same angle to that line gives the same " +
                "answer, so a bearing is a cone, and front cannot be told from back.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
