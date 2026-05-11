package com.readertomeai.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.readertomeai.data.model.VoiceModel
import com.readertomeai.data.model.VoiceQuality
import com.readertomeai.ui.theme.Purple

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceManagerScreen(
    onBack: () -> Unit,
    viewModel: VoiceManagerViewModel = viewModel()
) {
    val voices by viewModel.voices.collectAsState()
    val selectedVoiceId by viewModel.selectedVoiceId.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val downloadingVoiceId by viewModel.downloadingVoiceId.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Voice Models", fontWeight = FontWeight.Bold)
                        Text(
                            "Download voices for offline TTS",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Purple.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, tint = Purple)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Voice models are powered by Piper TTS and run entirely on your device. " +
                            "Download a model to get started with natural-sounding text-to-speech.",
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            items(voices) { voice ->
                VoiceCard(
                    voice = voice,
                    isSelected = voice.id == selectedVoiceId,
                    isDownloading = voice.id == downloadingVoiceId,
                    downloadProgress = if (voice.id == downloadingVoiceId) downloadProgress else null,
                    onDownload = { viewModel.downloadVoice(voice) },
                    onSelect = { viewModel.selectVoice(voice.id) },
                    onDelete = { viewModel.deleteVoice(voice) }
                )
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun VoiceCard(
    voice: VoiceModel,
    isSelected: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Purple.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Voice icon
                Icon(
                    if (voice.isDownloaded) Icons.Filled.RecordVoiceOver else Icons.Outlined.RecordVoiceOver,
                    null,
                    tint = if (isSelected) Purple else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(voice.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Badge(containerColor = Purple) {
                                Text("Active", color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row {
                        Text(
                            voice.language,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            " · ${voice.sizeInMb}MB",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            " · ${voice.quality.name.lowercase().replaceFirstChar { it.uppercase() }}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Action button
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = Purple,
                        strokeWidth = 3.dp
                    )
                } else if (voice.isDownloaded) {
                    Row {
                        if (!isSelected) {
                            FilledTonalButton(
                                onClick = onSelect,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Purple.copy(alpha = 0.15f)
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("Use", color = Purple, fontSize = 13.sp)
                            }
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Outlined.Delete, "Delete",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = onDownload,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Purple,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Download")
                    }
                }
            }

            // Download progress bar
            if (isDownloading && downloadProgress != null) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Purple,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${(downloadProgress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
