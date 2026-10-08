package dev.bakrlabs.flux

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.graphics.drawable.toBitmap

@Composable
fun UpdateDialog(state: FluxState, info: UpdateInfo) {
    AlertDialog(
        onDismissRequest = { if (!state.updating) state.update = null },
        containerColor = Palette.card,
        title = { Text("Update available", color = Palette.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Build ${info.build} is ready. You have build ${state.installedBuild}.", color = Palette.muted, fontSize = 14.sp)
                if (state.updating) {
                    ProgressBar(state.updateProgress)
                    Text("Downloading...", color = Palette.muted, fontSize = 13.sp)
                }
                if (state.updateStatus.isNotEmpty()) {
                    Text(state.updateStatus, color = Palette.danger, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { state.installUpdate() }, enabled = !state.updating) {
                Text("Update", color = Palette.accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = { state.update = null }, enabled = !state.updating) {
                Text("Later", color = Palette.muted)
            }
        },
    )
}

@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.ground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) { content() }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Palette.card)
                .clickable(onClickLabel = "Back", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Text("‹", color = Palette.text, fontSize = 26.sp) }
        Spacer(Modifier.width(12.dp))
        Text(title, color = Palette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    contentColor: Color? = null,
) {
    val shape = RoundedCornerShape(26.dp)
    val background = if (filled) Palette.accent else Palette.card
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(background.copy(alpha = if (enabled) 1f else 0.4f))
            .then(if (filled) Modifier else Modifier.border(1.dp, Palette.border, shape))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = contentColor ?: if (filled) Palette.onAccent else Palette.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BigCard(title: String, glyph: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .height(128.dp)
            .clip(shape)
            .background(if (filled) Palette.accent else Palette.card)
            .then(if (filled) Modifier else Modifier.border(1.dp, Palette.border, shape))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(glyph, color = if (filled) Palette.onAccent else Palette.accent, fontSize = 30.sp)
        Text(
            title,
            color = if (filled) Palette.onAccent else Palette.text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit = {}) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Palette.border else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) Palette.text else Palette.muted, fontSize = 14.sp) }
}

@Composable
private fun SectionHeader(label: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Palette.muted, fontSize = 13.sp)
        if (action != null) {
            Text(
                action,
                color = Palette.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun DeviceRow(name: String, host: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(Palette.card)
            .border(1.dp, if (selected) Palette.accent else Palette.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (selected) Palette.accent else Palette.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.accent))
        }
        Spacer(Modifier.width(12.dp))
        Text(name, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(host, color = Palette.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun AppRow(app: AppEntry, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(app.packageName) {
        runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(shape)
            .background(Palette.card)
            .border(1.dp, if (selected) Palette.accent else Palette.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Palette.border))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (app.apks.size > 1) {
                Text("${app.apks.size} parts, sent as .apks", color = Palette.muted, fontSize = 12.sp)
            }
        }
        if (selected) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(Palette.accent), contentAlignment = Alignment.Center) {
                Text("✓", color = Palette.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FileRow(name: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.card)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Palette.border),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "FILE" },
                color = Palette.muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(name, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(48.dp).clickable(onClickLabel = "Remove $name", onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Text("✕", color = Palette.muted, fontSize = 16.sp) }
    }
}

@Composable
private fun ReceivedRow(name: String, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.card)
            .clickable(onClickLabel = "Open $name", onClick = onOpen)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            if (name.endsWith(".apk", ignoreCase = true) || name.endsWith(".apks", ignoreCase = true)) "Install" else "Open",
            color = Palette.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Palette.track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Palette.accent),
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, color = Palette.muted, fontSize = 12.sp)
        Text(value, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Row2(left: String, right: String, rightColor: Color = Palette.muted) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.card)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(left, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(right, color = rightColor, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
fun HomeScreen(state: FluxState) {
    Page {
        Column {
            Text("Flux", color = Palette.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("by BakrLabs  ·  build ${state.installedBuild}", color = Palette.muted, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigCard("Send", "↗", true, Modifier.weight(1f)) { state.screen = Screen.Send }
            BigCard("Receive", "↙", false, Modifier.weight(1f)) { state.startReceiving() }
        }
        if (state.sending) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Palette.card)
                    .clickable { state.screen = Screen.Progress }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Transfer in progress", color = Palette.text, fontSize = 15.sp)
                Text("View", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Palette.card)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab("Nearby", true, Modifier.weight(1f))
            Tab("Online", false, Modifier.weight(1f))
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.card)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.accent))
                Spacer(Modifier.width(8.dp))
                Text("Devices appear when you open Send", color = Palette.text, fontSize = 14.sp)
            }
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                listOf(30.dp, 52.dp, 73.dp).forEach {
                    drawCircle(Palette.border, radius = it.toPx(), center = center, style = Stroke(width = 1.dp.toPx()))
                }
                drawCircle(Palette.accent, radius = 9.dp.toPx(), center = center)
            }
        }
        if (state.recent.isNotEmpty()) {
            SectionHeader("Recent", "See all") { state.screen = Screen.History }
            state.recent.take(3).forEach { Text(it, color = Palette.text, fontSize = 15.sp) }
        }
        Spacer(Modifier.weight(1f))
        Text(
            state.updateStatus.ifEmpty { "Check for updates" },
            color = Palette.muted,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { state.checkNow() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun SendScreen(state: FluxState, pick: () -> Unit) {
    LaunchedEffect(Unit) { state.discover() }
    LaunchedEffect(state.tab) { if (state.tab == 1 && state.apps.isEmpty()) state.loadApps() }
    val chosen = state.devices.firstOrNull { "${it.host}:${it.port}" == state.target }
    Page {
        TopBar("Send") { state.screen = Screen.Home }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Palette.card)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Tab("Files", state.tab == 0, Modifier.weight(1f)) { state.tab = 0 }
            Tab("Apps", state.tab == 1, Modifier.weight(1f)) { state.tab = 1 }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Send to")
            if (state.devices.isEmpty()) {
                Text("Looking for devices. Open Receive on the other phone.", color = Palette.muted, fontSize = 14.sp)
            }
            state.devices.take(3).forEach { device ->
                val address = "${device.host}:${device.port}"
                DeviceRow(device.name, device.host, address == state.target) { state.target = address }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.tab == 0) {
                SectionHeader("Files (${state.picked.size})", "+ Add", pick)
                if (state.picked.isEmpty()) {
                    Text("Nothing selected yet.", color = Palette.muted, fontSize = 14.sp)
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.picked) { file -> FileRow(file.name) { state.picked.remove(file) } }
                }
            } else {
                SectionHeader("Installed apps (${state.picked.count { it.app != null }} selected)")
                if (state.apps.isEmpty()) {
                    Text("Loading apps...", color = Palette.muted, fontSize = 14.sp)
                }
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.apps) { app -> AppRow(app, state.isSelected(app)) { state.toggleApp(app) } }
                }
            }
        }
        TextField(
            value = state.target,
            onValueChange = { state.target = it },
            singleLine = true,
            placeholder = { Text("Or type an address, e.g. 192.168.1.5:41234") },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Palette.card,
                unfocusedContainerColor = Palette.card,
                focusedTextColor = Palette.text,
                unfocusedTextColor = Palette.text,
                focusedPlaceholderColor = Palette.muted,
                unfocusedPlaceholderColor = Palette.muted,
                cursorColor = Palette.accent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                chosen?.let { "To ${it.name}" } ?: "${state.picked.size} selected",
                color = Palette.muted,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            PillButton(
                "Send",
                { state.send() },
                Modifier.width(140.dp),
                enabled = state.picked.isNotEmpty() && state.target.isNotBlank() && !state.sending,
            )
        }
    }
}

