package com.bbcl.snapsave.ui

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.bbcl.snapsave.R
import com.bbcl.snapsave.download.DownloadItem
import com.bbcl.snapsave.download.DownloadService
import com.bbcl.snapsave.download.Downloads
import com.bbcl.snapsave.download.Engine
import com.bbcl.snapsave.download.Settings
import com.bbcl.snapsave.download.Status
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(sharedUrl: String? = null, onSharedHandled: () -> Unit = {}) {
    val items by Downloads.items.collectAsStateWithLifecycle()
    val engine by Downloads.engine.collectAsStateWithLifecycle()
    val settings by Downloads.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var link by rememberSaveable { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<DownloadItem?>(null) }
    /** Link whose formats are being shown in the sheet. */
    var picking by remember { mutableStateOf<String?>(null) }
    /** yt-dlp version the user already answered the update prompt for, this time the app is open. */
    var updateAsked by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(sharedUrl) {
        if (sharedUrl != null) {
            showSettings = false
            picking = sharedUrl
            onSharedHandled()
        }
    }

    val active = items.filter { it.status != Status.COMPLETED }
    val done = items.filter { it.status == Status.COMPLETED }
    val shown = if (tab == 0) active else done

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    fun launch(intent: Intent?) {
        if (intent == null) return
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast("No app found to open this file")
        }
    }

    fun submit() {
        val url = Downloads.extractUrl(link)
        if (url == null) toast("That doesn't look like a video link") else picking = url
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            item(key = "header") {
                Header(
                    engine = engine,
                    onSettings = { showSettings = true },
                ) {
                    LinkCard(
                        link = link,
                        onLink = { link = it },
                        engine = engine,
                        onPaste = { readClipboard(context)?.let { link = Downloads.extractUrl(it) ?: it } },
                        onSubmit = ::submit,
                    )
                }
            }
            item(key = "tabs") {
                Tabs(tab, active.size, done.size) { tab = it }
            }
            if (tab == 1 && done.isNotEmpty()) {
                item(key = "clear") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Saved in Download/SnapSave",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = Downloads::clearFinished) { Text("Clear list") }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") { EmptyState(tab) }
            }
            items(shown, key = { it.id }) { item ->
                DownloadCard(
                    item = item,
                    onPause = { Downloads.pause(item.id) },
                    onResume = { Downloads.resume(item.id) },
                    onCancel = { Downloads.cancel(item.id) },
                    onRemove = { if (item.status == Status.COMPLETED) toDelete = item else Downloads.remove(item.id, false) },
                    onOpen = { launch(Downloads.openIntent(item)) },
                    onShare = { launch(Downloads.shareIntent(item)) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SettingsSheet(settings, engine)
        }
    }

    picking?.let { url ->
        FormatSheet(
            url = url,
            onPick = { probe, option ->
                picking = null
                when (Downloads.add(probe, option)) {
                    Downloads.AddResult.Added -> {
                        link = ""
                        tab = 0
                        toast("Downloading ${option.pick.label}")
                    }
                    Downloads.AddResult.Duplicate -> toast("This video is already downloading")
                }
            },
            onDismiss = { picking = null },
        )
    }

    if (engine.state == Engine.State.READY && engine.updateAvailable && !engine.updatePending &&
        updateAsked != engine.latest && picking == null
    ) {
        AlertDialog(
            onDismissRequest = { updateAsked = engine.latest },
            // Only the buttons answer it; a stray tap outside shouldn't.
            properties = DialogProperties(dismissOnClickOutside = false),
            icon ={ Icon(Icons.Filled.Refresh, contentDescription = null) },
            title = { Text("Update the download engine?") },
            text = {
                Text(
                    "yt-dlp ${engine.latest} is out" + (engine.version?.let { " (you have $it)" } ?: "") +
                        ". Sites like YouTube and Instagram change often; updating keeps downloads working." +
                        if (items.any { it.status.isRunning }) "\n\nIt installs as soon as the current downloads finish." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { updateAsked = engine.latest; Downloads.updateEngine() }) { Text("Update now") }
            },
            dismissButton = {
                TextButton(onClick = { updateAsked = engine.latest }) { Text("Later") }
            },
        )
    }

    toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this video?") },
            text = { Text(item.displayTitle, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { Downloads.remove(item.id, deleteFile = true); toDelete = null }) {
                    Text("Delete file", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { Downloads.remove(item.id, deleteFile = false); toDelete = null }) {
                    Text("Only remove from list")
                }
            },
        )
    }
}

