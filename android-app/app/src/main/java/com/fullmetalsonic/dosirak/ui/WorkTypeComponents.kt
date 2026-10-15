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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fullmetalsonic.dosirak.domain.WorkTypeDefinition
import com.fullmetalsonic.dosirak.domain.WorkTypes

internal val WorkColorPalette = listOf(0xFF00659EL, 0xFF42474EL, 0xFFA83A50L, 0xFF35754AL,
    0xFF6B4E9BL, 0xFF00796BL, 0xFF925B00L, 0xFF56606BL)

internal fun workTextColor(color: Color): Color = if (color.luminance() > 0.179f) Color.Black else Color.White

internal fun workAccentColor(color: Color, background: Color, fallback: Color): Color {
    val light = maxOf(color.luminance(), background.luminance())
    val dark = minOf(color.luminance(), background.luminance())
    return if ((light + 0.05f) / (dark + 0.05f) >= 4.5f) color else fallback
}

@Composable
internal fun WorkTypeBadge(type: WorkTypeDefinition) {
    val off = type.id == WorkTypes.OFF
    val color = Color(type.color)
    val characters = type.calendarLabel.codePoints().toArray().toList()
    val label = characters.chunked(2).joinToString("\n") { points -> buildString { points.forEach { appendCodePoint(it) } } }
    val labelFontSize = when (characters.size) { 1 -> 12.sp; 2 -> 11.sp; else -> 8.sp }
    val labelLineHeight = when (characters.size) { 1 -> 16.sp; 2 -> 14.sp; else -> 10.sp }
    val badge = Modifier.size(40.dp).testTag("calendar_work_${type.id}").semantics { contentDescription = type.name }
    Box(if (off) badge else badge.clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(label,
            modifier = Modifier.testTag("calendar_work_text_${type.id}"),
            color = if (off) workAccentColor(color, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant) else workTextColor(color),
            style = if (off && characters.size == 1) MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.sp)
                else MaterialTheme.typography.labelMedium.copy(fontSize = labelFontSize, lineHeight = labelLineHeight, letterSpacing = 0.sp),
            maxLines = if (characters.size <= 2) 1 else 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
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
