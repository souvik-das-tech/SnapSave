package com.bbcl.snapsave.ui

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.bbcl.snapsave.download.DownloadService
import com.bbcl.snapsave.download.Downloads
import com.bbcl.snapsave.download.Option
import com.bbcl.snapsave.download.Probe
import kotlin.coroutines.cancellation.CancellationException

private sealed interface Load {
    data object Busy : Load
    data class Failed(val message: String) : Load
    data class Done(val probe: Probe) : Load
}

/**
 * Reads the link's real formats first, then lists only the qualities this video has,
 * grouped into Video and Audio. Tap one, then Download. Used for shared and pasted links.
 *
 * A full-screen page rather than a bottom sheet: scrolling the list or a stray touch
 * must never close it. Only the close button and the Back key do.
 */
@Composable
fun FormatSheet(url: String, onPick: (Probe, Option) -> Unit, onDismiss: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var load by remember { mutableStateOf<Load>(Load.Busy) }
    var selected by remember { mutableStateOf<Option?>(null) }
    LaunchedEffect(url, attempt) {
        load = Load.Busy
        load = try {
            Downloads.probe(url).let { probe ->
                // Start on the quality picked last time, when this video has it.
                val last = Downloads.settings.value.lastPick
                selected = (probe.video + probe.audio).firstOrNull { it.pick.key == last }
                Load.Done(probe)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Load.Failed(Downloads.friendlyError(e))
        }
    }

    Dialog(
        onDismissRequest = onDismiss, // Back key; outside taps are off below
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                TopBar(url, onDismiss)
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    when (val l = load) {
                        Load.Busy -> Busy()
                        is Load.Failed -> Failed(l.message, onRetry = { attempt++ })
                        is Load.Done -> Chooser(l.probe, selected, onSelect = { selected = it })
                    }
                }
                (load as? Load.Done)?.let { done ->
                    DownloadBar(selected, onClick = { selected?.let { onPick(done.probe, it) } })
                }
            }
        }
    }
}

@Composable
private fun TopBar(url: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Logo(44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Save with SnapSave", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                siteName(url) ?: url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close")
        }
    }
}

@Composable
private fun Busy() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text("Checking the video…", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Finding which qualities it really has",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Failed(message: String, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(colors.error.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.error)
        }
        Spacer(Modifier.height(14.dp))
        Text("Can't download this link", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun Chooser(probe: Probe, selected: Option?, onSelect: (Option) -> Unit) {
    val settings by Downloads.settings.collectAsStateWithLifecycle()
    Spacer(Modifier.height(4.dp))
    Preview(probe)
    Section(AppIcons.Video, "Video", probe.video, selected, settings.lastPick, onSelect)
    Section(AppIcons.Music, "Audio", probe.audio, selected, settings.lastPick, onSelect)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Section(
    icon: ImageVector,
    title: String,
    options: List<Option>,
    selected: Option?,
    lastPick: String?,
    onSelect: (Option) -> Unit,
) {
    if (options.isEmpty()) return
    Spacer(Modifier.height(22.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            OptionRow(
                option,
                selected = option == selected,
                last = option.pick.key == lastPick,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun Preview(probe: Probe) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(BrandGradient),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Video, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(24.dp))
            probe.thumbnail?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                probe.title ?: probe.url,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    probe.site ?: siteName(probe.url),
                    probe.uploader,
                    probe.durationSec?.let { DownloadService.formatEta(it.toLong()) },
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OptionRow(option: Option, selected: Boolean, last: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) colors.primaryContainer else colors.surfaceVariant,
        border = if (selected) BorderStroke(1.5.dp, colors.primary) else null,
    ) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(option.pick.label, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (last) {
                        Spacer(Modifier.width(6.dp))
                        Text("Last used", color = colors.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                Text(
                    option.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (option.bytes > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "~" + Downloads.humanBytes(option.bytes),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** Stays at the bottom; only lights up once a quality is picked. */
@Composable
private fun DownloadBar(selected: Option?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val enabled = selected != null
    Box(
        Modifier
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) BrandGradientH else SolidColor(colors.surfaceContainerHighest))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val fg = if (enabled) Color.White else colors.onSurfaceVariant
            if (selected != null) {
                Icon(AppIcons.Download, contentDescription = null, tint = fg)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                selected?.let {
                    "Download ${it.pick.label}" + if (it.bytes > 0) "  ·  ~${Downloads.humanBytes(it.bytes)}" else ""
                } ?: "Pick a quality",
                color = fg,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(BrandGradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

private fun siteName(url: String): String? {
    val host = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")?.removePrefix("m.") ?: return null
    return when {
        host.contains("youtu") -> "YouTube"
        host.contains("facebook") || host == "fb.watch" -> "Facebook"
        host.contains("instagram") -> "Instagram"
        host.contains("tiktok") -> "TikTok"
        host == "x.com" || host.contains("twitter") -> "X (Twitter)"
        else -> host
    }
}