private fun readClipboard(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

// ---- header & link card ------------------------------------------------------------------------

@Composable
private fun Header(engine: Engine, onSettings: () -> Unit, card: @Composable () -> Unit) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(top + 190.dp)
                .clip(RoundedCornerShape(bottomStart = 36.dp, bottomEnd = 36.dp))
                .background(BrandGradient)
                .drawBehind {
                    // Soft decorative bubbles.
                    drawCircle(Color.White.copy(alpha = 0.10f), radius = size.width * 0.38f, center = Offset(size.width * 0.92f, size.height * 0.12f))
                    drawCircle(Color.White.copy(alpha = 0.07f), radius = size.width * 0.22f, center = Offset(size.width * 0.05f, size.height * 0.95f))
                }
        )
        Column(Modifier.padding(top = top)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Logo(44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SnapSave", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    EngineBadge(engine)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }
            Spacer(Modifier.height(22.dp))
            card()
        }
    }
}

@Composable
fun Logo(size: Dp) {
    Box(
        Modifier
            .size(size)
            .shadow(6.dp, RoundedCornerShape(size * 0.3f))
            .clip(RoundedCornerShape(size * 0.3f))
            .background(BrandGradient),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_fg),
            contentDescription = null,
            modifier = Modifier.requiredSize(size * 1.5f),
        )
    }
}

