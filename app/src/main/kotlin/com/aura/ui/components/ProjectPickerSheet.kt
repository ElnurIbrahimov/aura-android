package com.aura.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.aura.R
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

/**
 * Attribute this conversation to a project.
 *
 * "No project" is a first-class row rather than a dismiss action, because
 * unattributed is a legitimate state and the sticky default means the user needs
 * a way *out* of a project as much as a way into one. Without it, one tagged
 * conversation would make every later conversation inherit that project until
 * the user found some other way to clear it.
 *
 * Creating a project is the same text field as filtering, so naming one is one
 * gesture rather than a separate flow. `ProjectStore.create` is idempotent on
 * name, so typing a name that already exists selects it instead of failing.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ProjectPickerSheet(
    current: String?,
    projects: List<String>,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(projects, query) {
        if (query.isBlank()) projects
        else projects.filter { it.contains(query.trim(), ignoreCase = true) }
    }
    val canCreate = query.isNotBlank() && projects.none { it.equals(query.trim(), ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AuraSpacing.medium)
                .padding(bottom = AuraSpacing.large)
                .testTag("project-picker-sheet"),
        ) {
            Text(
                text = stringResource(R.string.project),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = AuraSpacing.sm),
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.project_find_or_create)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("project-picker-query"),
            )

            if (canCreate) {
                TextButton(
                    onClick = { onPick(query.trim()) },
                    modifier = Modifier.testTag("project-picker-create"),
                ) {
                    Text(stringResource(R.string.project_create, query.trim()))
                }
            }

            AuraListItem(
                label = stringResource(R.string.no_project),
                selected = current == null,
                testTag = "project-picker-none",
                onClick = { onPick(null) },
            )

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(filtered, key = { it }) { name ->
                    AuraListItem(
                        label = name,
                        selected = name == current,
                        testTag = "project-picker-row",
                        onClick = { onPick(name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuraListItem(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
    ) {
        Text(
            text = label,
            color = if (selected) AuraThemeTokens.colors.actionPrimary
            else AuraThemeTokens.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = AuraThemeTokens.colors.actionPrimary,
            )
        }
    }
}
