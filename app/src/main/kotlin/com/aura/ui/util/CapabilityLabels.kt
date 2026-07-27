package com.aura.ui.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.ui.graphics.vector.ImageVector
import com.aura.capabilities.CapabilityKind

/**
 * User-facing label, icon, and description for a [CapabilityKind].
 * Shared between Home destination cards and the Capabilities screen.
 */
fun CapabilityKind.displayLabel(): String = when (this) {
    CapabilityKind.ImageGeneration -> "Image generation"
    CapabilityKind.TextToSpeech -> "Text to speech"
    CapabilityKind.VideoGeneration -> "Video generation"
    CapabilityKind.World3DGeneration -> "3D world generation"
    CapabilityKind.WebSearch -> "Web search"
    CapabilityKind.Transcription -> "Transcription"
}

fun CapabilityKind.displayIcon(): ImageVector = when (this) {
    CapabilityKind.ImageGeneration -> Icons.Filled.Image
    CapabilityKind.TextToSpeech -> Icons.Filled.Audiotrack
    CapabilityKind.VideoGeneration -> Icons.Filled.Movie
    CapabilityKind.World3DGeneration -> Icons.Filled.ViewInAr
    CapabilityKind.WebSearch -> Icons.Filled.Language
    CapabilityKind.Transcription -> Icons.Filled.Audiotrack
}

fun CapabilityKind.description(): String = when (this) {
    CapabilityKind.ImageGeneration -> "Generate images from text prompts"
    CapabilityKind.TextToSpeech -> "Convert text to spoken audio"
    CapabilityKind.VideoGeneration -> "Generate short videos from prompts"
    CapabilityKind.World3DGeneration -> "Generate 3D world previews"
    CapabilityKind.WebSearch -> "Search the web with neural or classic backends"
    CapabilityKind.Transcription -> "Transcribe audio to text"
}
