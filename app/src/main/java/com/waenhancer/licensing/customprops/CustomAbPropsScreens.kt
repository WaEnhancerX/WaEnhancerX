package com.waenhancer.licensing.customprops

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.components.WaexSwitchPreference
import com.waenhancer.ui.components.WaexTopBar
import com.waenhancer.ui.designsystem.WaexIcons
import com.waenhancer.ui.designsystem.WaexTheme
import org.json.JSONArray

@Composable
fun CustomAbPropsScreen(
    prefs: CustomAbPropsStore,
    onBack: () -> Unit,
    onEdit: (Int) -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    var enabled by remember { mutableStateOf(prefs.getBoolean(CustomAbProps.ENABLED_KEY, false)) }
    var entries by remember {
        mutableStateOf(CustomAbProps.decode(prefs.getString(CustomAbProps.PREF_KEY, "")))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        WaexTopBar(title = "Custom AB Properties", onBackClick = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    shape = radius.cardShape,
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.outline)
                ) {
                    WaexSwitchPreference(
                        title = "Enable custom properties",
                        description = "Apply these typed WhatsApp AB overrides at startup.",
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            prefs.putBoolean(CustomAbProps.ENABLED_KEY, it)
                        },
                        showDivider = false
                    )
                }
            }
            if (entries.isEmpty()) {
                item {
                    Text(
                        text = "No custom properties configured.",
                        style = typography.bodyMd,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 20.dp)
                    )
                }
            }
            itemsIndexed(entries, key = { _, entry -> "${entry.id}:${entry.type}" }) { index, entry ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(index) },
                    shape = radius.cardShape,
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.outline)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.note.ifBlank { "Property ${entry.id}" },
                                style = typography.titleMd,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Property ${entry.id} • ${entry.type.name.lowercase()}",
                                style = typography.bodySm,
                                color = colors.onSurfaceVariant
                            )
                            Text(
                                text = entry.value,
                                style = typography.bodySm,
                                color = colors.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            entries = entries.filterIndexed { itemIndex, _ -> itemIndex != index }
                            prefs.putString(CustomAbProps.PREF_KEY, CustomAbProps.encode(entries))
                        }) {
                            Icon(WaexIcons.Clear, contentDescription = "Delete", tint = colors.error)
                        }
                        Icon(WaexIcons.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
                    }
                }
            }
            item {
                Button(
                    onClick = { onEdit(-1) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = radius.buttonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                ) { Text("Add property", fontWeight = FontWeight.Bold) }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomAbPropEditorScreen(index: Int, prefs: CustomAbPropsStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val existing = remember(index) { CustomAbProps.decode(prefs.getString(CustomAbProps.PREF_KEY, "")) }
    val original = existing.getOrNull(index)
    var id by remember { mutableStateOf(original?.id?.toString().orEmpty()) }
    var type by remember { mutableStateOf(original?.type ?: CustomAbProps.Type.BOOLEAN) }
    var value by remember { mutableStateOf(original?.value ?: "false") }
    var note by remember { mutableStateOf(original?.note.orEmpty()) }
    var listItemType by remember { mutableStateOf(original?.listItemType ?: CustomAbProps.ListItemType.STRING) }
    var listMode by remember { mutableStateOf(0) }
    val listItems = remember { mutableStateListOf<String>().apply { addAll(parseListItems(original?.value.orEmpty())) } }
    var pendingItem by remember { mutableStateOf("") }
    var rawList by remember { mutableStateOf(original?.value.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun currentValue(): String = if (type == CustomAbProps.Type.LIST) {
        if (listMode == 0) encodeListItems(listItems) else rawList
    } else value

    fun validate(): CustomAbProps.Entry {
        val parsedId = id.toIntOrNull() ?: throw IllegalArgumentException("Enter a valid non-negative property ID")
        if (existing.withIndex().any { it.index != index && it.value.id == parsedId }) {
            throw IllegalArgumentException("Property ID $parsedId already exists")
        }
        return CustomAbProps.Entry(parsedId, type, currentValue(), note, listItemType)
    }

    Column(Modifier.fillMaxSize()) {
        WaexTopBar(if (original == null) "Add AB Property" else "Edit AB Property", onBackClick = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Property", style = typography.titleMd, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it.filter(Char::isDigit); error = null },
                    label = { Text("Property ID") },
                    supportingText = { Text("Numeric WhatsApp AB property identifier") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = error?.contains("ID") == true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text("Value type", style = typography.titleMd, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CustomAbProps.Type.entries.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = {
                                type = option
                                value = defaultValue(option)
                                if (option == CustomAbProps.Type.LIST && rawList.isBlank()) rawList = "[]"
                                error = null
                            },
                            label = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) }
                        )
                    }
                }
            }
            if (type == CustomAbProps.Type.LIST) {
                item {
                    Text("List item type", style = typography.titleMd, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CustomAbProps.ListItemType.entries.forEach { option ->
                            FilterChip(
                                selected = listItemType == option,
                                onClick = { listItemType = option; error = null },
                                label = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) }
                            )
                        }
                    }
                }
                item {
                    Surface(shape = radius.cardShape, border = BorderStroke(1.dp, colors.outline), color = colors.surface) {
                        Column {
                            TabRow(selectedTabIndex = listMode, containerColor = colors.surface) {
                                Tab(selected = listMode == 0, onClick = {
                                    if (listMode != 0) {
                                        listItems.clear(); listItems.addAll(parseListItems(rawList))
                                        listMode = 0
                                    }
                                }, text = { Text("Items") })
                                Tab(selected = listMode == 1, onClick = {
                                    rawList = encodeListItems(listItems)
                                    listMode = 1
                                }, text = { Text("Raw input") })
                            }
                            if (listMode == 0) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (listItems.isNotEmpty()) {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listItems.forEachIndexed { itemIndex, item ->
                                                InputChip(
                                                    selected = false,
                                                    onClick = {},
                                                    label = { Text(item) },
                                                    trailingIcon = {
                                                        Icon(
                                                            WaexIcons.Clear,
                                                            contentDescription = "Remove $item",
                                                            modifier = Modifier.clickable { listItems.removeAt(itemIndex) }
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    } else Text("Add at least one item", style = typography.bodySm, color = colors.onSurfaceVariant)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = pendingItem,
                                            onValueChange = { pendingItem = it; error = null },
                                            label = { Text("New ${listItemType.name.lowercase()} item") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(onClick = {
                                            val candidate = pendingItem.trim()
                                            runCatching { validateListItem(candidate, listItemType) }
                                                .onSuccess { listItems.add(candidate); pendingItem = ""; error = null }
                                                .onFailure { error = it.message }
                                        }, enabled = pendingItem.isNotBlank()) { Text("Add") }
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = rawList,
                                    onValueChange = { rawList = it; error = null },
                                    label = { Text("JSON array, comma-separated, or one item per line") },
                                    minLines = 6,
                                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    Text("Value", style = typography.titleMd, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    when (type) {
                        CustomAbProps.Type.BOOLEAN -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("true", "false").forEach { option ->
                                FilterChip(selected = value == option, onClick = { value = option; error = null }, label = { Text(option) })
                            }
                        }
                        else -> OutlinedTextField(
                            value = value,
                            onValueChange = { value = it; error = null },
                            label = { Text(valueLabel(type)) },
                            supportingText = { Text(valueHint(type)) },
                            keyboardOptions = KeyboardOptions(keyboardType = when (type) {
                                CustomAbProps.Type.INTEGER -> KeyboardType.Number
                                CustomAbProps.Type.FLOAT -> KeyboardType.Decimal
                                else -> KeyboardType.Text
                            }),
                            minLines = if (type == CustomAbProps.Type.JSON) 5 else 1,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            item {
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())
            }
            error?.let { message -> item { Text(message, style = typography.bodySm, color = colors.error) } }
            item {
                Button(
                    onClick = {
                        runCatching {
                            val entry = validate()
                            val updated = existing.toMutableList()
                            if (index in updated.indices) updated[index] = entry else updated.add(entry)
                            prefs.putString(CustomAbProps.PREF_KEY, CustomAbProps.encode(updated))
                        }.onSuccess {
                            Toast.makeText(context, "Property saved", Toast.LENGTH_SHORT).show(); onBack()
                        }.onFailure { error = it.message ?: "Invalid property value" }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = radius.buttonShape,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)
                ) { Text("Save property", fontWeight = FontWeight.Bold) }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private fun defaultValue(type: CustomAbProps.Type) = when (type) {
    CustomAbProps.Type.BOOLEAN -> "false"
    CustomAbProps.Type.JSON -> "{}"
    CustomAbProps.Type.LIST -> "[]"
    else -> ""
}

private fun valueLabel(type: CustomAbProps.Type) = when (type) {
    CustomAbProps.Type.INTEGER -> "Integer value"
    CustomAbProps.Type.FLOAT -> "Decimal value"
    CustomAbProps.Type.JSON -> "JSON object or array"
    else -> "Text value"
}

private fun valueHint(type: CustomAbProps.Type) = when (type) {
    CustomAbProps.Type.INTEGER -> "Whole number only"
    CustomAbProps.Type.FLOAT -> "Finite decimal number"
    CustomAbProps.Type.JSON -> "Must be valid JSON beginning with { or ["
    else -> "Text is preserved exactly"
}

private fun parseListItems(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        List(array.length()) { array.get(it).toString() }
    }.getOrElse { raw.split(',', '\n').map(String::trim).filter(String::isNotEmpty) }
}

private fun encodeListItems(items: List<String>): String = JSONArray().apply {
    items.forEach { put(it) }
}.toString()

private fun validateListItem(value: String, type: CustomAbProps.ListItemType) {
    require(value.isNotBlank()) { "List item cannot be empty" }
    when (type) {
        CustomAbProps.ListItemType.INTEGER -> require(value.toIntOrNull() != null) { "Enter a valid integer" }
        CustomAbProps.ListItemType.FLOAT -> require(value.toFloatOrNull()?.isFinite() == true) { "Enter a finite decimal number" }
        CustomAbProps.ListItemType.BOOLEAN -> require(value.equals("true", true) || value.equals("false", true)) { "Boolean items must be true or false" }
        CustomAbProps.ListItemType.STRING -> Unit
    }
}