@Composable
fun ReceiveScreen(state: FluxState) {
    Page {
        TopBar("Receive") { state.stopReceiving() }
        Column(
            Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Box(
                Modifier
                    .padding(top = 24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Palette.card)
                    .border(1.dp, Palette.border, RoundedCornerShape(28.dp))
                    .padding(vertical = 56.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.addresses.firstOrNull() ?: "…",
                    color = Palette.accent,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.accent))
                Spacer(Modifier.width(8.dp))
                Text(if (state.incomingName.isEmpty()) "Waiting for sender" else "Receiving", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text("Open Send on the other phone, or type this address.", color = Palette.muted, fontSize = 14.sp)
            state.addresses.drop(1).forEach { Text("or $it", color = Palette.muted, fontSize = 14.sp) }
            if (state.incomingName.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Palette.card)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(state.incomingName, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${(state.incomingFraction * 100).toInt()}%", color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
                    }
                    ProgressBar(state.incomingFraction)
                }
            }
            if (state.received.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Received, saved in Download/Flux")
                    state.received.take(4).forEach { item -> ReceivedRow(item.name) { state.open(item) } }
                }
            }
            state.error?.let { Text(it, color = Palette.danger, fontSize = 13.sp) }
        }
        PillButton("Stop", { state.stopReceiving() }, Modifier.fillMaxWidth(), filled = false)
    }
}

@Composable
fun ProgressScreen(state: FluxState) {
    val total = state.transfers.size
    val done = state.transfers.count { it.status == "Done" }
    val fraction = state.progress
    Page {
        TopBar("Sending") { state.screen = Screen.Home }
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("to ${state.target}", color = Palette.muted, fontSize = 14.sp)
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                    val inset = 6.dp.toPx()
                    val arc = Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(Palette.track, 0f, 360f, false, topLeft = Offset(inset, inset), size = arc, style = stroke)
                    drawArc(Palette.accent, -90f, 360f * fraction, false, topLeft = Offset(inset, inset), size = arc, style = stroke)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(fraction * 100).toInt()}%", color = Palette.text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                    Text("$done of $total files", color = Palette.muted, fontSize = 13.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("Speed", state.speed.ifEmpty { "—" }, Modifier.weight(1f))
                StatCard("Time left", state.eta.ifEmpty { "—" }, Modifier.weight(1f))
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.transfers) {
                val color = when (it.status) {
                    "Done" -> Palette.accent
                    "Failed" -> Palette.danger
                    else -> Palette.muted
                }
                Row2(it.name, it.status, color)
            }
        }
        state.error?.let { Text(it, color = Palette.danger, fontSize = 13.sp) }
        if (state.sending) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (state.paused) "Resume" else "Pause", { state.togglePause() }, Modifier.weight(1f), filled = false)
                PillButton("Cancel", { state.cancelSend() }, Modifier.weight(1f), filled = false, contentColor = Palette.danger)
            }
        } else {
            PillButton("Done", { state.screen = Screen.Home }, Modifier.fillMaxWidth(), filled = false)
        }
    }
}

@Composable
fun HistoryScreen(state: FluxState) {
    val format = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Page {
        TopBar("History") { state.screen = Screen.Home }
        SectionHeader("${state.history.size} items", if (state.history.isEmpty()) null else "Clear") { state.clearHistory() }
        if (state.history.isEmpty()) {
            Text("Nothing here yet.", color = Palette.muted, fontSize = 14.sp)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.history) { entry ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Palette.card)
                        .then(if (entry.uri != null) Modifier.clickable { state.openEntry(entry) } else Modifier)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(entry.text, color = if (entry.ok) Palette.text else Palette.danger, fontSize = 15.sp)
                    Text(format.format(Date(entry.time)), color = Palette.muted, fontSize = 12.sp)
                }
            }
        }
    }
}
