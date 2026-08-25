package com.phonerobot.app.ai

/**
 * Data class representing a chat message in the conversation.
 */
data class ChatMessage(
    val role: Role,
    val content: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val toolName: String? = null
) {
    enum class Role {
        USER, ASSISTANT, SYSTEM, TOOL
    }
}

/**
 * Configuration for the Gemma model inference.
 *
 * @param modelPath absolute path to the .litertlm model file on device storage
 * @param maxTokens max output tokens (used for reference; actual limit set by model)
 * @param temperature creativity / randomness (0.0 = deterministic, 1.0 = creative)
 * @param topK top-K sampling parameter
 * @param topP top-P (nucleus) sampling parameter
 * @param useGpu whether to use GPU delegate if available
 */
data class GemmaConfig(
    val modelPath: String = "",  // resolved dynamically in GemmaService if empty
    val maxTokens: Int = 256,
    val temperature: Float = 0.5f,
    val topK: Int = 40,
    val topP: Float = 0.9f,
    val useGpu: Boolean = true,
    val supportsAudio: Boolean = true,  // Gemma 4 supports audio input
    val promptVariant: PromptVariant = PromptVariant.FULL,
)

enum class PromptVariant {
    FULL,
    COMPACT,
}

/**
 * Result of a single inference call.
 */
data class InferenceResult(
    val text: String,
    val tokenCount: Int,
    val latencyMs: Long,
)
