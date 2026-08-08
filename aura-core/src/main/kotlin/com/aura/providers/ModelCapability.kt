package com.aura.providers

import com.aura.capabilities.CapabilityKind
import kotlinx.serialization.Serializable

/**
 * What a single catalog model can actually do.
 *
 * `/v1/models` is not a list of chat models — a fact the app learned the hard
 * way. Agnes AI returns two chat models, two image models and one video model
 * from one endpoint, and selecting the image one for chat produced
 * `HTTP 400 … "Model agnes-image-2.1-flash is an image model."`. `3f7c9d88`
 * fixed the symptom with a boolean (`OpenAiCompatProvider.canChat`), which was
 * enough to keep the picker honest but threw away the interesting half of the
 * answer: *what the model is instead*.
 *
 * Knowing that is what lets a newly-configured token light up image, video and
 * voice without a code change, rather than only chat.
 *
 * Distinct from [CapabilityKind] on purpose. This describes a **model**;
 * [CapabilityKind] describes a **backend**, and includes things no model
 * catalog ever mentions — `WebSearch` and `World3DGeneration` are services, not
 * models. [toCapabilityKind] is the deliberate, partial bridge between them.
 */
@Serializable
enum class ModelCapability {
    /** Holds a conversation. The only kind the chat picker may offer. */
    Chat,
    Image,
    Video,
    /** Text-to-speech. */
    Speech,
    /** Speech-to-text. */
    Transcription,
    Embedding,
    Rerank,
    Moderation,

    /**
     * Classified as nothing in particular — which means **treat it as chat**.
     *
     * Deliberately not a separate "probably chat" state. The bias established
     * in `canChat` and kept here: an unrecognised model is offered, because a
     * model that errors when used announces itself while a real chat model
     * missing from the picker is invisible. Callers that need chat models must
     * accept `Chat` and `Unknown` together — see [isChatUsable].
     */
    Unknown,
    ;

    /**
     * Whether this model may be offered as a conversational model.
     *
     * The single place the Chat-or-Unknown rule lives, so no caller has to
     * remember it.
     */
    val isChatUsable: Boolean get() = this == Chat || this == Unknown

    /**
     * The capability backend this model can serve, or null when it does not map
     * to one.
     *
     * Null for `Chat`/`Unknown` (that is [com.aura.providers.Provider]'s job,
     * not the capability registry's) and for `Embedding`/`Rerank`/`Moderation`,
     * which have no [CapabilityKind] today.
     */
    fun toCapabilityKind(): CapabilityKind? = when (this) {
        Image -> CapabilityKind.ImageGeneration
        Video -> CapabilityKind.VideoGeneration
        Speech -> CapabilityKind.TextToSpeech
        Transcription -> CapabilityKind.Transcription
        Chat, Unknown, Embedding, Rerank, Moderation -> null
    }
}

/** A catalog model paired with what it can do. */
data class ClassifiedModel(
    val name: String,
    val capability: ModelCapability,
)
