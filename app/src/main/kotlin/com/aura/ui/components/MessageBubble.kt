package com.aura.ui.components

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.agent.Reaction
import com.aura.tools.Citation
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.Fraunces
import com.aura.ui.theme.InterDisplay
import com.aura.ui.theme.JetBrainsMono
import com.aura.ui.util.formatRelativeTime
import com.aura.ui.theme.AuraSpacing
import kotlin.math.sin

/**
 * The Aura AI avatar used in the chat header and as the inline
 * avatar to the left of every assistant message.
 *
 * This is the Android equivalent of `AuraBreathingAvatar.tsx` on
 * the web: a 36–44dp circle with a violet radial glow ring that
 * breathes in and out via a slow `infiniteRepeatable` animation.
 * The inner circle is a subtle white-to-transparent gradient
 * with a 1px white/07 border — the same look as the Web avatar.
 *
 * `isThinking` swaps the inner icon from the sparkles to a bolt
 * and speeds the breathing animation up — the Web calls this the
 * "thinking" state.
 */
@Composable
fun AuraAiAvatar(
    isActive: Boolean = true,
    isThinking: Boolean = false,
    isProactive: Boolean = false,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "aura-avatar")
    val ringScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isThinking) 1.18f else 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isThinking) 900 else 2400,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring-scale",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = if (isThinking) 0.55f else 0.40f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isThinking) 900 else 2400,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ring-alpha",
    )
    Box(
        modifier = modifier.size(size + AuraSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        // Outer breathing ring — radial gradient blur
        Box(
            modifier = Modifier
                .size(size + AuraSpacing.xs)
                .graphicsLayer {
                    scaleX = ringScale
                    scaleY = ringScale
                    alpha = if (isActive) ringAlpha else 0.10f
                }
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AuraThemeTokens.colors.assistantAccent,
                            AuraThemeTokens.colors.info.copy(alpha = 0.20f),
                            Color.Transparent,
                        ),
                        radius = (size.value * 1.4f),
                    ),
                    shape = CircleShape,
                ),
        )
        // Inner avatar
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    brush = Brush.linearGradient(
                        colors = if (isProactive) {
                            listOf(
                                AuraThemeTokens.colors.assistantAccent.copy(alpha = 0.28f),
                                AuraThemeTokens.colors.surface1,
                            )
                        } else {
                            listOf(
                                AuraThemeTokens.colors.surface3,
                                AuraThemeTokens.colors.surface1,
                            )
                        },
                    ),
                )
                .border(
                    width = AuraSpacing.hairline,
                    color = AuraThemeTokens.colors.borderStrong,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isProactive || isThinking) Icons.Filled.Bolt else Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = if (isProactive) {
                    AuraThemeTokens.colors.assistantAccent
                } else {
                    AuraThemeTokens.colors.textPrimary
                },
                modifier = Modifier.size(size * 0.5f),
            )
        }
        // Proactive ping dot
        if (isProactive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(AuraSpacing.medium)
                    .background(
                        color = AuraThemeTokens.colors.info,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/**
 * Animated thinking-shimmer row. Three dots rising and falling
 * with a 150ms stagger — the universal "AI is thinking" pattern.
 * Replaces the [TypingIndicator] inside the message list because
 * the new design shows the shimmer next to the AI avatar, not
 * inside a bubble.
 */
@Composable
fun ThinkingShimmer(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
    ) {
        for (i in 0 until 3) {
            val transition = rememberInfiniteTransition(label = "dot-$i")
            val offset by transition.animateFloat(
                initialValue = 0f,
                targetValue = -4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(i * 150),
                ),
                label = "y-$i",
            )
            val alpha by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(i * 150),
                ),
                label = "alpha-$i",
            )
            Box(
                modifier = Modifier
                    .graphicsLayer { translationY = offset }
                    .alpha(alpha)
                    .size(AuraSpacing.small)
                    .background(
                        color = AuraThemeTokens.colors.textSecondary,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

/**
 * Message bubble — the new web-aligned design.
 *
 * User bubble: asymmetric radius 24/24/4/24 (pointed at sender),
 * white background, black text, 0 4px 20px rgba(0,0,0,0.25) shadow.
 * 12dp horizontal + 10dp vertical padding, body text 1rem weight 500.
 *
 * Assistant bubble: NO bubble. Just the avatar to the left,
 * the role label (AURA in caps), the streaming text, citations,
 * and a footer with model + timestamp + hover actions. This
 * matches Aura Web's design where the assistant feels like a
 * document, not a chat reply.
 *
 * Stagger entry: pass [animationIndex] 0..4 to get the
 * spring-up entry animation. >4 falls back to index 4.
 */
@Composable
fun MessageBubble(
    text: String,
    isUser: Boolean,
    citations: List<Citation> = emptyList(),
    isStreaming: Boolean = false,
    timestamp: Long = 0L,
    modelLabel: String? = null,
    agentName: String? = null,
    isProactive: Boolean = false,
    reaction: Reaction? = null,
    animationIndex: Int = 0,
    durationMs: Long = 0L,
    generatedImages: List<String> = emptyList(),
    thinking: String? = null,
    /** True when the previous message came from the same sender. Tightens spacing. */
    groupedWithPrevious: Boolean = false,
    onShowSources: () -> Unit = {},
    onReact: (Reaction) -> Unit = {},
    onEdit: () -> Unit = {},
    onShare: () -> Unit = {},
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    if (isUser) {
        // ── User bubble: pointed at sender ─────────────────────────────
        UserBubble(
            text = text,
            animationIndex = animationIndex,
            groupedWithPrevious = groupedWithPrevious,
            onEdit = onEdit,
        )
    } else {
        // ── Assistant: avatar + content, NO bubble ─────────────────────
        AssistantMessage(
            text = text,
            citations = citations,
            isStreaming = isStreaming,
            isProactive = isProactive,
            timestamp = timestamp,
            modelLabel = modelLabel,
            agentName = agentName,
            durationMs = durationMs,
            reaction = reaction,
            animationIndex = animationIndex,
            copied = copied,
            generatedImages = generatedImages,
            thinking = thinking,
            onCopiedChange = { copied = it },
            onCopy = {
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Aura", text))
                copied = true
            },
            onShowSources = onShowSources,
            onReact = onReact,
            onShare = onShare,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    text: String,
    animationIndex: Int,
    groupedWithPrevious: Boolean = false,
    onEdit: () -> Unit = {},
) {
    val springEased = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        springEased.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = 0.65f,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
            ),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = (1f - springEased.value) * 16f
                alpha = springEased.value
            }
            // Messages sent back-to-back sit almost flush; a full gap only
            // opens when the speaker changes. Six messages in a row used to
            // read as six unrelated events instead of one burst.
            .padding(
                horizontal = AuraSpacing.md,
                vertical = if (groupedWithPrevious) AuraSpacing.tiny else AuraSpacing.xs,
            ),
        horizontalArrangement = Arrangement.End,
    ) {
        // Pointed-corner bubble: top-left 24, top-right 24,
        // bottom-left 24 (rounded away from sender), bottom-right 4
        // (pointed TOWARD sender). The web is the opposite for
        // the right-aligned user bubble: pointed at the sender.
        //
        // A continuation bubble also squares its top-right corner so a run
        // of messages reads as one stacked column rather than a scatter of
        // separate lozenges.
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = AuraSpacing.lg,
                        topEnd = if (groupedWithPrevious) AuraSpacing.xxs else AuraSpacing.lg,
                        bottomStart = AuraSpacing.xxs,
                        bottomEnd = AuraSpacing.lg,
                    ),
                )
                    .background(AuraThemeTokens.colors.userBubble)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onEdit,
                )
                .padding(horizontal = 22.dp, vertical = AuraSpacing.sm),
        ) {
            // SelectionContainer lets the user highlight and copy
            // just a phrase from their own message, not the whole thing.
            SelectionContainer {
                Text(
                    text = text.ifBlank { "…" },
                    color = AuraThemeTokens.colors.onUserBubble,
                    fontFamily = InterDisplay,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    lineHeight = 26.sp,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AssistantMessage(
    text: String,
    citations: List<Citation>,
    isStreaming: Boolean,
    isProactive: Boolean,
    timestamp: Long,
    modelLabel: String?,
    agentName: String?,
    durationMs: Long,
    reaction: Reaction?,
    animationIndex: Int,
    copied: Boolean,
    generatedImages: List<String> = emptyList(),
    thinking: String? = null,
    onCopiedChange: (Boolean) -> Unit,
    onCopy: () -> Unit,
    onShowSources: () -> Unit,
    onReact: (Reaction) -> Unit,
    onShare: () -> Unit = {},
) {
    val springEased = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        val delayMs = (animationIndex.coerceIn(0, 4) * 30L)
        kotlinx.coroutines.delay(delayMs)
        springEased.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = 0.65f,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
            ),
        )
    }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1500)
            onCopiedChange(false)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = (1f - springEased.value) * 16f
                alpha = springEased.value
            }
            .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.sm),
    ) {
        // Avatar and role label share one header line, and the body runs
        // beneath them at full width.
        //
        // The avatar used to hold its own column beside the text for the
        // whole message, so every line of every answer was indented past
        // it — roughly 60dp of permanent gutter, costing ~16% of the
        // reading width and forcing early wraps on a prose-heavy screen.
        // The avatar only needs to identify the speaker once.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
        ) {
            // Avatar: 32dp — proportional to 16sp body text, matches Claude mobile.
            AuraAiAvatar(
                isThinking = isStreaming,
                isProactive = isProactive,
                size = AuraSpacing.xl,
            )
            Text(
                text = agentName ?: "AURA",
                fontFamily = InterDisplay,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp,
                color = AuraThemeTokens.colors.textPrimary,
            )
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            if (isProactive) {
                Spacer(Modifier.height(AuraSpacing.xxs))
                Surface(
                    color = AuraThemeTokens.colors.aiToolCall,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = stringResource(R.string.proactive),
                        fontFamily = InterDisplay,
                        fontWeight = FontWeight.Medium,
                        fontSize = 10.sp,
                        color = AuraThemeTokens.colors.assistantAccent,
                        modifier = Modifier.padding(horizontal = AuraSpacing.xs, vertical = AuraSpacing.tiny),
                    )
                }
            }
            // Persisted thinking block (from history replay)
            if (!thinking.isNullOrBlank() && !isStreaming) {
                PersistedThinkingBlock(text = thinking)
            }
            Spacer(Modifier.height(AuraSpacing.small))
            val renderedText = remember(text, citations, isStreaming) {
                if (isStreaming) text else renderCitationMarkers(
                    text,
                    citations.mapTo(mutableSetOf()) { it.index },
                )
            }
            // Content. While streaming, StreamingText renders inline markdown
            // fast (with the cursor + tok/s). Once complete, re-render through
            // MarkdownColumn so fenced code blocks and tables become real
            // boxed blocks instead of literal backticks/pipes — StreamingText
            // only handles inline markup.
            val contentStyle = MaterialTheme.typography.bodyLarge.copy(
                color = AuraThemeTokens.colors.textPrimary,
            )
            if (isStreaming) {
                StreamingText(
                    text = renderedText.ifBlank { "…" },
                    isStreaming = true,
                    style = contentStyle,
                )
            } else {
                MarkdownColumn(
                    text = renderedText.ifBlank { "…" },
                    style = contentStyle,
                )
            }
            // Inline images (from image_gen tool)
            if (generatedImages.isNotEmpty() && !isStreaming) {
                Spacer(Modifier.height(AuraSpacing.xs))
                generatedImages.forEach { url ->
                    InlineImage(url = url)
                }
            }
            // Citations row
            if (citations.isNotEmpty() && !isStreaming) {
                Spacer(Modifier.height(AuraSpacing.xs))
                CitationChipRow(citations = citations, onShowSources = onShowSources)
            }
            // Footer: timestamp + actions. Actions appear on long-press
            // to reduce visual noise (like Claude mobile).
            if (!isStreaming) {
                Spacer(Modifier.height(AuraSpacing.xs))
                var showActions by remember { mutableStateOf(false) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                    modifier = Modifier.combinedClickable(
                        onClick = { showActions = !showActions },
                        onLongClick = { showActions = true },
                    ),
                ) {
                    if (timestamp > 0) {
                        Text(
                            text = com.aura.ui.util.formatRelativeTime(timestamp),
                            fontFamily = InterDisplay,
                            fontSize = 10.sp,
                            color = AuraThemeTokens.colors.textTertiary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Action buttons appear on tap/long-press to reduce noise
                    if (showActions) {
                    // Reaction buttons: thumbs up / down.
                    BubbleAction(
                        icon = Icons.Filled.ThumbUp,
                        label = "Helpful",
                        isActive = reaction == Reaction.Up,
                        onClick = { onReact(Reaction.Up) },
                    )
                    BubbleAction(
                        icon = Icons.Filled.ThumbDown,
                        label = "Not helpful",
                        isActive = reaction == Reaction.Down,
                        onClick = { onReact(Reaction.Down) },
                    )
                    // Copy action
                    BubbleAction(
                        icon = androidx.compose.material.icons.Icons.Filled.ContentCopy,
                        label = if (copied) "Copied" else "Copy",
                        onClick = onCopy,
                    )
                    // Share action
                    BubbleAction(
                        icon = Icons.Filled.Share,
                        label = "Share",
                        onClick = onShare,
                    )
                    } // end if (showActions)
                }
            }
        }
    }
}

