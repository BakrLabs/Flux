package dev.bakrlabs.flux

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            color = if (filled) Palette.onAccent else Palette.text,
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
private fun Tab(label: String, selected: Boolean, modifier: Modifier) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Palette.border else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) Palette.text else Palette.muted, fontSize = 14.sp) }
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
        Text(left, color = Palette.text, fontSize = 15.sp)
        Text(right, color = rightColor, fontSize = 13.sp)
    }
}

@Composable
fun HomeScreen(state: FluxState) {
    Page {
        Column {
            Text("Flux", color = Palette.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("by BakrLabs", color = Palette.muted, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigCard("Send", "↗", true, Modifier.weight(1f)) { state.screen = Screen.Send }
            BigCard("Receive", "↙", false, Modifier.weight(1f)) { state.startReceiving() }
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
                Text("Auto-discovery comes next", color = Palette.text, fontSize = 14.sp)
            }
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                listOf(30.dp, 52.dp, 73.dp).forEach {
                    drawCircle(Palette.border, radius = it.toPx(), center = center, style = Stroke(width = 1.dp.toPx()))
                }
                drawCircle(Palette.accent, radius = 9.dp.toPx(), center = center)
            }
        }
        if (state.recent.isNotEmpty()) {
            Text("Recent", color = Palette.muted, fontSize = 13.sp)
            state.recent.take(3).forEach { Text(it, color = Palette.text, fontSize = 15.sp) }
        }
    }
}

@Composable
fun SendScreen(state: FluxState, pick: () -> Unit) {
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
            Tab("Files", true, Modifier.weight(1f))
            Tab("Apps", false, Modifier.weight(1f))
        }
        PillButton("Pick files", pick, Modifier.fillMaxWidth(), filled = false)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.picked) { Row2(it.name, "Selected", Palette.accent) }
        }
        TextField(
            value = state.target,
            onValueChange = { state.target = it },
            singleLine = true,
            placeholder = { Text("Receiver address, e.g. 192.168.1.5:41234") },
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
            Text("${state.picked.size} selected", color = Palette.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            PillButton(
                "Send",
                { state.send() },
                Modifier.width(140.dp),
                enabled = state.picked.isNotEmpty() && state.target.isNotBlank(),
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
                    state.address.ifEmpty { "…" },
                    color = Palette.accent,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.accent))
                Spacer(Modifier.width(8.dp))
                Text("Waiting for sender", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text("Type this address on the sender's device.", color = Palette.muted, fontSize = 14.sp)
            state.recent.firstOrNull { it.startsWith("Received") }?.let {
                Text(it, color = Palette.accent, fontSize = 15.sp)
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
    val fraction = if (total == 0) 0f else done.toFloat() / total
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Palette.card)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("Speed", color = Palette.muted, fontSize = 12.sp)
                Text(state.speed.ifEmpty { "—" }, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
        PillButton("Done", { state.screen = Screen.Home }, Modifier.fillMaxWidth(), filled = false)
    }
}
