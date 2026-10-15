package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fullmetalsonic.dosirak.domain.WorkTypeDefinition

internal val WorkColorPalette = listOf(0xFF00659EL, 0xFF42474EL, 0xFFA83A50L, 0xFF35754AL,
    0xFF6B4E9BL, 0xFF00796BL, 0xFF925B00L, 0xFF56606BL)

internal fun workTextColor(color: Color): Color = if (color.luminance() > 0.179f) Color.Black else Color.White

internal fun workAccentColor(color: Color, background: Color, fallback: Color): Color {
    val light = maxOf(color.luminance(), background.luminance())
    val dark = minOf(color.luminance(), background.luminance())
    return if ((light + 0.05f) / (dark + 0.05f) >= 4.5f) color else fallback
}

@Composable
internal fun WorkTypePicker(types: List<WorkTypeDefinition>, selectedId: String?, enabled: Boolean = true,
    onSelect: (WorkTypeDefinition) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val available = types.filter { !it.archived || it.id == selectedId }.sortedBy { it.sortOrder }
    val shown = available.filter { expanded || it.favorite || it.id == selectedId }
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val columns = ((maxWidth.value + 8) / (96 * fontScale + 8)).toInt().coerceIn(1, 3)
    Column {
    shown.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { type ->
                FilterChip(selected = type.id == selectedId, onClick = { onSelect(type) }, enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    label = { Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(8.dp).background(Color(type.color), CircleShape))
                        Text(type.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge)
                    } })
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (available.any { !it.favorite && it.id != selectedId }) TextButton(onClick = { expanded = !expanded }, enabled = enabled) {
        Text(if (expanded) "접기" else "더보기")
    }
    }
    }
}
