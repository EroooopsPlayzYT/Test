package com.eroooops.wifispeedtest.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.eroooops.wifispeedtest.net.TestConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    config: TestConfig,
    onDismiss: () -> Unit,
    onSave: (TestConfig) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var downloadUrl by remember { mutableStateOf(config.downloadUrl) }
    var uploadUrl by remember { mutableStateOf(config.uploadUrl) }
    var connections by remember { mutableIntStateOf(config.connections) }
    var duration by remember { mutableIntStateOf(config.downloadSeconds) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = "Test settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Point these at a server on your own network to measure the " +
                    "Wi-Fi link itself instead of your internet connection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = downloadUrl,
                onValueChange = { downloadUrl = it },
                label = { Text("Download URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = uploadUrl,
                onValueChange = { uploadUrl = it },
                label = { Text("Upload URL (POST target)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            SliderRow(
                label = "Parallel connections",
                value = connections,
                range = TestConfig.CONNECTION_RANGE,
                onChange = { connections = it },
            )

            SliderRow(
                label = "Duration per direction",
                value = duration,
                range = TestConfig.DURATION_RANGE,
                suffix = "s",
                onChange = { duration = it },
            )

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        downloadUrl = TestConfig.DEFAULT_DOWNLOAD_URL
                        uploadUrl = TestConfig.DEFAULT_UPLOAD_URL
                        connections = 4
                        duration = 10
                    }
                ) {
                    Text("Reset")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        onSave(
                            config.copy(
                                downloadUrl = downloadUrl.trim(),
                                uploadUrl = uploadUrl.trim(),
                                connections = connections,
                                downloadSeconds = duration,
                                uploadSeconds = duration,
                            )
                        )
                    },
                    enabled = downloadUrl.isNotBlank() && uploadUrl.isNotBlank(),
                ) {
                    Text("Save")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    suffix: String = "",
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$value$suffix",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}
