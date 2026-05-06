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

                // System prompt for AI assistant with robot control capabilities
                val systemPrompt = buildString {
                    append("You are an AI assistant that can chat with users and control robots using tools.\n\n")

                    append("YOUR CAPABILITIES:\n")
                    append("- Chat naturally with users about any topic\n")
                    append("- Control robot external devices (motors, sensors, actuators, etc.)\n")
                    append("- Write and execute JavaScript code in a sandbox environment\n")
                    append("- Send commands via USB or Bluetooth connections\n\n")

                    append("HOW YOU WORK:\n")
                    append("1. When user gives a robot command, you write JavaScript code\n")
                    append("2. The JavaScript code calls protocol functions to generate command data\n")
                    append("3. Execute the JavaScript in the sandbox to get the result\n")
                    append("4. The result may contain protocol-formatted data (binary packets, etc.)\n")
                    append("5. Send the command via USB or Bluetooth tool to the physical robot\n\n")

                    append("TOOLS AVAILABLE:\n")
                    append("- executeJavaScript(): Run JS code in sandbox, returns execution result\n")
                    append("- testJavaScript(): Save and test JS code, returns execution log\n")
                    append("- loadProtocol(): Load a robot protocol file (defines available functions)\n")
                    append("- listProtocols(): Show all available protocol files\n")
                    append("- [USB/Bluetooth tools]: Send commands to robot (implemented elsewhere)\n\n")

                    append("PROTOCOL SYSTEM:\n")
                    append("- Protocols are JavaScript files that define robot control functions\n")
                    append("- Example: protocol.packDriveRequest(200, 0, 100) returns a binary command\n")
                    append("- Default protocol: 'rover_protocol.js' (UGV/rover)\n")
                    append("- Available: rover_protocol.js (UGV), toy_car_protocol_core.js (car), drone_protocol.js (UAV), robot_arm_protocol.js (arm), bipedal_robot_protocol.js (humanoid)\n\n")

                    append("EXECUTION PATTERN:\n")
                    append("  User: 'Move forward 2 meters'\n")
                    append("  You: Write JS → 'var cmd = protocol.packDriveRequest(200, 0, 100); return cmd;'\n")
                    append("  You: [call executeJavaScript(jsCode)] → returns binary data\n")
                    append("  You: [call USB/Bluetooth tool with binary data] → robot moves\n")
                    append("  You: 'Moving forward 2 meters'\n\n")

                    append("RESPONSE GUIDELINES:\n")
                    append("- Chat naturally when user asks general questions\n")
                    append("- Be direct and action-oriented for robot commands\n")
                    append("- Explain what you did after execution (briefly)\n")
                    append("- If execution fails, explain why and suggest fixes\n")
                    append("- Keep responses concise (under 2 sentences when possible)\n")
                    append("- Execute first, then explain — don't ask unnecessary questions")
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
                // Log user input
                Log.i(TAG, "========== USER INPUT ==========")
                Log.i(TAG, userPrompt)
                Log.i(TAG, "========== END USER INPUT ==========")

                val response: Message = conversation!!.sendMessage(userPrompt)
                val outputText = extractText(response)

                val latency = System.currentTimeMillis() - startMs
                val result = InferenceResult(
                    text = outputText.trim(),
                    tokenCount = estimateTokenCount(outputText),
                    latencyMs = latency
                )

                // Log AI output
                Log.i(TAG, "========== AI OUTPUT ==========")
                Log.i(TAG, result.text)
                Log.i(TAG, "========== END AI OUTPUT ==========")
                Log.d(TAG, "Inference done in ${latency}ms")

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
                // Log audio input
                Log.i(TAG, "========== USER AUDIO INPUT ==========")
                Log.i(TAG, "Audio file: ${audioFile.absolutePath} (${audioFile.length()} bytes)")
                Log.i(TAG, "========== END USER AUDIO INPUT ==========")

                val audioContent = Contents.of(Content.AudioFile(audioFile.absolutePath))
                val response: Message = conversation!!.sendMessage(audioContent)
                val outputText = extractText(response)

                val latency = System.currentTimeMillis() - startMs
                val result = InferenceResult(
                    text = outputText.trim(),
                    tokenCount = estimateTokenCount(outputText),
                    latencyMs = latency
                )

                // Log AI output
                Log.i(TAG, "========== AI OUTPUT (from audio) ==========")
                Log.i(TAG, result.text)
                Log.i(TAG, "========== END AI OUTPUT ==========")
                Log.d(TAG, "Audio inference done in ${latency}ms")

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

        // Log user input
        Log.i(TAG, "========== USER INPUT (async) ==========")
        Log.i(TAG, userPrompt)
        Log.i(TAG, "========== END USER INPUT (async) ==========")

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

                // Log AI output
                Log.i(TAG, "========== AI OUTPUT (async) ==========")
                Log.i(TAG, result.text)
                Log.i(TAG, "========== END AI OUTPUT (async) ==========")
                Log.d(TAG, "Streaming inference done in ${latency}ms")

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
