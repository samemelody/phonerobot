package com.phonerobot.app.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Core AI service: loads and runs Gemma 4 via Google's LiteRT-LM runtime.
 *
 * Uses the official LiteRT-LM Kotlin API (com.google.ai.edge.litertlm).
 * The model file should be a .litertlm file placed in assets/models/.
 *
 * Usage:
 *   val service = GemmaService(context)
 *   service.initialize(config)  // load model (one-time, ~2-10 seconds)
 *   val result = service.generate("Go forward 1 meter")  // returns InferenceResult
 *   service.close()             // release resources
 */
class GemmaService(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    var isReady: Boolean = false
        private set

    var audioSupportEnabled: Boolean = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "GemmaService"

    /**
     * Load the Gemma 4 model into memory using LiteRT-LM.
     * Call once before generate().
     *
     * @param config Model configuration
     * @param toolSets Optional list of ToolSet implementations for AI function calling
     */
    suspend fun initialize(
        config: GemmaConfig = GemmaConfig(),
        toolSets: List<ToolSet> = emptyList()
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Loading Gemma 4 model...")
                val startTime = System.currentTimeMillis()

                // Resolve model path: use provided path, or default to app external files dir
                val modelPath = if (config.modelPath.isNotEmpty()) {
                    config.modelPath
                } else {
                    File(context.getExternalFilesDir(null), "models/gemma-4-E4B-it.litertlm").absolutePath
                }
                Log.i(TAG, "Model path: $modelPath")

                // Verify model file exists
                val modelFile = File(modelPath)
                if (!modelFile.exists()) {
                    Log.e(TAG, "Model file not found: $modelPath")
                    return@withContext false
                }

                // Build the engine config
                // audioBackend is required for audio input — without it, the audio
                // executor is not created and sending Content.AudioFile causes SIGSEGV.
                // Note: audio backend must be CPU (model audio encoder only supports CPU).
                val mainBackend = if (config.useGpu) Backend.GPU() else Backend.CPU()
                val engineConfig = EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = mainBackend,
                    audioBackend = if (config.supportsAudio) Backend.CPU() else null,
                )

                // Create and initialize the engine
                engine = Engine(engineConfig)
                engine!!.initialize()

                // System prompt for robot protocol control
                val systemPrompt = buildString {
                    append("You are an AI that controls robots via Bluetooth/USB. Keep responses concise.\n\n")
                    append("WORKFLOW:\n")
                    append("1. When the user tells you what robot they control, call loadProtocol(filename)\n")
                    append("2. loadProtocol returns the available commands — remember them\n")
                    append("3. When the user gives a command (e.g. 'go', 'stop', 'turn left'), call executeJavaScript() with the right protocol function\n")
                    append("4. Binary data is auto-sent to the robot via BT/USB\n\n")
                    append("Example:\n")
                    append("  User: 'You are driving a toy car'\n")
                    append("  You: → loadProtocol('toy_car_protocol_core.js')\n")
                    append("  User: 'Go forward'\n")
                    append("  You: → executeJavaScript('return protocol.packForwardRequest(50, 1000);')\n")
                    append("  User: 'Stop!'\n")
                    append("  You: → executeJavaScript('return protocol.packStopRequest();')\n\n")
                    append("Robot types: toy_car_protocol_core.js (car), rover_protocol.js (UGV), drone_protocol.js (UAV), robot_arm_protocol.js (arm), bipedal_robot_protocol.js (humanoid).\n")
                    append("If unsure which protocol to use, call listProtocols(). If unsure about a function's parameters, call readProtocol().\n\n")
                    append("PROTOCOL DEBUGGING & REWRITING:\n")
                    append("If a command fails or produces unexpected results:\n")
                    append("1. Call readProtocol(filename) to examine the script\n")
                    append("2. Identify the issue (wrong command byte, byte order, offset, etc.)\n")
                    append("3. Call writeProtocol(new_filename, content) with the corrected script — choose a descriptive name\n")
                    append("4. Call loadProtocol(new_filename) to load the new version\n")
                    append("5. Retry the command with executeJavaScript()\n")
                    append("The original protocol is preserved, so you can compare both versions.")
                }

                // Convert ToolSet list to ToolProvider list
                val toolProviders = toolSets.map { tool(it) }

                // Create a conversation with enhanced system instruction, tools, and sampler config
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    tools = toolProviders,
                    automaticToolCalling = true,
                    samplerConfig = SamplerConfig(
                        topK = config.topK,
                        topP = config.topP.toDouble(),
                        temperature = config.temperature.toDouble(),
                    ),
                )

                conversation = engine!!.createConversation(conversationConfig)

                audioSupportEnabled = config.supportsAudio
                isReady = true
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Model loaded in ${elapsed}ms with ${toolSets.size} tool(s) — ready for inference")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model", e)
                isReady = false
                false
            }
        }
    }

    /**
     * Run inference on user input text. Returns generated response.
     * Must call initialize() first.
     */
    suspend fun generate(userPrompt: String): InferenceResult {
        check(isReady) { "Model not ready. Call initialize() first." }
        check(conversation != null) { "Conversation not initialized." }

        return withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()

            try {
                val response: Message = conversation!!.sendMessage(userPrompt)
                val outputText = extractText(response)

                val latency = System.currentTimeMillis() - startMs
                val result = InferenceResult(
                    text = outputText.trim(),
                    tokenCount = estimateTokenCount(outputText),
                    latencyMs = latency
                )

                Log.d(TAG, "Inference done in ${latency}ms -> ${result.text}")
                result

            } catch (e: Exception) {
                Log.e(TAG, "Inference failed", e)
                InferenceResult(
                    text = "Error: ${e.message}",
                    tokenCount = 0,
                    latencyMs = System.currentTimeMillis() - startMs
                )
            }
        }
    }

    /**
     * Run inference on audio input (e.g. recorded voice). Returns generated response.
     * Must call initialize() first.
     *
     * If the model does not support audio (supportsAudio = false), this falls back
     * to text-only with a placeholder message. Sending Content.AudioFile to a
     * text-only model causes a native SIGSEGV crash in LiteRT-LM.
     */
    suspend fun generateFromAudio(audioFile: java.io.File): InferenceResult {
        check(isReady) { "Model not ready. Call initialize() first." }
        check(conversation != null) { "Conversation not initialized." }

        if (!audioSupportEnabled) {
            Log.w(TAG, "Audio input not supported by this model — falling back to text prompt")
            return generate("[User sent a voice message, but this model cannot process audio.]")
        }

        return withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()

            try {
                val audioContent = Contents.of(Content.AudioFile(audioFile.absolutePath))
                val response: Message = conversation!!.sendMessage(audioContent)
                val outputText = extractText(response)

                val latency = System.currentTimeMillis() - startMs
                val result = InferenceResult(
                    text = outputText.trim(),
                    tokenCount = estimateTokenCount(outputText),
                    latencyMs = latency
                )

                Log.d(TAG, "Audio inference done in ${latency}ms -> ${result.text}")
                result

            } catch (e: Exception) {
                Log.e(TAG, "Audio inference failed", e)
                InferenceResult(
                    text = "Error: ${e.message}",
                    tokenCount = 0,
                    latencyMs = System.currentTimeMillis() - startMs
                )
            }
        }
    }

    /**
     * Run inference with streaming output via Kotlin Flow.
     */
    fun generateAsync(
        userPrompt: String,
        onPartial: (String) -> Unit,
        onComplete: (InferenceResult) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        check(isReady) { "Model not ready. Call initialize() first." }
        check(conversation != null) { "Conversation not initialized." }

        val startMs = System.currentTimeMillis()
        val fullText = StringBuilder()

        scope.launch {
            try {
                conversation!!.sendMessageAsync(userPrompt)
                    .catch { e ->
                        Log.e(TAG, "Streaming inference failed", e)
                        onError(e)
                    }
                    .collect { messagePart ->
                        // Each emitted Message is a partial chunk
                        val chunk = extractText(messagePart)
                        fullText.append(chunk)
                        onPartial(chunk)
                    }

                val latency = System.currentTimeMillis() - startMs
                val result = InferenceResult(
                    text = fullText.toString().trim(),
                    tokenCount = estimateTokenCount(fullText.toString()),
                    latencyMs = latency
                )
                Log.d(TAG, "Streaming inference done in ${latency}ms -> ${result.text}")
                onComplete(result)

            } catch (e: Exception) {
                Log.e(TAG, "Streaming inference error", e)
                onError(e)
            }
        }
    }

    // -- Internal helpers --

    /**
     * Extract text from a Message.
     * Message has no .text property; we get text from contents -> Content.Text items.
     */
    private fun extractText(message: Message): String {
        return message.contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("") { it.text }
    }

    private fun estimateTokenCount(text: String): Int {
        // Rough estimate: ~4 chars per token for English
        return (text.length + 3) / 4
    }

    /**
     * Release native resources. Call when app is destroyed.
     */
    fun close() {
        scope.cancel()
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation", e)
        }
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing engine", e)
        }
        conversation = null
        engine = null
        isReady = false
        audioSupportEnabled = false
        Log.d(TAG, "Resources released")
    }
}
