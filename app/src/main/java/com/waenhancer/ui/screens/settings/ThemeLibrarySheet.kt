package com.waenhancer.ui.screens.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.app.R
import com.waenhancer.theme.InstalledTheme
import com.waenhancer.theme.ThemeRepository
import com.waenhancer.ui.components.WaexModalBottomSheet
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.utils.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lists installed WAEX themes, imports new `.zip` packages and activates or removes them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemeLibrarySheet(
    preferenceManager: com.waenhancer.core.preferences.WaexPreferenceManager,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    onWriteCss: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember(preferenceManager.targetEpoch) { ThemeRepository(context.applicationContext, preferenceManager) }
    val scope = rememberCoroutineScope()
    val colors = WaexTheme.colors
    val radius = WaexTheme.radius
    var themes by remember { mutableStateOf(repository.list()) }
    var activeId by remember { mutableStateOf(repository.activeId()) }
    var pendingDelete by remember { mutableStateOf<InstalledTheme?>(null) }

    fun refresh() {
        themes = repository.list()
        activeId = repository.activeId()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { repository.import(uri) }
            result.onSuccess {
                Toast.makeText(context, UiText.get(R.string.waex_ui_theme_imported, "Theme imported"), Toast.LENGTH_SHORT).show()
                refresh()
            }.onFailure {
                Toast.makeText(
                    context,
                    UiText.get(R.string.waex_ui_theme_import_failed, "Theme import failed: %1\$s", it.message ?: "?"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    WaexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        shape = radius.bottomSheetShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                UiText.get(R.string.waex_ui_theme_library, "Theme library"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                UiText.get(
                    R.string.waex_ui_theme_library_desc,
                    "Import a WAEX theme (.zip with style.css and optional images), then pick it below. Restart WhatsApp after changing themes."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )

            ThemeRow(
                title = UiText.get(R.string.waex_ui_no_theme_default, "Default (no theme)"),
                subtitle = null,
                selected = activeId == null,
                onSelect = { if (repository.activate(null)) { refresh(); onChanged() } },
                onDelete = null
            )
            themes.forEach { theme ->
                ThemeRow(
                    title = theme.displayName,
                    subtitle = theme.header.author?.let { UiText.get(R.string.waex_ui_theme_by_author, "by %1\$s", it) },
                    selected = activeId == theme.id,
                    onSelect = {
                        if (repository.activate(theme.id)) { refresh(); onChanged() }
                    },
                    onDelete = { pendingDelete = theme }
                )
            }

            Button(
                onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = radius.buttonShape
            ) {
                Text(UiText.get(R.string.waex_ui_import_theme_zip, "Import theme (.zip)"), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onWriteCss,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = radius.buttonShape
            ) {
                Text(UiText.get(R.string.waex_ui_write_custom_css, "Write custom CSS"))
            }
        }
    }

    pendingDelete?.let { theme ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(UiText.get(R.string.waex_ui_theme_delete_confirm, "Delete this theme?")) },
            text = { Text(theme.displayName) },
            confirmButton = {
                TextButton(onClick = {
                    val wasActive = activeId == theme.id
                    repository.delete(theme.id)
                    pendingDelete = null
                    refresh()
                    if (wasActive) onChanged()
                }) { Text(UiText.get(R.string.action_delete, "Delete")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(UiText.get(R.string.action_cancel, "Cancel")) }
            }
        )
    }
}

@Composable
private fun ThemeRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = WaexTheme.colors.onSurfaceVariant)
            }
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete) { Text(UiText.get(R.string.action_delete, "Delete")) }
        }
    }
}