@Composable
private fun EngineBadge(engine: Engine) {
    val (dot, text) = when (engine.state) {
        Engine.State.STARTING -> Color(0xFFFFD166) to "Preparing engine…"
        Engine.State.UPDATING -> Color(0xFFFFD166) to "Updating engine…"
        Engine.State.READY -> Color(0xFF7CF5B5) to "Ready · fast & private"
        Engine.State.FAILED -> Color(0xFFFF9AA2) to "Engine error"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LinkCard(
    link: String,
    onLink: (String) -> Unit,
    engine: Engine,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Download any video", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "YouTube, Facebook, Instagram, X, TikTok and 1000+ sites",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = link,
                onValueChange = onLink,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste video link here") },
                leadingIcon = { Icon(AppIcons.Link, contentDescription = null, tint = colors.primary) },
                trailingIcon = {
                    if (link.isEmpty()) {
                        TextButton(onClick = onPaste) {
                            Icon(AppIcons.Paste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Paste")
                        }
                    } else {
                        IconButton(onClick = { onLink("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = colors.surfaceVariant,
                    focusedContainerColor = colors.surfaceVariant,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = colors.primary,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            )
            Spacer(Modifier.height(16.dp))
            GradientButton(
                enabled = link.isNotBlank() && (engine.state == Engine.State.READY || engine.state == Engine.State.UPDATING),
                loading = engine.state == Engine.State.STARTING,
                onClick = onSubmit,
            )
        }
    }
}

@Composable
private fun GradientButton(enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) BrandGradientH else SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest)
    val fg = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Preparing engine…", color = fg, fontWeight = FontWeight.SemiBold)
            } else {
                Icon(AppIcons.Download, contentDescription = null, tint = fg)
                Spacer(Modifier.width(8.dp))
                Text("Download", color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

// ---- list ----------------------------------------------------------------------------------------

@Composable
private fun Tabs(selected: Int, activeCount: Int, doneCount: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
    ) {
        listOf("Downloading" to activeCount, "Completed" to doneCount).forEachIndexed { i, (label, count) ->
            val on = i == selected
            Surface(
                onClick = { onSelect(i) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = if (on) MaterialTheme.colorScheme.surface else Color.Transparent,
                shadowElevation = if (on) 2.dp else 0.dp,
            ) {
                Row(
                    Modifier.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (count > 0) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (on) BrandGradientH else SolidColor(MaterialTheme.colorScheme.outline))
                                .padding(horizontal = 7.dp, vertical = 1.dp),
                        ) {
                            Text("$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(tab: Int) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (tab == 0) AppIcons.Download else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (tab == 0) "No active downloads" else "Nothing downloaded yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (tab == 0) "In YouTube or Facebook tap Share → SnapSave and pick a format.\nShare as many videos as you like, they download together."
            else "Finished videos show up here. Tap one to play it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val completed = item.status == Status.COMPLETED
    Card(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp).animateContentSize()) {
            Row {
                Thumbnail(
                    item,
                    Modifier
                        .width(124.dp)
                        .aspectRatio(16f / 9f)
                        .then(if (completed) Modifier.clickable(onClick = onOpen) else Modifier),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title ?: if (item.status == Status.FETCHING || item.status == Status.QUEUED) "Loading video…" else item.url,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            item.site,
                            item.pick.label,
                            if (completed && item.fileBytes > 0) Downloads.humanBytes(item.fileBytes) else item.size,
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusPill(item)
                }
            }

            if (!completed) {
                Spacer(Modifier.height(12.dp))
                Progress(item)
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(10.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (completed) "Download/SnapSave" else DownloadService.statusLine(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == Status.FAILED) colors.error else colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                when (item.status) {
                    Status.QUEUED, Status.FETCHING, Status.DOWNLOADING -> {
                        Action(AppIcons.Pause, "Pause", onPause)
                        Action(Icons.Filled.Close, "Cancel", onCancel, danger = true)
                    }
                    Status.PROCESSING -> Action(Icons.Filled.Close, "Cancel", onCancel, danger = true)
                    Status.PAUSED -> {
                        Action(Icons.Filled.PlayArrow, "Resume", onResume, primary = true)
                        Action(Icons.Filled.Close, "Cancel", onCancel, danger = true)
                    }
                    Status.FAILED, Status.CANCELLED -> {
                        Action(Icons.Filled.Refresh, "Retry", onResume, primary = true)
                        Action(Icons.Filled.Delete, "Remove", onRemove)
                    }
                    Status.COMPLETED -> {
                        Action(Icons.Filled.PlayArrow, "Play", onOpen, primary = true)
                        Action(Icons.Filled.Share, "Share", onShare)
                        Action(Icons.Filled.Delete, "Delete", onRemove)
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(item: DownloadItem, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(BrandGradient), contentAlignment = Alignment.Center) {
        Icon(
            if (item.pick.audioOnly) AppIcons.Music else AppIcons.Video,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(28.dp),
        )
        if (item.thumbnail != null) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (item.status == Status.COMPLETED) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White)
            }
        }
        item.durationSec?.let { d ->
            Text(
                DownloadService.formatEta(d.toLong()),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun StatusPill(item: DownloadItem) {
    val colors = MaterialTheme.colorScheme
    val (label, tint) = when (item.status) {
        Status.QUEUED -> "Queued" to colors.onSurfaceVariant
        Status.FETCHING -> "Starting" to colors.primary
        Status.DOWNLOADING -> "Downloading" to colors.primary
        Status.PROCESSING -> "Finishing" to colors.primary
        Status.PAUSED -> "Paused" to Color(0xFFE8A317)
        Status.COMPLETED -> "Saved" to SuccessGreen
        Status.FAILED -> "Failed" to colors.error
        Status.CANCELLED -> "Cancelled" to colors.onSurfaceVariant
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (item.status) {
            Status.COMPLETED -> Icon(Icons.Filled.CheckCircle, null, tint = tint, modifier = Modifier.size(12.dp))
            Status.FAILED -> Icon(Icons.Filled.Warning, null, tint = tint, modifier = Modifier.size(12.dp))
            Status.DOWNLOADING -> Icon(AppIcons.Bolt, null, tint = tint, modifier = Modifier.size(12.dp))
            else -> Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        }
        Spacer(Modifier.width(4.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Progress(item: DownloadItem) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    if (item.status == Status.FETCHING || item.status == Status.PROCESSING ||
        (item.status == Status.DOWNLOADING && item.progress <= 0f)
    ) {
        LinearProgressIndicator(
            Modifier.fillMaxWidth().height(8.dp).clip(shape),
            color = colors.primary,
            trackColor = colors.surfaceVariant,
        )
        return
    }
    val p by animateFloatAsState(item.progress.coerceIn(0f, 1f), label = "progress")
    val fill = when (item.status) {
        Status.DOWNLOADING -> BrandGradientH
        Status.FAILED -> SolidColor(colors.error.copy(alpha = 0.6f))
        else -> SolidColor(colors.onSurfaceVariant.copy(alpha = 0.45f))
    }
    Box(Modifier.fillMaxWidth().height(8.dp).clip(shape).background(colors.surfaceVariant)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(shape).background(fill))
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit, primary: Boolean = false, danger: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.padding(start = 6.dp).size(38.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = when {
                primary -> colors.primary
                danger -> colors.error.copy(alpha = 0.12f)
                else -> colors.surfaceVariant
            },
            contentColor = when {
                primary -> colors.onPrimary
                danger -> colors.error
                else -> colors.onSurface
            },
        ),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
    }
}

// ---- settings ------------------------------------------------------------------------------------

@Composable
private fun SettingsSheet(settings: Settings, engine: Engine) {
    val context = LocalContext.current
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(40.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("SnapSave v${appVersion ?: ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SectionTitle("Downloads at the same time", "More is faster on good Wi-Fi")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = settings.parallel.toFloat(),
                onValueChange = { Downloads.setParallel(it.toInt()) },
                valueRange = 1f..5f,
                steps = 3,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text("${settings.parallel}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        SectionTitle("Download engine", "If a site stops working, update the engine")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("yt-dlp ${engine.version ?: ""}", fontWeight = FontWeight.SemiBold)
                val note = when {
                    engine.updatePending -> "Updates when the current downloads finish"
                    engine.state == Engine.State.READY && engine.updateAvailable -> "New version ${engine.latest} available"
                    else -> engine.message
                }
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalButton(
                onClick = Downloads::updateEngine,
                enabled = engine.state == Engine.State.READY && !engine.updatePending,
            ) {
                if (engine.state == Engine.State.UPDATING || engine.updatePending) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (engine.updatePending) "Waiting" else "Updating")
                } else {
                    Text("Update")
                }
            }
        }

        BackgroundRow()

        SectionTitle("How to use", null)
        Text(
            "1. Open a video in YouTube, Facebook or Instagram\n" +
                "2. Tap Share → SnapSave, choose Video or Audio, then a quality\n" +
                "3. It starts downloading. Go back and share the next one\n" +
                "Videos are saved in Download/SnapSave",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 22.sp,
        )
    }
}

/**
 * Battery optimisation (very aggressive on Realme, Oppo, Vivo, Xiaomi) throttles or
 * freezes downloads once the screen turns off; offer to turn it off for SnapSave.
 */
@Composable
private fun BackgroundRow() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var allowed by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleResumeEffect(Unit) {
        allowed = power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    if (allowed) return

    SectionTitle("Background downloads", "Battery saver can pause downloads when the screen is off")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Let SnapSave keep downloading in the background",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        FilledTonalButton(onClick = {
            val ask = Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            try {
                context.startActivity(ask)
            } catch (_: ActivityNotFoundException) {
                runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        }) { Text("Allow") }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String?) {
    Spacer(Modifier.height(22.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    subtitle?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(10.dp))
}
