package com.github.gafiatulin.parakeetflow.ui.screen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.github.gafiatulin.parakeetflow.core.model.AsrModel
import com.github.gafiatulin.parakeetflow.core.model.MdContentMode
import com.github.gafiatulin.parakeetflow.core.model.ModelStatus
import com.github.gafiatulin.parakeetflow.service.DictationService
import com.github.gafiatulin.parakeetflow.ui.component.ModelStatusCard
import com.github.gafiatulin.parakeetflow.viewmodel.AppViewModel
import com.github.gafiatulin.parakeetflow.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToHistory: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToOnboarding: () -> Unit,
    appViewModel: AppViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings by settingsViewModel.settings.collectAsState()
    val asrStatus by appViewModel.asrStatus.collectAsState()
    val llmStatus by appViewModel.llmStatus.collectAsState()
    val selectedAsrModel by appViewModel.selectedAsrModel.collectAsState()

    // Re-check permissions on resume (returning from system settings)
    var refreshKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasMic = remember(refreshKey) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val hasOverlay = remember(refreshKey) { Settings.canDrawOverlays(context) }
    val hasNotif = remember(refreshKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true
    }
    val missingPermissions = remember(refreshKey) {
        buildList {
            if (!hasMic) add("Microphone")
            if (!hasOverlay) add("Overlay")
            if (!hasNotif) add("Notifications")
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshKey++
        if (granted) {
            val intent = Intent(context, DictationService::class.java).apply {
                action = DictationService.ACTION_START
            }
            context.startForegroundService(intent)
        } else {
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    val exportFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            settingsViewModel.setExportFolderUri(it.toString())
        }
    }

    val audioFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            settingsViewModel.setAudioFolderUri(it.toString())
        }
    }

    fun startService() {
        if (!hasMic) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!hasOverlay) {
            Toast.makeText(context, "Overlay permission required", Toast.LENGTH_SHORT).show()
            onNavigateToPermissions()
            return
        }
        val intent = Intent(context, DictationService::class.java).apply {
            action = DictationService.ACTION_START
        }
        context.startForegroundService(intent)
    }

    fun reloadService() {
        context.startService(Intent(context, DictationService::class.java).apply {
            action = DictationService.ACTION_RELOAD
        })
    }

    fun refreshBubble() {
        context.startService(Intent(context, DictationService::class.java).apply {
            action = DictationService.ACTION_REFRESH_BUBBLE
        })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ParakeetFlow") },
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "History")
                    }
                    IconButton(onClick = onNavigateToPermissions) {
                        Icon(Icons.Default.Security, contentDescription = "Permissions")
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
            // Permissions banner
            if (missingPermissions.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Missing: ${missingPermissions.joinToString(", ")}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        FilledTonalButton(onClick = onNavigateToPermissions) { Text("Fix") }
                    }
                }
            }

            // Service control
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Dictation Service", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    val asrReady = asrStatus is ModelStatus.Ready
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { startService() },
                            enabled = asrReady
                        ) { Text("Start") }
                        OutlinedButton(onClick = {
                            val intent = Intent(context, DictationService::class.java).apply {
                                action = DictationService.ACTION_STOP
                            }
                            context.startService(intent)
                        }) { Text("Stop") }
                    }
                }
            }

            // Models
            Text(
                "Models",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Speech Recognition", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${selectedAsrModel.description} (${selectedAsrModel.sizeLabel})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        AsrModel.entries.forEachIndexed { index, model ->
                            SegmentedButton(
                                selected = selectedAsrModel == model,
                                onClick = {
                                    appViewModel.selectAsrModel(model)
                                    reloadService()
                                },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = AsrModel.entries.size
                                )
                            ) {
                                Text(model.displayName.removePrefix("Parakeet TDT 0.6B "))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    when (val status = asrStatus) {
                        is ModelStatus.NotDownloaded -> {
                            Button(onClick = { appViewModel.downloadAsrModel() }) { Text("Download") }
                        }
                        is ModelStatus.Downloading -> {
                            LinearProgressIndicator(
                                progress = { status.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${(status.progress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                TextButton(onClick = { appViewModel.cancelAsrDownload() }) { Text("Cancel") }
                            }
                        }
                        is ModelStatus.Ready -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("\u2713 Ready", color = MaterialTheme.colorScheme.primary)
                                TextButton(onClick = { appViewModel.deleteAsrModel() }) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        is ModelStatus.Error -> {
                            Text(
                                "Error: ${status.message}",
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = { appViewModel.downloadAsrModel() }) { Text("Retry") }
                        }
                    }
                }
            }

            ModelStatusCard(
                title = "Qwen3 0.6B",
                description = "Text cleanup model (~614 MB)",
                status = llmStatus,
                onDownload = { appViewModel.downloadLlmModel() },
                onCancel = { appViewModel.cancelLlmDownload() },
                onDelete = { appViewModel.deleteLlmModel() }
            )

            // Settings toggles
            Text(
                "Processing",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            val llmReady = llmStatus is ModelStatus.Ready
            ListItem(
                headlineContent = { Text("LLM Post-Processing") },
                supportingContent = {
                    Text(
                        if (llmReady) "Clean up grammar and punctuation with AI"
                        else "Download LLM model first"
                    )
                },
                trailingContent = {
                    Switch(
                        checked = settings.llmEnabled && llmReady,
                        enabled = llmReady,
                        onCheckedChange = {
                            settingsViewModel.setLlmEnabled(it)
                            reloadService()
                        }
                    )
                }
            )
            ListItem(
                headlineContent = { Text("LLM on GPU") },
                supportingContent = { Text("Faster but uses more battery") },
                trailingContent = {
                    Switch(
                        checked = settings.llmGpu,
                        enabled = settings.llmEnabled && llmReady,
                        onCheckedChange = {
                            settingsViewModel.setLlmGpu(it)
                            reloadService()
                        }
                    )
                }
            )
            ListItem(
                headlineContent = { Text("Filler Word Filter") },
                supportingContent = { Text("Remove um, uh, like, etc.") },
                trailingContent = {
                    Switch(
                        checked = settings.fillerFilterEnabled,
                        onCheckedChange = {
                            settingsViewModel.setFillerFilterEnabled(it)
                            reloadService()
                        }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "Bubble",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            ListItem(
                headlineContent = { Text("Lingering Bubble") },
                supportingContent = { Text("Keep bubble visible when no text field is focused") },
                trailingContent = {
                    Switch(
                        checked = settings.lingeringBubble,
                        onCheckedChange = { settingsViewModel.setLingeringBubble(it) }
                    )
                }
            )

            // Bubble size
            var sizeSlider by remember(settings.bubbleSizeDp) {
                mutableFloatStateOf(settings.bubbleSizeDp.toFloat())
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Bubble size: ${sizeSlider.toInt()} dp", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = sizeSlider,
                    onValueChange = { sizeSlider = it },
                    onValueChangeFinished = {
                        settingsViewModel.setBubbleSizeDp(sizeSlider.toInt())
                        refreshBubble()
                    },
                    valueRange = 48f..96f
                )
            }

            // Bubble opacity
            var opacitySlider by remember(settings.bubbleOpacity) {
                mutableFloatStateOf(settings.bubbleOpacity)
            }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Bubble opacity: ${(opacitySlider * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = opacitySlider,
                    onValueChange = { opacitySlider = it },
                    onValueChangeFinished = {
                        settingsViewModel.setBubbleOpacity(opacitySlider)
                        refreshBubble()
                    },
                    valueRange = 0.3f..1f
                )
            }

            // Bubble color
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Bubble color", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BUBBLE_COLOR_PRESETS.forEach { argb ->
                        val selected = settings.bubbleColor == argb
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(argb), CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                                .clickable {
                                    settingsViewModel.setBubbleColor(argb)
                                    refreshBubble()
                                }
                        )
                    }
                }
            }

            ListItem(
                headlineContent = { Text("Controls beside bubble") },
                supportingContent = { Text("Show pause/cancel left or right of the bubble (by edge) instead of below") },
                trailingContent = {
                    Switch(
                        checked = settings.bubbleControlsBeside,
                        onCheckedChange = {
                            settingsViewModel.setBubbleControlsBeside(it)
                            refreshBubble()
                        }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "Feedback",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            ListItem(
                headlineContent = { Text("Haptic Feedback") },
                trailingContent = {
                    Switch(
                        checked = settings.hapticFeedback,
                        onCheckedChange = { settingsViewModel.setHapticFeedback(it) }
                    )
                }
            )
            ListItem(
                headlineContent = { Text("Audio Feedback") },
                trailingContent = {
                    Switch(
                        checked = settings.audioFeedback,
                        onCheckedChange = { settingsViewModel.setAudioFeedback(it) }
                    )
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "Transcript Export",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            ListItem(
                headlineContent = { Text("Save transcripts as Markdown") },
                supportingContent = { Text("Write a .md file to a folder you choose") },
                trailingContent = {
                    Switch(
                        checked = settings.mdExportEnabled,
                        onCheckedChange = { settingsViewModel.setMdExportEnabled(it) }
                    )
                }
            )

            ListItem(
                headlineContent = { Text("Export folder") },
                supportingContent = { Text(folderLabel(settings.exportFolderUri)) },
                trailingContent = {
                    OutlinedButton(onClick = { exportFolderLauncher.launch(null) }) {
                        Text("Choose")
                    }
                }
            )

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Markdown content", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val modes = MdContentMode.entries
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = settings.mdContentMode == mode,
                            onClick = { settingsViewModel.setMdContentMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size)
                        ) {
                            Text(
                                when (mode) {
                                    MdContentMode.FINAL -> "Final"
                                    MdContentMode.RAW -> "Raw ASR"
                                    MdContentMode.FULL -> "Full"
                                }
                            )
                        }
                    }
                }
            }

            ListItem(
                headlineContent = { Text("Include metadata header") },
                supportingContent = { Text("YAML frontmatter (date, app, duration) for Obsidian") },
                trailingContent = {
                    Switch(
                        checked = settings.mdFrontmatter,
                        onCheckedChange = { settingsViewModel.setMdFrontmatter(it) }
                    )
                }
            )

            ListItem(
                headlineContent = { Text("Also save audio (WAV)") },
                supportingContent = { Text("Store the raw recording alongside the transcript") },
                trailingContent = {
                    Switch(
                        checked = settings.audioExportEnabled,
                        onCheckedChange = { settingsViewModel.setAudioExportEnabled(it) }
                    )
                }
            )

            if (settings.audioExportEnabled) {
                ListItem(
                    headlineContent = { Text("Audio folder") },
                    supportingContent = {
                        Text(
                            if (settings.audioFolderUri.isBlank()) "Same as export folder"
                            else folderLabel(settings.audioFolderUri)
                        )
                    },
                    trailingContent = {
                        OutlinedButton(onClick = { audioFolderLauncher.launch(null) }) {
                            Text("Choose")
                        }
                    }
                )
            }
        }
    }
}

/** Preset idle-bubble colors (packed ARGB), offered as swatches in Settings. */
private val BUBBLE_COLOR_PRESETS = listOf(
    0xFF4CAF50.toInt(), // green (default)
    0xFF2196F3.toInt(), // blue
    0xFF9C27B0.toInt(), // purple
    0xFFF44336.toInt(), // red
    0xFFFF9800.toInt(), // orange
    0xFF009688.toInt(), // teal
    0xFF607D8B.toInt(), // blue gray
    0xFF000000.toInt()  // black
)

/** Human-readable label for a persisted SAF tree URI, e.g. "Documents/ParakeetFlow". */
private fun folderLabel(uriString: String): String {
    if (uriString.isBlank()) return "No folder selected"
    val lastSegment = Uri.parse(uriString).lastPathSegment ?: return "Selected folder"
    val decoded = Uri.decode(lastSegment)
    return decoded.substringAfter(':', decoded)
}