@Composable
private fun CitationChipRow(citations: List<Citation>, onShowSources: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xxs)) {
        citations.forEach { c ->
            var showDialog by remember { mutableStateOf(false) }
            CitationChip(
                index = c.index,
                onClick = { showDialog = true },
            )
            if (showDialog) {
                val context = LocalContext.current
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = {
                        Text(
                            text = c.title.ifBlank { "Source ${c.index}" },
                            fontFamily = InterDisplay,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    text = {
                        if (c.url.isNotBlank()) {
                            Text(
                                text = c.url,
                                fontFamily = JetBrainsMono,
                                fontSize = 12.sp,
                                color = AuraThemeTokens.colors.assistantAccent,
                            )
                        }
                    },
                    confirmButton = {
                        if (c.url.isNotBlank()) {
                            androidx.compose.material3.TextButton(onClick = {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(c.url))
                                context.startActivity(intent)
                                showDialog = false
                            }) { Text(stringResource(R.string.open)) }
                        } else {
                            androidx.compose.material3.TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.close)) }
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.close)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun CitationChip(index: Int, onClick: () -> Unit) {
    // Outer box enforces a 48dp touch target (minimumInteractiveComponentSize)
    // and carries the accessibility label; the inner 18dp circle is the visual.
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Open citation $index" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(AuraSpacing.xl2)
                .clip(CircleShape)
                .background(
                    color = AuraThemeTokens.colors.assistantAccent.copy(alpha = 0.35f),
                )
                .border(
                    width = AuraSpacing.hairline,
                    color = AuraThemeTokens.colors.assistantAccent.copy(alpha = 0.5f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index.toString(),
                fontFamily = InterDisplay,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = AuraThemeTokens.colors.textPrimary,
            )
        }
    }
}

@Composable
private fun BubbleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    isActive: Boolean = false,
) {
    val tint = if (isActive) AuraThemeTokens.colors.assistantAccent else AuraThemeTokens.colors.textTertiary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.tiny),
        modifier = Modifier
            .clip(RoundedCornerShape(AuraSpacing.small))
            .clickable(onClick = onClick)
            // Meet the 48dp minimum touch target; the icon+label stay small.
            .defaultMinSize(minWidth = AuraSpacing.xxl, minHeight = AuraSpacing.xxl)
            .padding(horizontal = AuraSpacing.xs, vertical = AuraSpacing.tiny),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(AuraSpacing.md),
        )
        if (label.isNotBlank()) {
            Text(
                text = label,
                fontFamily = InterDisplay,
                fontSize = 10.sp,
                color = tint,
            )
        }
    }
}

/** Format a duration in ms as a short string: "1.2s" / "850ms" / "2.3m". */
private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> String.format(java.util.Locale.US, "%.1fm", ms / 60_000.0)
}

@Composable
private fun PersistedThinkingBlock(text: String) {
    var expanded by remember { mutableStateOf(false) }
    val colors = AuraThemeTokens.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = AuraSpacing.xxs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(vertical = AuraSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
        ) {
            Icon(
                imageVector = Icons.Filled.Psychology,
                contentDescription = null,
                tint = colors.assistantAccent,
                modifier = Modifier.size(AuraSpacing.large),
            )
            Text(
                text = if (expanded) "Thinking" else "Thinking\u2026",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = colors.textTertiary,
                modifier = Modifier.size(AuraSpacing.md),
            )
        }
        if (expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = colors.surface0,
                shape = MaterialTheme.shapes.small,
                border = androidx.compose.foundation.BorderStroke(AuraSpacing.hairline, colors.borderSubtle),
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        modifier = Modifier.padding(AuraSpacing.sm),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}
