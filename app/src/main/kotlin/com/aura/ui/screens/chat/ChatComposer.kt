package com.aura.ui.screens.chat

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aura.skills.Skill
import com.aura.ui.components.AuraIconButton
import com.aura.ui.theme.AuraDimensions
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.util.Haptics
import com.aura.ui.theme.AuraSpacing

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatComposer(
    draft: String,
    streaming: Boolean,
    sendEnabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRunInBackground: () -> Unit = {},
    onCancel: () -> Unit = {},
    onTapToSpeak: () -> Unit = {},
    onHoldToTalk: () -> Unit = {},
    onContinuousVoice: () -> Unit = {},
    onVoiceCall: () -> Unit = {},
    onCameraClick: () -> Unit = {},
    onGalleryClick: () -> Unit = {},
    onAudioClick: () -> Unit = {},
    onDocumentClick: () -> Unit = {},
    /**
     * Callback invoked when the user explicitly pastes an image into the
     * composer. The composer no longer auto-reads the system clipboard on
     * every composition to avoid silently capturing private content.
     */
    onImagePasted: (android.graphics.Bitmap) -> Unit = {},
    skills: List<Skill> = emptyList(),
    onUseSkill: (Skill) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var attachmentOpen by remember { mutableStateOf(false) }
    var voiceMenuOpen by remember { mutableStateOf(false) }
    val attachmentState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hapticView = LocalView.current
    val canSend = sendEnabled && draft.isNotBlank()
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Two-tier composer: the field owns a full-width line, and the controls
    // sit on a row beneath it — attach at the leading edge, mic and send
    // paired at the trailing edge.
    //
    // This shape is what ChatGPT, Claude and Manus all converge on, and the
    // convergence is not arbitrary: one row cannot hold a growing text
    // field and four controls without squeezing the field and jamming the
    // buttons into the container's curve.
    //
    // Every control gets a circular container of the same size. Giving only
    // send a filled disc, next to two bare glyphs, made it the single heavy
    // object in the row and it read as wedged into the corner rather than
    // belonging to the set.
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.xs, vertical = AuraSpacing.small)
            .testTag("chat-composer"),
        color = AuraThemeTokens.colors.surface1,
        contentColor = AuraThemeTokens.colors.textPrimary,
        shape = RoundedCornerShape(AuraSpacing.lg),
        border = androidx.compose.foundation.BorderStroke(AuraSpacing.hairline, AuraThemeTokens.colors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = AuraSpacing.sm,
                vertical = AuraSpacing.xs,
            ),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = AuraThemeTokens.colors.textPrimary,
                ),
                cursorBrush = SolidColor(AuraThemeTokens.colors.actionPrimary),
                minLines = 1,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (canSend) onSend() },
                ),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) {
                            Text(
                                text = stringResource(R.string.message_aura),
                                color = AuraThemeTokens.colors.textSecondary,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AuraSpacing.xxl, max = 144.dp)
                    .padding(horizontal = AuraSpacing.xs, vertical = AuraSpacing.xs)
                    .testTag("chat-composer-input"),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AuraIconButton(
                    onClick = { attachmentOpen = true },
                    shape = CircleShape,
                    containerColor = AuraThemeTokens.colors.surface2,
                    modifier = Modifier.testTag("chat-composer-attach"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Attach",
                        tint = AuraThemeTokens.colors.textSecondary,
                        modifier = Modifier.size(AuraSpacing.xxl2),
                    )
                }

                Spacer(Modifier.weight(1f))

            if (!streaming) {
                Box {
                    Box(
                        modifier = Modifier
                            .size(AuraSpacing.xxl)
                            .combinedClickable(
                                onClick = { voiceMenuOpen = true },
                                onLongClick = onHoldToTalk,
                            )
                            .testTag("chat-composer-voice"),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Same 40dp circle as attach and send, so the three
                        // controls read as one set.
                        Box(
                            modifier = Modifier
                                .size(AuraDimensions.iconButtonVisualSize)
                                .background(AuraThemeTokens.colors.surface2, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Voice modes",
                                tint = AuraThemeTokens.colors.textSecondary,
                                modifier = Modifier.size(AuraSpacing.xxl2),
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = voiceMenuOpen,
                        onDismissRequest = { voiceMenuOpen = false },
                    ) {
                        VoiceModeItem(
                            label = "Tap to speak",
                            supporting = "Speak one message and send",
                            icon = Icons.Filled.Mic,
                            onClick = {
                                voiceMenuOpen = false
                                onTapToSpeak()
                            },
                        )
                        VoiceModeItem(
                            label = "Hold to talk",
                            supporting = "Review your transcript before sending",
                            icon = Icons.Filled.GraphicEq,
                            onClick = {
                                voiceMenuOpen = false
                                onHoldToTalk()
                            },
                        )
                        VoiceModeItem(
                            label = "Continuous voice",
                            supporting = "Hands-free listening and replies",
                            icon = Icons.Filled.AudioFile,
                            onClick = {
                                voiceMenuOpen = false
                                onContinuousVoice()
                            },
                        )
                        VoiceModeItem(
                            label = "Voice call",
                            supporting = "Full-screen phone-call mode",
                            icon = Icons.Filled.Call,
                            onClick = {
                                voiceMenuOpen = false
                                onVoiceCall()
                            },
                        )
                    }
                }
            }

            AuraIconButton(
                onClick = {
                    if (streaming) {
                        onCancel()
                    } else if (canSend) {
                        Haptics.send(hapticView)
                        // Hide the soft keyboard immediately so the user
                        // sees the response, not the input bar.
                        keyboardController?.hide()
                        onSend()
                    }
                },
                // Hold to run it as a background task instead of a turn: it keeps going
                // after the app closes and notifies when it is done.
                //
                // A long-press rather than a second button. The composer already has four
                // controls in a row, a fifth would crowd them, and "hold for the other
                // thing" is the idiom this bar already uses for the voice modes.
                onLongClick = {
                    if (!streaming && canSend) {
                        Haptics.send(hapticView)
                        keyboardController?.hide()
                        onRunInBackground()
                    }
                },
                enabled = streaming || canSend,
                shape = CircleShape,
                // Filled accent circle when there's something to send,
                // matching the neutral circles of attach and mic when
                // there isn't.
                containerColor = when {
                    streaming -> AuraThemeTokens.colors.error
                    canSend -> AuraThemeTokens.colors.actionPrimary
                    else -> AuraThemeTokens.colors.surface2
                },
                modifier = Modifier.testTag(if (streaming) "chat-composer-stop" else "chat-composer-send"),
            ) {
                Icon(
                    // An upward arrow, not a paper plane. Every current
                    // assistant app uses ↑ here; the plane glyph is
                    // asymmetric, never optically centres in a circle, and
                    // reads as dated beside them.
                    imageVector = if (streaming) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                    contentDescription = if (streaming) "Stop streaming" else "Send",
                    tint = when {
                        streaming -> AuraThemeTokens.colors.onActionPrimary
                        canSend -> AuraThemeTokens.colors.onActionPrimary
                        else -> AuraThemeTokens.colors.textTertiary
                    },
                    modifier = Modifier.size(AuraSpacing.xxl2),
                )
            }
            }
        }
    }

    if (attachmentOpen) {
        ModalBottomSheet(
            onDismissRequest = { attachmentOpen = false },
            sheetState = attachmentState,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = AuraSpacing.xxl2, vertical = AuraSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
            ) {
                Text(
                    text = stringResource(R.string.attach),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = AuraSpacing.xs),
                )
                AttachmentOption(Icons.Filled.PhotoLibrary, "Gallery") {
                    attachmentOpen = false
                    onGalleryClick()
                }
                AttachmentOption(Icons.Filled.AddAPhoto, "Camera") {
                    attachmentOpen = false
                    onCameraClick()
                }
                AttachmentOption(Icons.Filled.AudioFile, "Audio") {
                    attachmentOpen = false
                    onAudioClick()
                }
                AttachmentOption(Icons.Filled.Description, "Document") {
                    attachmentOpen = false
                    onDocumentClick()
                }
                if (skills.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.use_a_skill),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = AuraThemeTokens.colors.textSecondary,
                        modifier = Modifier.padding(top = AuraSpacing.md, bottom = AuraSpacing.xxs),
                    )
                    Text(
                        text = stringResource(R.string.inserts_use_skill_name_into_the),
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textSecondary,
                        modifier = Modifier.padding(bottom = AuraSpacing.xs),
                    )
                    skills.take(8).forEach { skill ->
                        Surface(
                            onClick = {
                                attachmentOpen = false
                                onUseSkill(skill)
                            },
                            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AuraSpacing.xxl),
                            color = Color.Transparent,
                            shape = RoundedCornerShape(AuraSpacing.medium),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.medium),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    tint = AuraThemeTokens.colors.actionPrimary,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = skill.name,
                                        color = AuraThemeTokens.colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (skill.description.isNotBlank()) {
                                        Text(
                                            text = skill.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AuraThemeTokens.colors.textSecondary,
                                            maxLines = 2,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (skills.size > 8) {
                        Text(
                            text = "+${skills.size - 8} more — open the Skills tab to see all",
                            style = MaterialTheme.typography.labelSmall,
                            color = AuraThemeTokens.colors.textTertiary,
                            modifier = Modifier.padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xxs),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceModeItem(
    label: String,
    supporting: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(label, color = AuraThemeTokens.colors.textPrimary)
                Text(
                    supporting,
                    color = AuraThemeTokens.colors.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = AuraThemeTokens.colors.actionPrimary)
        },
        onClick = onClick,
    )
}

@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AuraSpacing.xxl),
        color = Color.Transparent,
        shape = RoundedCornerShape(AuraSpacing.medium),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            Icon(icon, contentDescription = null, tint = AuraThemeTokens.colors.textSecondary)
            Text(label, color = AuraThemeTokens.colors.textPrimary)
        }
    }
}
