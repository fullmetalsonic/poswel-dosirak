package com.fullmetalsonic.dosirak.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
internal fun todaySeoul(): LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul"))
internal fun parseTime(value: String): LocalTime? = if (Regex("\\d{2}:\\d{2}:\\d{2}").matches(value)) runCatching { LocalTime.parse(value) }.getOrNull() else null

@Composable
internal fun QuantityControl(value: String, onChange: (String) -> Unit) {
    val number = value.toIntOrNull()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = { onChange(((number ?: 1) - 1).coerceIn(1, 5).toString()) }, enabled = number == null || number > 1) { Icon(Icons.Outlined.Remove, "수량 줄이기") }
        OutlinedTextField(value, onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) onChange(it) }, label = { Text("수량 (1~5개)") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = number !in 1..5)
        IconButton(onClick = { onChange(((number ?: 0) + 1).coerceIn(1, 5).toString()) }, enabled = number == null || number < 5) { Icon(Icons.Outlined.Add, "수량 늘리기") }
    }
}

@Composable
internal fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Switch(checked, onCheckedChange = onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
internal fun <T> ChoiceField(label: String, selected: T, options: List<T>, text: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label: ${text(selected)}", modifier = Modifier.weight(1f)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) { options.forEach { item -> DropdownMenuItem(text = { Text(text(item)) }, onClick = { onSelect(item); expanded = false }) } }
    }
}

@Composable
internal fun SectionTitle(title: String) { HorizontalDivider(Modifier.padding(top = 12.dp)); Text(title, Modifier.padding(top = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
