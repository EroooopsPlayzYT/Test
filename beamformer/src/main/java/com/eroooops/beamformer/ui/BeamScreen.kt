package com.eroooops.beamformer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eroooops.beamformer.BuildConfig
import com.eroooops.beamformer.audio.CaptureSource
import com.eroooops.beamformer.audio.MonitorRoute
import com.eroooops.beamformer.data.BeamSettings
import com.eroooops.beamformer.dsp.BeamformerType
import com.eroooops.beamformer.ui.theme.Amber
import com.eroooops.beamformer.ui.theme.Mint
import com.eroooops.beamformer.ui.theme.Rose
import com.eroooops.beamformer.ui.theme.Sky

@Composable
fun BeamScreen(viewModel: BeamViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // RECORD_AUDIO is requested together with POST_NOTIFICATIONS (API 33+,
    // needed for the capture foreground service's own notification to show at
    // all) so there is one prompt to get through rather than two.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.onPermissionResult() }

    // Bluetooth naming is optional and requested only when the headset input is
    // actually chosen — denying it just leaves the headset unnamed, per
    // BluetoothAudio's graceful degradation.
    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.onPermissionResult() }

    // Steering that follows the live estimate is driven from here rather than
    // from the audio thread, so the easing happens at display rate and never
    // competes with sample delivery.
    LaunchedEffect(engine.doaDeg, engine.doaConfidence) {
        val bearing = engine.doaDeg
        if (bearing != null) viewModel.onDirectionEstimate(bearing, engine.doaConfidence)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        Header(running = engine.running, version = BuildConfig.VERSION_NAME)

        if (!state.permissionGranted) {
            SectionCard(title = "Microphone") {
                Text(
                    "This app does nothing without microphone access — every number on this " +
                        "screen is derived from live audio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions += Manifest.permission.POST_NOTIFICATIONS
                    }
                    permissionLauncher.launch(permissions.toTypedArray())
                }) {
                    Text("Grant microphone access")
                }
            }
        }

        PolarRadar(
            surface = engine.surface,
            angles = engine.angles,
            steerDeg = state.settings.steerDeg,
            doaDeg = engine.doaDeg,
            doaConfidence = engine.doaConfidence,
            simulated = state.simulating,
            directionAvailable = engine.directionAvailable,
            onSteer = viewModel::steerTo,
        )

        BearingReadout(
            steerDeg = state.settings.steerDeg,
            doaDeg = engine.doaDeg.takeIf { engine.directionAvailable },
            confidence = engine.doaConfidence,
            beamVsOmniDb = engine.beamVsOmniDb,
        )

        BearingWaterfall(surface = engine.surface, angles = engine.angles)

        TransportRow(
            running = engine.running,
            // The probe sweeps every source, channel count and sample rate the
            // device offers, which can take several seconds. Starting capture
            // before it finishes means guessing at exactly the thing this app
            // exists to measure — the channel count and geometry to use — so
            // Listen waits for a verdict rather than assuming one.
            listenEnabled = !state.probing,
            recording = engine.recording,
            recordedSeconds = engine.recordedSeconds,
            onToggleRunning = viewModel::toggleRunning,
            onToggleRecording = viewModel::toggleRecording,
        )

        SourceSelector(
            source = state.source,
            canBeamformLive = state.canBeamformLive,
            bluetoothAvailable = state.bluetoothAvailable,
            headsetName = state.headsetName,
            onSelect = { selected ->
                // Naming the headset needs BLUETOOTH_CONNECT on API 31+; ask for
                // it only when the headset input is actually chosen, and only if
                // it is not already granted — denying it just leaves the name
                // blank, per BluetoothAudio's graceful degradation.
                if (selected == CaptureSource.BLUETOOTH_REFERENCE &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    state.headsetName == null
                ) {
                    bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
                viewModel.selectSource(selected)
            },
        )

        if (state.source == CaptureSource.BLUETOOTH_REFERENCE) {
            BluetoothNotice(state.headsetName)
        }

        BeamControls(
            settings = state.settings,
            onType = viewModel::setBeamformerType,
            onSteer = viewModel::steerTo,
            onNoiseFloor = viewModel::setWhiteNoiseFloor,
            onFollow = viewModel::setFollowDirection,
            enabled = state.source != CaptureSource.BLUETOOTH_REFERENCE,
        )

        MonitorPanel(
            monitoring = viewModel.monitoringRequested,
            route = engine.monitorRoute,
            blockedReason = engine.monitorBlockedReason,
            gain = state.settings.monitorGain,
            allowSpeaker = state.settings.allowSpeakerMonitor,
            onToggle = viewModel::toggleMonitoring,
            onGain = viewModel::setMonitorGain,
            onAllowSpeaker = viewModel::setAllowSpeakerMonitor,
        )

        LevelsPanel(
            channelLevelsDb = engine.channelLevelsDb,
            beamLevelDb = engine.beamLevelDb,
            omniLevelDb = engine.omniLevelDb,
            beamVsOmniDb = engine.beamVsOmniDb,
        )

        ArrayPanel(
            spacingMetres = state.geometry?.spacingMetres ?: state.settings.spacingMetres,
            geometrySource = engine.geometrySource,
            geometryNote = engine.geometryNote.ifEmpty {
                state.geometry?.note ?: "Not started yet."
            },
            aliasFrequencyHz = engine.aliasFrequencyHz,
            scanBandHz = engine.scanBandHz,
            directivityIndexDb = engine.directivityIndexDb,
            whiteNoiseGainDb = engine.whiteNoiseGainDb,
            routedDevice = engine.routedDevice,
        )

        SpacingControl(
            spacingMetres = state.settings.spacingMetres,
            fromDevice = engine.geometrySource ==
                com.eroooops.beamformer.audio.GeometrySource.REPORTED_BY_DEVICE,
            onSpacing = viewModel::setSpacing,
        )

        ProbePanel(
            report = state.report,
            expanded = state.showProbeDetail,
            probing = state.probing,
            onToggle = viewModel::toggleProbeDetail,
            onShare = {
                viewModel.shareReport()?.let {
                    context.startActivity(android.content.Intent.createChooser(it, "Share report"))
                }
            },
            onRerun = viewModel::runProbe,
        )

        RecordingsPanel(
            recordings = state.recordings,
            onShare = { recording ->
                viewModel.shareRecording(recording)?.let {
                    context.startActivity(
                        android.content.Intent.createChooser(it, "Share recording")
                    )
                }
            },
            onDelete = viewModel::deleteRecording,
        )

        engine.error?.let { error ->
            SectionCard(title = "Error") {
                Text(error, style = MaterialTheme.typography.bodySmall, color = Rose)
            }
        }

        state.message?.let { message ->
            SectionCard(title = "Note", trailing = {
                TextButton(onClick = viewModel::dismissMessage) { Text("Dismiss") }
            }) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Header(running: Boolean, version: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Beamformer", style = MaterialTheme.typography.titleMedium)
        Text(
            if (running) "LISTENING · v$version" else "IDLE · v$version",
            style = MaterialTheme.typography.labelSmall,
            color = if (running) Mint else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The two numbers worth reading at a glance: where it is aimed, and what is out there. */
@Composable
private fun BearingReadout(
    steerDeg: Double,
    doaDeg: Double?,
    confidence: Double,
    beamVsOmniDb: Double,
) {
    SectionCard(title = "Bearing") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "STEERED",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatDegrees(steerDeg),
                    style = MaterialTheme.typography.displayLarge,
                    color = Mint,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "LOUDEST",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatDegrees(doaDeg),
                    style = MaterialTheme.typography.displayLarge,
                    color = if (confidence > 0.3) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        StatRow("Confidence", String.format(java.util.Locale.US, "%.0f%%", confidence * 100))
        StatRow("Beam vs unsteered", formatSignedDb(beamVsOmniDb), tint = Sky)
        Text(
            "Beam vs unsteered compares this instant's output with a plain average of the " +
                "same microphones. It depends on what is making noise right now — the array's " +
                "own directivity is in the Array card below.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TransportRow(
    running: Boolean,
    listenEnabled: Boolean,
    recording: Boolean,
    recordedSeconds: Double,
    onToggleRunning: () -> Unit,
    onToggleRecording: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onToggleRunning,
            // Stopping is always allowed; only starting waits on the probe.
            enabled = running || listenEnabled,
            modifier = Modifier.weight(1f),
        ) {
            Text(if (running) "Stop" else "Listen")
        }
        OutlinedButton(
            onClick = onToggleRecording,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (recording) Rose else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(if (recording) "Stop · ${formatSeconds(recordedSeconds)}" else "Record")
        }
    }
}

@Composable
private fun SourceSelector(
    source: CaptureSource,
    canBeamformLive: Boolean,
    bluetoothAvailable: Boolean,
    headsetName: String?,
    onSelect: (CaptureSource) -> Unit,
) {
    SectionCard(title = "Input") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = source == CaptureSource.PHONE_ARRAY,
                onClick = { onSelect(CaptureSource.PHONE_ARRAY) },
                enabled = canBeamformLive,
                label = { Text("Phone array", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = source == CaptureSource.SIMULATION,
                onClick = { onSelect(CaptureSource.SIMULATION) },
                label = { Text("Simulation", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = source == CaptureSource.BLUETOOTH_REFERENCE,
                onClick = { onSelect(CaptureSource.BLUETOOTH_REFERENCE) },
                enabled = bluetoothAvailable,
                label = { Text("Headset", style = MaterialTheme.typography.labelSmall) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                !canBeamformLive && source == CaptureSource.SIMULATION ->
                    "This device will not give two real channels, so the live option is off. " +
                        "Simulation runs synthetic sources through the same beamformer, and is " +
                        "labelled as such on the display."
                source == CaptureSource.PHONE_ARRAY ->
                    "The phone's own microphones — the only input here that can be beamformed."
                source == CaptureSource.SIMULATION ->
                    "Synthetic sources through the real beamformer. Nothing is being heard."
                else -> headsetName?.let { "Reference recording from $it." }
                    ?: "Reference recording from the connected headset."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The Bluetooth headset explanation, stated where it matters rather than buried
 * in a readme: several capsules in the headset do not make it an array as far as
 * the phone is concerned.
 */
@Composable
private fun BluetoothNotice(headsetName: String?) {
    SectionCard(title = "Why the headset cannot be beamformed") {
        Text(
            "${headsetName ?: "A Bluetooth headset"} may have several microphones, but they " +
                "are wired to its own processor. It runs its noise reduction and picks its own " +
                "beam internally, then sends the phone one finished mono stream — 16 kHz on a " +
                "good link, 8 kHz on a poor one. No Android API reaches the individual capsules.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Combining it with the phone's microphones is not possible either: two capture " +
                "streams on unrelated clocks, with Bluetooth's tens of milliseconds of jitter, " +
                "cannot be aligned to the fractions of a millisecond beamforming measures.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "So this mode records the headset's own processed output as a reference track, to " +
                "compare against a phone-array recording of the same scene. Opening the " +
                "headset microphone also drops it out of high-quality playback, so it is a poor " +
                "choice for monitoring at the same time.",
            style = MaterialTheme.typography.labelSmall,
            color = Amber,
        )
    }
}

@Composable
private fun BeamControls(
    settings: BeamSettings,
    onType: (BeamformerType) -> Unit,
    onSteer: (Double) -> Unit,
    onNoiseFloor: (Double) -> Unit,
    onFollow: (Boolean) -> Unit,
    enabled: Boolean,
) {
    SectionCard(title = "Beam") {
        if (!enabled) {
            Text(
                "One channel cannot be steered. Switch the input back to the phone array or " +
                    "simulation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.beamformerType == BeamformerType.SUPERDIRECTIVE,
                onClick = { onType(BeamformerType.SUPERDIRECTIVE) },
                label = { Text("Superdirective", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = settings.beamformerType == BeamformerType.DELAY_SUM,
                onClick = { onType(BeamformerType.DELAY_SUM) },
                label = { Text("Delay and sum", style = MaterialTheme.typography.labelSmall) },
            )
        }

        Spacer(Modifier.height(10.dp))
        StatRow("Steer", formatDegrees(settings.steerDeg))
        Slider(
            value = settings.steerDeg.toFloat(),
            onValueChange = { onSteer(it.toDouble()) },
            valueRange = 0f..180f,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Follow the loudest direction", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Eases the beam toward the estimate instead of snapping to it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = settings.followDirection, onCheckedChange = onFollow)
        }

        if (settings.beamformerType == BeamformerType.SUPERDIRECTIVE) {
            Spacer(Modifier.height(10.dp))
            StatRow("Noise floor", formatSignedDb(settings.minWhiteNoiseGainDb))
            Slider(
                value = settings.minWhiteNoiseGainDb.toFloat(),
                onValueChange = { onNoiseFloor(it.toDouble()) },
                valueRange = -20f..0f,
            )
            Text(
                "How much microphone hiss the weights may amplify. Further left buys sharper " +
                    "low-frequency directivity and a louder noise floor; at 0 dB the result is " +
                    "close to plain delay-and-sum. The trade only bites below about 500 Hz — " +
                    "above that the constraint is inactive.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonitorPanel(
    monitoring: Boolean,
    route: MonitorRoute,
    blockedReason: String?,
    gain: Double,
    allowSpeaker: Boolean,
    onToggle: () -> Unit,
    onGain: (Double) -> Unit,
    onAllowSpeaker: (Boolean) -> Unit,
) {
    SectionCard(title = "Monitor") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Listen to the beam", style = MaterialTheme.typography.bodySmall)
                Text(
                    when (route) {
                        MonitorRoute.WIRED -> "Wired headphones — the best option here."
                        MonitorRoute.USB -> "USB headphones — low latency."
                        MonitorRoute.BLUETOOTH -> "Bluetooth output."
                        MonitorRoute.SPEAKER -> "Loudspeaker."
                        MonitorRoute.NONE -> "No output route detected yet."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = monitoring, onCheckedChange = { onToggle() })
        }

        blockedReason?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = if (route == MonitorRoute.SPEAKER) Rose else Amber,
            )
        }

        if (route == MonitorRoute.SPEAKER) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Use the loudspeaker anyway",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(checked = allowSpeaker, onCheckedChange = onAllowSpeaker)
            }
        }

        Spacer(Modifier.height(6.dp))
        StatRow("Gain", String.format(java.util.Locale.US, "%.1f×", gain))
        Slider(
            value = gain.toFloat(),
            onValueChange = { onGain(it.toDouble()) },
            valueRange = 0f..4f,
        )
    }
}

@Composable
private fun LevelsPanel(
    channelLevelsDb: List<Double>,
    beamLevelDb: Double,
    omniLevelDb: Double,
    beamVsOmniDb: Double,
) {
    SectionCard(title = "Levels") {
        channelLevelsDb.forEachIndexed { index, level ->
            LevelBar("Microphone ${index + 1}", level, Sky)
        }
        LevelBar("Unsteered average", omniLevelDb, MaterialTheme.colorScheme.onSurfaceVariant)
        LevelBar("Beam output", beamLevelDb, Mint)
        Spacer(Modifier.height(4.dp))
        StatRow("Difference", formatSignedDb(beamVsOmniDb))
    }
}

@Composable
private fun SpacingControl(
    spacingMetres: Double,
    fromDevice: Boolean,
    onSpacing: (Double) -> Unit,
) {
    SectionCard(title = "Microphone spacing") {
        if (fromDevice) {
            Text(
                "The device reported its real microphone positions, so this setting is not " +
                    "in use.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
        }
        StatRow("Assumed spacing", formatMillimetres(spacingMetres))
        Slider(
            value = spacingMetres.toFloat(),
            onValueChange = { onSpacing(it.toDouble()) },
            valueRange = 0.02f..0.30f,
        )
        Text(
            "Every bearing on this screen is computed from this number, so a wrong value " +
                "produces confidently wrong angles. Measure the distance between the hole by " +
                "the charging port and the one at the top edge.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordingsPanel(
    recordings: List<com.eroooops.beamformer.data.Recording>,
    onShare: (com.eroooops.beamformer.data.Recording) -> Unit,
    onDelete: (com.eroooops.beamformer.data.Recording) -> Unit,
) {
    SectionCard(title = "Recordings") {
        if (recordings.isEmpty()) {
            Text(
                "Each run saves two files: the beamformed output, and the raw microphone " +
                    "channels beside it. The raw capture is what lets the same scene be run " +
                    "through a changed beamformer later, off the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        recordings.take(12).forEach { recording ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(recording.name, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${recording.kind} · ${formatBytes(recording.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onShare(recording) }) {
                    Text("Share", style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onDelete(recording) }) {
                    Text("Delete", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
