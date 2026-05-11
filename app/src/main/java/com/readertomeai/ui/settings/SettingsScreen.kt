package com.readertomeai.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.readertomeai.BuildConfig
import com.readertomeai.ui.theme.Purple

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onVoiceManager: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val ttsSpeed by viewModel.ttsSpeed.collectAsState()
    val readingTheme by viewModel.readingTheme.collectAsState()
    val selectedVoiceName by viewModel.selectedVoiceName.collectAsState()
    val autoScroll by viewModel.autoScrollDuringTts.collectAsState()
    val highlightTts by viewModel.highlightDuringTts.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Voice section
            SettingsSection("Voice") {
                SettingsItem(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = "Voice Model",
                    subtitle = selectedVoiceName,
                    onClick = onVoiceManager
                )

                SettingsSliderItem(
                    icon = Icons.Outlined.Speed,
                    title = "Speech Speed",
                    value = ttsSpeed,
                    valueRange = 0.5f..2.0f,
                    valueLabel = "${"%.1f".format(ttsSpeed)}x",
                    onValueChange = { viewModel.setTtsSpeed(it) }
                )

                SettingsSwitchItem(
                    icon = Icons.Outlined.TextFields,
                    title = "Highlight while reading",
                    subtitle = "Highlight text as it's spoken",
                    checked = highlightTts,
                    onCheckedChange = { viewModel.setHighlightDuringTts(it) }
                )

                SettingsSwitchItem(
                    icon = Icons.Outlined.SwipeVertical,
                    title = "Auto-scroll",
                    subtitle = "Scroll to follow along with narration",
                    checked = autoScroll,
                    onCheckedChange = { viewModel.setAutoScrollDuringTts(it) }
                )
            }

            // Reading section
            SettingsSection("Reading") {
                SettingsItem(
                    icon = Icons.Outlined.Palette,
                    title = "Default Theme",
                    subtitle = readingTheme.replaceFirstChar { it.uppercase() },
                    onClick = { /* Opens theme picker */ }
                )
            }

            // About section
            SettingsSection("About") {
                SettingsItem(
                    icon = Icons.Outlined.Info,
                    title = "OnDeviceReaderAI",
                    subtitle = "Version ${BuildConfig.VERSION_NAME}",
                    onClick = { }
                )

                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "A free ePub reader with AI-powered natural text-to-speech. " +
                        "Your books, your device, no cloud required.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Powered by Piper TTS (sherpa-onnx)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = Purple,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        content()
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Filled.ChevronRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun SettingsSliderItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(valueLabel, fontSize = 14.sp, color = Purple, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.padding(start = 40.dp),
            colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple)
        )
    }
}

@Composable
fun SettingsSwitchItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Purple)
        )
    }
}
