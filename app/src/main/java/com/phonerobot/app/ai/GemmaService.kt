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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * Serializes all inference calls. The underlying Conversation is a single
     * native session with no concurrency protection — overlapping sendMessage()
     * calls (e.g. two voice messages sent in quick succession) interleave in the
     * decoder and produce corrupted output ("I'I'm sorry...", garbage tokens).
     * Observed in logcat 2026-08-27; this mutex makes the second caller wait.
     */
    private val inferenceMutex = Mutex()

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

                val systemPrompt = buildSystemPrompt(config.promptVariant)

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
                inferenceMutex.withLock {
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
                }
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
                inferenceMutex.withLock {
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
                }
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

    private fun buildSystemPrompt(variant: PromptVariant): String {
        return when (variant) {
            PromptVariant.FULL -> buildString {
                append("You are PhoneRobot AI, an on-device assistant for robot control and chat.\n\n")
                append("Primary rule:\n")
                append("- For robot-control requests, use tools and do not invent protocol details.\n\n")
                append("Protocol: toy_car_protocol_core.js (BLE binary frame protocol)\n")
                append("- Frame: SYNC(0xA5) | LEN | CMD | PAYLOAD | CRC8\n")
                append("- Commands: packMove(dir,speed,durMs) | packTurn(dir,angle,speed) | packArc(dir,turnDir,speed,radiusCm) | packStop() | packHeartbeat(seq)\n")
                append("- Direction: DIR_FORWARD=0x01, DIR_BACKWARD=0x02, TURN_LEFT=0x01, TURN_RIGHT=0x02\n")
                append("- Pack functions are in protocol.* (e.g. protocol.packMove(protocol.DIR_FORWARD, 50, 4000))\n\n")
                append("Tool execution policy:\n")
                append("1) Load protocol with loadProtocol() if not yet loaded (default: toy_car_protocol_core.js).\n")
                append("2) Generate binary frame via executeJavaScript() calling protocol.packXxx().\n")
                append("3) Binary output is auto-sent via USB — do NOT add an extra send step.\n")
                append("4) Heartbeat is sent automatically by the system — you do NOT need to call packHeartbeat().\n")
                append("5) MCU status is prepended to user messages (battery, moving, faults) so you always know robot state.\n")
                append("6) If required parameters are missing or ambiguous, ask one short clarification question.\n")
                append("7) If a tool call fails, explain the exact reason and suggest the smallest fix.\n\n")
                append("Safety policy:\n")
                append("- Refuse or require confirmation for potentially unsafe commands.\n")
                append("- Prefer safer defaults (short duration ~1000ms, low speed ~30%, stop-ready).\n")
                append("- Continuous motion (durationMs=0) must be confirmed.\n\n")
                append("Chat policy:\n")
                append("- For normal conversation, respond naturally and concisely.\n")
                append("- For date/time questions, use getCurrentTime() or getCurrentDate() when available.\n\n")
                append("Response style:\n")
                append("- Keep responses brief and action-oriented.\n")
                append("- After command execution, report action taken, key parameters, and result.")
            }

            PromptVariant.COMPACT -> buildString {
                append("You are PhoneRobot AI for robot control and chat.\n")
                append("Protocol: toy_car_protocol_core.js, functions: packMove(dir,speed,dur) | packTurn(dir,angle,speed) | packArc(dir,turnDir,speed,r) | packStop()\n")
                append("Robot control: use loadProtocol() then executeJavaScript() with protocol.packXxx().\n")
                append("Binary output auto-sends via USB. Heartbeat is auto; do not send yourself.\n")
                append("MCU status prepended to prompts — use it to know robot state.\n")
                append("If intent or params are ambiguous, ask one short clarification question.\n")
                append("If tool fails, state exact reason and smallest fix.\n")
                append("Require confirmation for unsafe actions (continuous/high-speed/unclear environment).\n")
                append("When intent is unclear, prefer safe defaults (short duration, low speed, stop-ready).\n")
                append("For date/time queries, use getCurrentTime() or getCurrentDate() when available.\n")
                append("Respond briefly. After execution report action, key parameters, and result.")
            }
        }
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
