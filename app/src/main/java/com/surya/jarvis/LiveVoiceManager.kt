package com.surya.jarvis
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale

class LiveVoiceManager(
    private val context: Context,
    private val onUserSpeech: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {

    private data class PlaybackChunk(
        val generation: Int,
        val bytes: ByteArray
    )

    private enum class TaskState {
        IDLE,
        OBSERVING,
        READY_TO_ACT,
        WAITING_FOR_CONFIRMATION,
        EXECUTING,
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELLED,
        ESCALATED
    }

    private data class ToolResult(
        val status: String,
        val message: String,
        val verified: Boolean = false,
        val retryable: Boolean = false,
        val taskState: TaskState,
        val data: JSONObject? = null
    ) {
        fun withData(value: JSONObject?): ToolResult = copy(data = value)

        fun toJson(): JSONObject = JSONObject()
            .put("success", status == "success")
            .put("status", status)
            .put("verified", verified)
            .put("retryable", retryable)
            .put("task_state", taskState.name.lowercase(Locale.ROOT))
            .put("message", message)
            .apply { if (data != null) put("data", data) }
    }

    companion object {
        private const val WORKER_URL =
            "https://jarvis-ai-backend.suryawebstudio.workers.dev"

        private const val LIVE_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"

        private const val MODEL =
            "models/gemini-3.1-flash-live-preview"

    private const val INPUT_RATE = 16000
    private const val OUTPUT_RATE = 24000
    private const val MAX_TASK_STEPS = 24
    private const val BARGE_IN_MIN_RMS = 1_600.0
    private const val BARGE_IN_REQUIRED_FRAMES = 3
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val reconnectScheduled = AtomicBoolean(false)
    // The service can call stop(), onTaskRemoved(), and release() close together.
    // Only the first shutdown is allowed to release the audio/WebSocket resources.
    private val stopped = AtomicBoolean(true)
    private val playbackGeneration = AtomicInteger(0)
    // Keep enough decoded audio buffered for low-memory phones whose audio
    // thread can be briefly delayed by Android or another foreground app.
    private val playbackQueue = LinkedBlockingDeque<PlaybackChunk>(64)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var taskActive = false
    private var taskStepCount = 0
    private var awaitingTaskConfirmation = false
    private var taskState = TaskState.IDLE

    private val fileManager = JarvisFileManager(context.applicationContext)
    private val memoryStore = JarvisMemoryStore(context.applicationContext)

    @Volatile
    private var setupComplete = false
    @Volatile
    private var sessionResumptionHandle: String? = null

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var audioTrack: AudioTrack? = null
    private var recorderThread: Thread? = null
    @Volatile
    private var playbackThread: Thread? = null
    // Do not send microphone frames while MARK is speaking. Device-level echo
    // cancellation is not reliable on every phone, and feeding MARK's own
    // reply back into Gemini can create an endless self-conversation loop.
    @Volatile
    private var suppressMicrophone = false
    @Volatile
    private var lastPlaybackChunkAtNanos = 0L
    private var bargeInSpeechFrames = 0
    private var ambientRms = 500.0

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    fun startListening() {
        if (!running.compareAndSet(false, true)) return
        stopped.set(false)

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            status("MICROPHONE PERMISSION REQUIRED")
            return
        }

        setupComplete = false
        status("REQUESTING TEMPORARY TOKEN...")

        connectToLive()
    }

    private fun connectToLive() {
        Thread {
            try {
                val tokenRequest = Request.Builder()
                    .url("$WORKER_URL/live-token")
                    .post("{}".toRequestBody())
                    .build()

                val response = client.newCall(tokenRequest).execute()

                val responseBody =
                    response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw IOException(
                        "Token HTTP ${response.code}: $responseBody"
                    )
                }

                val json = JSONObject(responseBody)

                val token = json.optString("token")
                    .ifBlank { json.optString("name") }

                if (token.isBlank()) {
                    throw IOException(
                        "Temporary token missing: $responseBody"
                    )
                }

                if (!running.get()) return@Thread

                val request = Request.Builder()
                    .url(LIVE_URL)
                    .header("Authorization", "Token $token")
                    .build()

                webSocket = client.newWebSocket(
                    request,
                    liveListener
                )

            } catch (e: Exception) {
                fail("TOKEN ERROR: ${e.message}")
            }
        }.start()
    }

    private val liveListener = object : WebSocketListener() {

        override fun onOpen(
            webSocket: WebSocket,
            response: Response
        ) {
            status("CONNECTED — SENDING SESSION SETUP...")

            val setup = JSONObject().apply {
                put(
                    "setup",
                    JSONObject().apply {
                        put("model", MODEL)

                        put(
                            "generationConfig",
                            JSONObject().apply {
                                put(
                                    "responseModalities",
                                    JSONArray().put("AUDIO")
                                )
                            }
                        )

                        put(
                            "systemInstruction",
                            JSONObject().apply {
                                put(
                                    "parts",
                                    JSONArray().put(
                                        JSONObject().put(
                                            "text",
                                            "You are MARK, an intelligent personal AI assistant. " +
                                            "Speak naturally, clearly and conversationally. " +
                                            "Be concise unless more detail is needed. " +
                                            "You are speaking directly with the user in a live voice conversation. " +
                                            "You may open an installed app or a listed Android Settings page only when the user directly asks. You may turn the flashlight on or off with set_flashlight when requested. You may capture one still image with capture_camera_image only when the user directly asks what the camera sees; never capture continuously or secretly. " +
                                            "Android protects many system settings. For Wi-Fi and Bluetooth, open the system control panel or Settings page first. If MARK Accessibility is enabled, inspect the visible panel and tap only the exact visible Wi-Fi or Bluetooth switch the user requested; otherwise tell the user Android requires them to tap the switch. Never claim a protected setting changed unless the tool response or a follow-up screen inspection confirms it. " +
                                            "When MARK Accessibility is enabled, you may inspect and control only visible UI elements using the provided tools; it does not grant hidden access to every phone setting. inspect_visible_screen returns a snapshot_id, a complete flag, and refs such as @a1abc123:e1. If complete is false, inspect again before choosing a target. For tap, type, and scroll, always pass the exact snapshot_id and ref from the latest inspection; never guess labels or reuse an old ref. MARK re-identifies refs from stable role/name/description evidence and rejects ambiguous matches, so if it reports a stale or ambiguous ref, inspect again and choose from the new snapshot. Back, Home, and overview do not need a ref. Accessibility is always the primary path. If the tree is incomplete or does not expose a requested visible control, use inspect_visual_screen once as a visual fallback; it sends one current screenshot with an 8-column by 12-row labeled grid and a short-lived grounding_token to the vision session. Use visual_tap only for a clearly visible custom-drawn target, passing the exact grid cell and grounding_token from the latest visual inspection. A visual token permits only one gesture and expires quickly; inspect again to verify it before any further visual gesture. Never use visual fallback to bypass passwords, biometrics, CAPTCHAs, permission dialogs, or security screens. " +
                                            "For a multi-step request, first call start_task, then execute it as a task: inspect the screen before the first control action, perform one action at a time, inspect the screen again after every control action, then continue only when the result matches the intended step. Tool responses include status, verified, retryable, and task_state fields; success means the request was accepted, while verified=true means the outcome was confirmed. Never issue multiple phone-control function calls in one turn; wait for each function response before choosing the next action. Do not call complete_task until the requested outcome is verified. Do not stop after the first successful action unless the task is complete or a confirmation is required. " +
                                            "Use inspect_visible_screen before every target-dependent action. If MARK reports a stale or invalid ref, inspect again and retry with the new ref. Never claim an action succeeded unless its function response says it did. " +
                                            "For sending, deleting, purchasing, calling, sharing, posting, installing, or other consequential actions, ask the user for confirmation first and then repeat the blocked action with confirmed=true only after the user explicitly agrees. " +
                                            "For file work, MARK uses Android's full shared-storage access only. If it is not enabled, tell the user to enable Full storage access from the MARK Settings panel and Android's permission screen; do not ask the user to select a folder. Use simple file names and do not invent paths. Before delete_file, say exactly which file or folder will be permanently deleted and wait for an explicit yes, then call delete_file with confirmed=true. " +
                                            "When a tool response says confirmation is required, ask exactly one concise confirmation question in your normal MARK Live voice, then wait for the user's answer. The Android app does not speak a second local confirmation. " +
                                            "If the user says stop, cancel, or abort, call cancel_task immediately. " +
                                            "Never bypass passwords, biometrics, CAPTCHAs, Android permission dialogs, or security screens. " +
                                            autopilotInstruction() +
                                            persistentMemoryInstruction()
                                        )
                                    )
                                )
                            }
                        )

                        put(
                            "sessionResumption",
                            JSONObject().apply {
                                sessionResumptionHandle
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { put("handle", it) }
                            }
                        )
                        put("tools", phoneControlTools())
                    }
                )
            }

            webSocket.send(setup.toString())
        }

        private fun persistentMemoryInstruction(): String {
            val memories = memoryStore.all()
            if (memories.isEmpty()) return ""
            return " You may use these private persistent user memories when relevant: " +
                memories.joinToString("; ") + ". Do not mention the memory system unless asked, " +
                "and always follow the user's current request if it conflicts with an old memory."
        }

        private fun autopilotInstruction(): String {
            val enabled = context.getSharedPreferences("jarvis_ui", Context.MODE_PRIVATE)
                .getBoolean("autopilot", true)
            return if (enabled) {
                " Autopilot mode is enabled. For safe, reversible actions such as opening an app or Settings page, reading the visible screen, scrolling, ordinary non-sensitive taps, non-sensitive typing, and flashlight control, call the appropriate tool directly without asking the user for extra confirmation. The application still requires confirmation for sending, deleting, purchasing, paying, calling, sharing, posting, installing, changing security settings, deleting files, or any other consequential action; never bypass that enforcement. "
            } else {
                " Autopilot mode is disabled. Follow the normal confirmation policy, while still obeying the application’s mandatory confirmation checks for consequential actions. "
            }
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String
        ) {
            handleServerMessage(webSocket, text)
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString
        ) {
            handleServerMessage(webSocket, bytes.utf8())
        }

        private fun handleServerMessage(
            webSocket: WebSocket,
            text: String
        ) {
            try {
                val json = JSONObject(text)

                if (json.has("error")) {
                    fail("LIVE SERVICE ERROR — PLEASE TRY AGAIN")
                    return
                }

                json.optJSONObject("sessionResumptionUpdate")?.let { update ->
                    val handle = listOf("newHandle", "handle", "token")
                        .asSequence()
                        .map { update.optString(it) }
                        .firstOrNull { it.isNotBlank() }
                    if (update.optBoolean("resumable", false) && handle != null) {
                        sessionResumptionHandle = handle
                    }
                }

                if (json.has("goAway")) {
                    scheduleReconnect(250L)
                    return
                }

                if (json.has("setupComplete")) {
                    setupComplete = true
                    status("MARK LIVE — LISTENING")
                    startPlayback()
                    startAudioCapture()
                    return
                }

                val toolCall = json.optJSONObject("toolCall")
                if (toolCall != null) {
                    handlePhoneToolCall(webSocket, toolCall)
                    return
                }

                val serverContent =
                    json.optJSONObject("serverContent") ?: return

                if (serverContent.optBoolean("interrupted")) {
                    interruptPlayback()
                }

                val modelTurn =
                    serverContent.optJSONObject("modelTurn") ?: return

                val parts =
                    modelTurn.optJSONArray("parts") ?: return

                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue

                    val inlineData =
                        part.optJSONObject("inlineData") ?: continue

                    val encoded = inlineData.optString("data")

                    if (encoded.isNotBlank()) {
                        val audio = Base64.decode(
                            encoded,
                            Base64.DEFAULT
                        )

                        playAudio(audio)
                    }
                }

            } catch (e: Exception) {
                status("LIVE RESPONSE COULD NOT BE PROCESSED")
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?
        ) {
            val details = response?.let {
                "HTTP ${it.code}: ${it.message}"
            } ?: t.message

            scheduleReconnect(1_000L, "WEBSOCKET RECONNECTING: $details")
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String
        ) {
            setupComplete = false

            if (running.get()) {
                scheduleReconnect(1_000L, "SESSION RECONNECTING: $code $reason")
            }
        }
    }

    private fun scheduleReconnect(
        delayMs: Long,
        message: String = "RECONNECTING TO MARK LIVE..."
    ) {
        if (!running.get() || stopped.get()) return
        if (!reconnectScheduled.compareAndSet(false, true)) return

        status(message)
        Thread {
            try {
                Thread.sleep(delayMs)
                if (!running.get() || stopped.get()) return@Thread

                setupComplete = false
                recorderThread?.interrupt()
                runCatching { audioRecord?.stop() }
                runCatching { audioRecord?.release() }
                audioRecord = null
                recorderThread = null

                playbackGeneration.incrementAndGet()
                playbackQueue.clear()
                playbackThread?.interrupt()
                playbackThread = null
                runCatching { audioTrack?.pause() }
                runCatching { audioTrack?.flush() }
                runCatching { audioTrack?.release() }
                audioTrack = null
                runCatching { echoCanceler?.release() }
                runCatching { noiseSuppressor?.release() }
                echoCanceler = null
                noiseSuppressor = null

                runCatching { webSocket?.cancel() }
                webSocket = null
                reconnectScheduled.set(false)
                connectToLive()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                reconnectScheduled.set(false)
            } catch (_: Exception) {
                reconnectScheduled.set(false)
                if (running.get() && !stopped.get()) {
                    scheduleReconnect(2_000L)
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun phoneControlTools(): JSONArray {
        val launchApp = JSONObject().apply {
            put("name", "launch_app")
            put(
                "description",
                "Open an installed Android app by its visible app name. Only launch the app; do not perform actions inside it."
            )
            put(
                "parameters",
                JSONObject().apply {
                    put("type", "OBJECT")
                    put(
                        "properties",
                        JSONObject().put(
                            "app_name",
                            JSONObject()
                                .put("type", "STRING")
                                .put("description", "The installed app's visible name, such as Chrome or Maps.")
                        )
                    )
                    put("required", JSONArray().put("app_name"))
                }
            )
        }

        val openSettings = JSONObject().apply {
            put("name", "open_settings_page")
            put(
                "description",
                "Open one of the supported Android Settings screens or a system control panel. For Wi-Fi, use the Android Wi-Fi panel when available; for Bluetooth, open Bluetooth Settings. Android may require the user to tap protected switches, unless the visible switch can be controlled through enabled MARK Accessibility. Never claim a setting changed without confirmation."
            )
            put(
                "parameters",
                JSONObject().apply {
                    put("type", "OBJECT")
                    put(
                        "properties",
                        JSONObject().put(
                            "page",
                            JSONObject()
                                .put("type", "STRING")
                                .put("enum", JSONArray().put("general").put("quick_settings").put("wireless").put("wifi").put("bluetooth").put("nfc").put("display").put("sound").put("notifications").put("apps").put("battery").put("battery_saver").put("location").put("security").put("privacy").put("accessibility").put("date_time").put("keyboard").put("storage").put("vpn").put("cast").put("home"))
                                .put("description", "Open a system page. Supported values include general, quick_settings, wireless, wifi, bluetooth, nfc, display, sound, notifications, apps, battery, battery_saver, location, security, privacy, accessibility, date_time, keyboard, storage, vpn, cast, or home. Android may require the user to make protected changes manually.")
                        )
                    )
                    put("required", JSONArray().put("page"))
                }
            )
        }

        val inspectScreen = JSONObject().apply {
            put("name", "inspect_visible_screen")
            put(
                "description",
                "Read the accessible tree, labels, states, and actions currently visible on the foreground screen. The response includes snapshot_id, complete, and refs for actionable elements. The tool envelope also reports status, verified, retryable, and task_state. Use this before interacting when the target is unclear."
            )
            put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        }

        val inspectVisualScreen = JSONObject().apply {
            put("name", "inspect_visual_screen")
            put("description", "Capture one current screenshot of the visible phone screen and send it to the active vision session with an 8-column by 12-row grid. Use only when inspect_visible_screen is incomplete or cannot expose a visible custom-drawn control. This is not continuous capture and does not bypass Android security screens. Requires Android 11+ and enabled MARK Accessibility.")
            put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        }

        val setFlashlight = JSONObject().apply {
            put("name", "set_flashlight")
            put("description", "Turn the phone flashlight on or off. This uses Android's flashlight API and requires the user to grant camera permission; no camera preview is opened.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject().put("enabled", JSONObject().put("type", "BOOLEAN").put("description", "True to turn the flashlight on, false to turn it off.")))
                put("required", JSONArray().put("enabled"))
            })
        }

        val captureCameraImage = JSONObject().apply {
            put("name", "capture_camera_image")
            put(
                "description",
                "Capture one still image from the rear camera and send it to the active MARK vision session. Use only after the user explicitly asks what the camera sees. This never starts continuous recording and requires camera permission."
            )
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject())
            })
        }

        val controlScreen = JSONObject().apply {
            put("name", "control_visible_screen")
            put(
                "description",
                "Perform one narrow action on the current visible screen: tap a semantic control, type into a visible text field, scroll, use Back, Home, or recent apps, or perform one guarded visual_tap on a custom-drawn target. Accessibility must be enabled by the user. For tap, type, and scroll, use the exact snapshot_id and ref returned by the latest inspect_visible_screen; stale refs are rejected. For visual_tap, use only the exact grid cell and grounding_token returned by the latest inspect_visual_screen; visual taps are single-use and repeated same-area coordinates are rejected until a fresh visual inspection."
            )
            put(
                "parameters",
                JSONObject().apply {
                    put("type", "OBJECT")
                    put(
                        "properties",
                        JSONObject()
                            .put("action", JSONObject().put("type", "STRING").put("enum", JSONArray().put("tap").put("visual_tap").put("type").put("scroll").put("back").put("home").put("overview")))
                            .put("snapshot_id", JSONObject().put("type", "STRING").put("description", "The snapshot_id returned by the latest inspect_visible_screen. Required for tap, type, and scroll."))
                            .put("ref", JSONObject().put("type", "STRING").put("description", "The exact ref returned by the latest inspect_visible_screen, such as @a1abc123:e1. Required for tap, type, and scroll."))
                            .put("target", JSONObject().put("type", "STRING").put("description", "Optional human-readable label used for confirmation context; do not use it to guess a target."))
                            .put("text", JSONObject().put("type", "STRING").put("description", "Text to type when action is type."))
                            .put("direction", JSONObject().put("type", "STRING").put("enum", JSONArray().put("up").put("down")))
                            .put("visual_cell", JSONObject().put("type", "STRING").put("description", "For visual_tap only: exact grid cell such as C7 from the latest visual inspection."))
                            .put("visual_token", JSONObject().put("type", "STRING").put("description", "For visual_tap only: exact short-lived grounding_token from the latest inspect_visual_screen result."))
                            .put("confirmed", JSONObject().put("type", "BOOLEAN").put("description", "Set true only after the user explicitly confirms a consequential action."))
                    )
                    put("required", JSONArray().put("action"))
                }
            )
        }

        val cancelTask = JSONObject().apply {
            put("name", "cancel_task")
            put("description", "Stop the current multi-step MARK task and clear its task state. Use when the user says stop, cancel, or abort.")
            put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        }

        val completeTask = JSONObject().apply {
            put("name", "complete_task")
            put("description", "Mark the current task complete only after the requested outcome has been verified. Do not use this after an unverified or failed action.")
            put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        }

        val startTask = JSONObject().apply {
            put("name", "start_task")
            put("description", "Start or reset a new multi-step MARK task before executing a complex user request.")
            put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        }

        val listFiles = JSONObject().apply {
            put("name", "list_files")
            put("description", "List files and folders in shared storage. Full storage access must be enabled by the user.")
            put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject()))
        }

        val createTextFile = JSONObject().apply {
            put("name", "create_text_file")
            put("description", "Create a new UTF-8 text file in shared storage. Full storage access must be enabled by the user.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "STRING").put("description", "File name only, without folders."))
                    .put("content", JSONObject().put("type", "STRING").put("description", "Complete text content.")))
                put("required", JSONArray().put("name").put("content"))
            })
        }

        val editTextFile = JSONObject().apply {
            put("name", "edit_text_file")
            put("description", "Replace the complete UTF-8 text content of an existing shared-storage file. Full storage access must be enabled by the user.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "STRING").put("description", "Existing file name only, without folders."))
                    .put("content", JSONObject().put("type", "STRING").put("description", "New complete text content.")))
                put("required", JSONArray().put("name").put("content"))
            })
        }

        val deleteFile = JSONObject().apply {
            put("name", "delete_file")
            put("description", "Permanently delete one file or folder from shared storage. Full storage access must be enabled by the user. Always ask the user to confirm aloud before calling this with confirmed=true.")
            put("parameters", JSONObject().apply {
                put("type", "OBJECT")
                put("properties", JSONObject()
                    .put("name", JSONObject().put("type", "STRING").put("description", "Exact file or folder name only, without folders."))
                    .put("confirmed", JSONObject().put("type", "BOOLEAN").put("description", "True only after the user explicitly confirms the exact deletion.")))
                put("required", JSONArray().put("name").put("confirmed"))
            })
        }

        return JSONArray().put(
            JSONObject().put(
                "functionDeclarations",
                JSONArray().put(launchApp).put(openSettings).put(setFlashlight).put(captureCameraImage).put(inspectScreen).put(inspectVisualScreen).put(controlScreen).put(startTask).put(cancelTask).put(completeTask).put(listFiles).put(createTextFile).put(editTextFile).put(deleteFile)
            )
        )
    }

    private fun handlePhoneToolCall(webSocket: WebSocket, toolCall: JSONObject) {
        val calls = toolCall.optJSONArray("functionCalls") ?: return
        if (calls.length() == 0) return

        mainHandler.post {
            val functionResponses = JSONArray()

            // Gemini can return several calls in one turn. Executing them all
            // immediately makes a tap/scroll happen before the new screen is
            // available, which looks like skipped task steps. Return one result
            // and let the model choose the next action from the updated screen.
            val call = calls.optJSONObject(0) ?: return@post
            val name = call.optString("name")
            val id = call.optString("id")
            val args = call.optJSONObject("args") ?: JSONObject()
            val outcome = executeTaskTool(name, args)

            val functionResponse = JSONObject().apply {
                if (id.isNotBlank()) put("id", id)
                put("name", name)
                put("response", outcome.toJson())
            }
            functionResponses.put(functionResponse)

            if (functionResponses.length() > 0) {
                val responseMessage = JSONObject().put(
                    "toolResponse",
                    JSONObject().put("functionResponses", functionResponses)
                )
                if (!webSocket.send(responseMessage.toString())) {
                    status("PHONE ACTION RESPONSE COULD NOT BE SENT")
                }
            }
        }
    }

    private fun executeTaskTool(name: String, args: JSONObject): ToolResult {
        if (name == "start_task") {
            clearTaskState()
            taskActive = true
            taskState = TaskState.READY_TO_ACT
            status("TASK STARTED")
            return ToolResult("success", "A new multi-step task has started.", taskState = taskState)
        }

        if (name == "cancel_task") {
            clearTaskState()
            taskState = TaskState.CANCELLED
            status("TASK CANCELLED")
            return ToolResult("cancelled", "The current MARK task was cancelled.", taskState = taskState)
        }

        if (name == "complete_task") {
            if (!taskActive || taskState !in setOf(TaskState.READY_TO_ACT, TaskState.VERIFYING)) {
                return ToolResult("failed", "The task cannot be completed before its requested outcome is verified.", taskState = taskState)
            }
            taskActive = false
            awaitingTaskConfirmation = false
            taskState = TaskState.COMPLETED
            status("TASK COMPLETED")
            return ToolResult("success", "The requested task outcome was verified and the task is complete.", verified = true, taskState = taskState)
        }

        if (name == "inspect_visible_screen") {
            taskState = TaskState.OBSERVING
            val outcome = executePhoneTool(name, args)
            if (!outcome.first) {
                taskState = TaskState.FAILED
                return resultFromPair(outcome, taskState)
            }
            val snapshot = runCatching { JSONObject(outcome.second) }.getOrNull()
            val complete = snapshot?.optBoolean("complete", true) ?: false
            taskState = if (complete) TaskState.READY_TO_ACT else TaskState.OBSERVING
            return ToolResult(
                status = "success",
                message = "Screen inspection completed.",
                verified = true,
                taskState = taskState
            ).withData(snapshot)
        }

        if (name == "inspect_visual_screen") {
            taskState = TaskState.OBSERVING
            val outcome = executePhoneTool(name, args)
            return resultFromPair(outcome, taskState, verified = outcome.first)
        }

        if (!isTaskAction(name)) {
            return resultFromPair(executePhoneTool(name, args), taskState)
        }

        if (!taskActive) {
            taskStepCount = 0
            awaitingTaskConfirmation = false
            taskActive = true
            taskState = TaskState.READY_TO_ACT
        }
        if (taskState in setOf(TaskState.CANCELLED, TaskState.COMPLETED, TaskState.FAILED)) {
            return ToolResult("failed", "The task is ${taskState.name.lowercase(Locale.ROOT)}. Start a new task before using phone controls.", taskState = taskState)
        }
        if (taskStepCount >= MAX_TASK_STEPS) {
            clearTaskState()
            taskState = TaskState.FAILED
            status("TASK STOPPED — TOO MANY STEPS")
            return ToolResult("failed", "The task was stopped after $MAX_TASK_STEPS steps to prevent an automation loop.", taskState = taskState)
        }

        val confirmed = args.optBoolean("confirmed", false)
        if (confirmed && !awaitingTaskConfirmation) {
            return ToolResult("failed", "Confirmation was not requested for a pending sensitive action. Ask the user before retrying.", taskState = taskState)
        }

        if (isPotentiallySensitive(name, args) && !confirmed) {
            taskStepCount += 1
            awaitingTaskConfirmation = true
            taskState = TaskState.WAITING_FOR_CONFIRMATION
            status("CONFIRMATION REQUIRED")
            return ToolResult("confirmation_required", "Confirmation required before this action. Ask the user to confirm, then repeat the same tool call with confirmed=true.", taskState = taskState)
        }

        taskStepCount += 1
        taskState = TaskState.EXECUTING
        status("TASK STEP $taskStepCount/$MAX_TASK_STEPS")
        val outcome = executePhoneTool(name, args)

        if (outcome.first && confirmed) {
            awaitingTaskConfirmation = false
        }

        if (outcome.first) {
            taskState = TaskState.VERIFYING
        } else {
            taskState = TaskState.FAILED
            status("TASK STEP FAILED")
        }
        return resultFromPair(outcome, taskState, verified = outcome.first && name != "control_visible_screen")
    }

    private fun resultFromPair(
        outcome: Pair<Boolean, String>,
        state: TaskState,
        verified: Boolean = false
    ): ToolResult = if (outcome.first) {
        ToolResult("success", outcome.second, verified = verified, taskState = state)
    } else {
        val lower = outcome.second.lowercase(Locale.ROOT)
        val status = when {
            "permission" in lower || "accessibility" in lower -> "permission_required"
            "stale" in lower -> "stale_ref"
            "ambiguous" in lower -> "ambiguous_target"
            "disabled" in lower -> "disabled_target"
            "not found" in lower -> "not_found"
            else -> "failed"
        }
        val retryable = lower.contains("timeout") || lower.contains("temporarily") || lower.contains("try again")
        ToolResult(status, outcome.second, retryable = retryable, taskState = state)
    }

    private fun isTaskAction(name: String): Boolean =
        name == "launch_app" || name == "open_settings_page" || name == "set_flashlight" || name == "capture_camera_image" || name == "control_visible_screen" ||
            name == "list_files" || name == "create_text_file" ||
            name == "edit_text_file" || name == "delete_file"

    private fun isPotentiallySensitive(name: String, args: JSONObject): Boolean {
        if (name == "delete_file") return true
        if (name != "control_visible_screen") return false
        val value = listOf(
            args.optString("action"),
            args.optString("target"),
            args.optString("text")
        ).joinToString(" ").lowercase(Locale.ROOT)
        return listOf(
            "send", "submit", "purchase", "buy", "pay", "delete", "remove",
            "call", "share", "post", "publish", "transfer", "confirm", "install"
        ).any(value::contains)
    }

    private fun clearTaskState() {
        taskActive = false
        taskStepCount = 0
        awaitingTaskConfirmation = false
        taskState = TaskState.IDLE
    }

    private fun executePhoneTool(name: String, args: JSONObject): Pair<Boolean, String> {
        return try {
            when (name) {
                "launch_app" -> launchInstalledApp(args.optString("app_name"))
                "open_settings_page" -> openSettingsPage(args.optString("page"))
                "set_flashlight" -> setFlashlight(args.optBoolean("enabled", false))
                "capture_camera_image" -> captureCameraImage()
                "inspect_visual_screen" -> captureVisualScreen()
                "list_files" -> fileManager.listFiles()
                "create_text_file" -> fileManager.createTextFile(
                    args.optString("name"),
                    args.optString("content")
                )
                "edit_text_file" -> fileManager.editTextFile(
                    args.optString("name"),
                    args.optString("content")
                )
                "delete_file" -> fileManager.deleteFile(args.optString("name"))
                "inspect_visible_screen" -> if (JarvisAccessibilityService.isEnabled()) {
                    true to JarvisAccessibilityService.inspectScreen()
                } else {
                    false to "Accessibility control is not enabled. Enable MARK in Android Accessibility settings first."
                }
                "control_visible_screen" -> JarvisAccessibilityService.control(
                    snapshotId = args.optString("snapshot_id"),
                    ref = args.optString("ref"),
                    action = args.optString("action"),
                    target = args.optString("target"),
                    text = args.optString("text"),
                    direction = args.optString("direction"),
                    confirmed = args.optBoolean("confirmed", false),
                    visualCell = args.optString("visual_cell"),
                    visualToken = args.optString("visual_token")
                )
                else -> false to "This phone action is not supported."
            }
        } catch (_: Exception) {
            false to "Android could not open that app or Settings page."
        }
    }

    /** Capture one screenshot and deliver it to Gemini as a user vision turn. */
    private fun captureVisualScreen(): Pair<Boolean, String> {
        val socket = webSocket ?: return false to "The live MARK session is not connected."
        if (!JarvisAccessibilityService.isEnabled()) {
            return false to "Accessibility control is not enabled. Enable MARK in Android Accessibility settings first."
        }
        val (success, capture) = JarvisAccessibilityService.captureVisualScreen()
        if (!success || capture == null) {
            return false to if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                "Visual screen capture requires Android 11 or newer."
            } else {
                "Android denied or could not complete the one-shot screen capture. Try again after the visible screen settles."
            }
        }
        val message = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray()
                        .put(JSONObject().put("text", "Inspect this single current phone screenshot only as a visual fallback. It includes an 8-column by 12-row grid: columns A-H and rows 1-12. Describe visible custom-drawn controls and their approximate grid cells. Do not infer hidden, protected, or unreadable content, and do not claim that an action was performed."))
                        .put(JSONObject().put("inlineData", JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("data", capture.jpegBase64)))
                    )
                }))
                put("turnComplete", true)
            })
        }
        if (!socket.send(message.toString())) {
            return false to "The visual screenshot could not be sent to the live vision session."
        }
        status("VISUAL SCREEN SENT ${capture.width}x${capture.height}")
        return true to "One visual screen inspection was sent to the live vision session with an 8 by 12 grounding grid. grounding_token=${capture.token}. Use this token only for one clearly visible visual_tap, then inspect again."
    }

    private fun launchInstalledApp(requestedName: String): Pair<Boolean, String> {
        val target = normalizeAppName(requestedName)
        if (target.isBlank()) return false to "Please specify an app name."

        val packageManager = context.packageManager
        val launcherQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(launcherQuery, 0)
            .firstOrNull { resolveInfo ->
                val visibleName = resolveInfo.loadLabel(packageManager).toString()
                normalizeAppName(visibleName) == target ||
                    normalizeAppName(resolveInfo.activityInfo.packageName) == target
            }
            ?: return false to "I couldn't find an installed app named $requestedName."

        val packageName = match.activityInfo.packageName
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return false to "That app does not provide a launch screen."

        context.startActivity(launchIntent)
        status("OPENING ${match.loadLabel(packageManager).toString().uppercase(Locale.ROOT)}")
        return true to "Opened ${match.loadLabel(packageManager)}."
    }

    private fun openSettingsPage(page: String): Pair<Boolean, String> {
        val action = when (page.lowercase(Locale.ROOT)) {
            "general" -> Settings.ACTION_SETTINGS
            "quick_settings" -> "android.settings.QUICK_SETTINGS"
            "wireless" -> Settings.ACTION_WIRELESS_SETTINGS
            "wifi" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "notifications" -> "android.settings.NOTIFICATION_SETTINGS"
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "battery" -> "android.settings.BATTERY_SETTINGS"
            "battery_saver" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "privacy" -> Settings.ACTION_PRIVACY_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "date_time" -> Settings.ACTION_DATE_SETTINGS
            "keyboard" -> Settings.ACTION_INPUT_METHOD_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "vpn" -> Settings.ACTION_VPN_SETTINGS
            "cast" -> Settings.ACTION_CAST_SETTINGS
            "home" -> Settings.ACTION_HOME_SETTINGS
            else -> return false to "That Settings page is not supported."
        }

        val intent = Intent(action)
        if (intent.resolveActivity(context.packageManager) == null) {
            return false to "That Settings page is not available on this device."
        }

        context.startActivity(intent)
        status("OPENING SETTINGS")
        return true to "Opened the $page Settings page."
    }

    private fun setFlashlight(enabled: Boolean): Pair<Boolean, String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return false to "Camera permission is required for flashlight control. Enable it in MARK App info, then try again."
        }

        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false to "This phone does not expose a controllable flashlight."
            cameraManager.setTorchMode(cameraId, enabled)
            status(if (enabled) "FLASHLIGHT ON" else "FLASHLIGHT OFF")
            true to if (enabled) "Flashlight turned on." else "Flashlight turned off."
        } catch (_: SecurityException) {
            false to "Android denied flashlight access. Grant camera permission in MARK App info."
        } catch (_: Exception) {
            false to "The flashlight could not be changed while another camera app is using it."
        }
    }

    /**
     * Capture exactly one JPEG from the rear camera and send it to the active
     * Gemini Live session. The camera is closed immediately after the frame is
     * received; this never starts a preview or a background camera loop.
     */
    private fun captureCameraImage(): Pair<Boolean, String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return false to "Camera permission is required. Grant it in MARK App info, then try again."
        }

        val socket = webSocket ?: return false to "The live MARK session is not connected."
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false to "Camera service is unavailable on this device."
        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()
        } catch (_: Exception) {
            null
        } ?: return false to "No camera is available on this device."

        val reader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
        val cameraThread = HandlerThread("JarvisCameraCapture").apply { start() }
        val cameraHandler = Handler(cameraThread.looper)
        val finished = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val succeeded = AtomicBoolean(false)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null

        fun finish(success: Boolean) {
            if (!completed.compareAndSet(false, true)) return
            succeeded.set(success)
            session?.close()
            camera?.close()
            reader.close()
            cameraThread.quitSafely()
            status(if (success) "CAMERA IMAGE SENT" else "CAMERA CAPTURE FAILED")
            finished.countDown()
        }

        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes.firstOrNull()?.buffer
                    ?: return@setOnImageAvailableListener
                val jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                val message = JSONObject().apply {
                    put("clientContent", JSONObject().apply {
                        put("turns", JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray()
                                    .put(JSONObject().put(
                                        "text",
                                        "Look at this camera image and answer the user's camera question. Describe only what is visibly present, and say clearly if something cannot be determined from the image."
                                    ))
                                    .put(JSONObject().put(
                                        "inlineData",
                                        JSONObject()
                                            .put("mimeType", "image/jpeg")
                                            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                                    ))
                                )
                            }
                        ))
                        put("turnComplete", true)
                    })
                }
                finish(socket.send(message.toString()))
            } catch (_: Exception) {
                finish(false)
            } finally {
                image.close()
            }
        }, cameraHandler)

        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    try {
                        device.createCaptureSession(
                            listOf(reader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(newSession: CameraCaptureSession) {
                                    session = newSession
                                    try {
                                        val request = device.createCaptureRequest(
                                            CameraDevice.TEMPLATE_STILL_CAPTURE
                                        ).apply {
                                            addTarget(reader.surface)
                                        }.build()
                                        newSession.capture(
                                            request,
                                            object : CameraCaptureSession.CaptureCallback() {},
                                            cameraHandler
                                        )
                                    } catch (_: Exception) {
                                        finish(false)
                                    }
                                }

                                override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                                    finish(false)
                                }
                            },
                            cameraHandler
                        )
                    } catch (_: Exception) {
                        finish(false)
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    finish(false)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    finish(false)
                }
            }, cameraHandler)
        } catch (_: SecurityException) {
            finish(false)
        } catch (_: Exception) {
            finish(false)
        }

        if (!finished.await(8, TimeUnit.SECONDS)) finish(false)
        return if (succeeded.get()) {
            true to "Captured one camera image and sent it to MARK for analysis."
        } else {
            false to "The camera capture could not be completed."
        }
    }

    private fun normalizeAppName(name: String): String =
        name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun startAudioCapture() {
        if (!running.get() || !setupComplete) return

        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBuffer <= 0) {
                throw IOException("Invalid microphone buffer size")
            }

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, 3200)
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IOException("Microphone initialization failed")
            }

            audioRecord = recorder

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler =
                    AcousticEchoCanceler.create(recorder.audioSessionId)

                echoCanceler?.enabled = true
            }

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor =
                    NoiseSuppressor.create(recorder.audioSessionId)

                noiseSuppressor?.enabled = true
            }

            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE)
                    as AudioManager

            // Keep output on Android's normal media route so volume buttons
            // control MARK playback with the media/music volume stream.
            audioManager.mode = AudioManager.MODE_NORMAL

            recorder.startRecording()

            recorderThread = Thread {
                // 1600 bytes is 50 ms of mono 16-bit PCM at 16 kHz. Smaller
                // packets reduce time-to-first-response without creating a
                // high-rate flood of WebSocket messages.
                val buffer = ByteArray(1600)

                while (running.get() && setupComplete) {
                    val count = recorder.read(
                        buffer,
                        0,
                        buffer.size
                    )

                    if (
                        count > 0 &&
                        running.get() &&
                        setupComplete
                    ) {
                        val playbackStillSettling = suppressMicrophone && (
                            playbackQueue.isNotEmpty() ||
                                System.nanoTime() - lastPlaybackChunkAtNanos < 350_000_000L
                            )

                        if (playbackStillSettling) {
                            // Keep reading so AudioRecord does not accumulate
                            // stale speaker audio. Android's VOICE_COMMUNICATION
                            // source applies AEC/NS; sustained residual speech
                            // is treated as a user barge-in, while short echo
                            // fragments are discarded locally.
                            if (detectBargeIn(buffer, count)) {
                                interruptPlayback()
                            } else {
                                continue
                            }
                        }

                        suppressMicrophone = false
                        val chunk = ByteArray(count)

                        System.arraycopy(
                            buffer,
                            0,
                            chunk,
                            0,
                            count
                        )

                        val encoded = Base64.encodeToString(
                            chunk,
                            Base64.NO_WRAP
                        )

                        val message = JSONObject().apply {
                            put(
                                "realtimeInput",
                                JSONObject().apply {
                                    put(
                                        "audio",
                                        JSONObject().apply {
                                            put(
                                                "mimeType",
                                                "audio/pcm;rate=16000"
                                            )
                                            put("data", encoded)
                                        }
                                    )
                                }
                            )
                        }

                        if (webSocket?.send(message.toString()) != true) {
                            fail("AUDIO SEND FAILED")
                            break
                        }
                    } else if (count < 0) {
                        fail("MICROPHONE READ ERROR: $count")
                        break
                    }
                }
            }

            recorderThread?.start()

        } catch (e: Exception) {
            fail("MICROPHONE ERROR: ${e.message}")
        }
    }

    private fun detectBargeIn(buffer: ByteArray, count: Int): Boolean {
        var sumSquares = 0.0
        var samples = 0
        var index = 0

        while (index + 1 < count) {
            val sample = ((buffer[index + 1].toInt() shl 8) or
                (buffer[index].toInt() and 0xff)).toShort().toDouble()
            sumSquares += sample * sample
            samples++
            index += 2
        }

        if (samples == 0) return false

        val rms = kotlin.math.sqrt(sumSquares / samples)
        if (!suppressMicrophone) {
            ambientRms = ambientRms * 0.95 + rms * 0.05
        }

        val speechThreshold = maxOf(BARGE_IN_MIN_RMS, ambientRms * 3.0)
        if (rms >= speechThreshold) {
            bargeInSpeechFrames++
        } else {
            bargeInSpeechFrames = 0
        }

        return bargeInSpeechFrames >= BARGE_IN_REQUIRED_FRAMES
    }

    private fun startPlayback() {
        if (playbackThread?.isAlive == true) return

        playbackThread = Thread({
            var track: AudioTrack? = null
            try {
                val minBuffer = AudioTrack.getMinBufferSize(
                    OUTPUT_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBuffer <= 0) {
                    throw IOException("Invalid speaker buffer size")
                }

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OUTPUT_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer, 4800))
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    throw IOException("Speaker initialization failed")
                }

                audioTrack = track
                track.play()

                while (running.get() && setupComplete) {
                    val chunk = playbackQueue.take()
                    if (chunk.generation != playbackGeneration.get()) continue

                    var offset = 0
                    while (
                        offset < chunk.bytes.size &&
                        running.get() &&
                        setupComplete &&
                        chunk.generation == playbackGeneration.get()
                    ) {
                        val writeSize = minOf(2048, chunk.bytes.size - offset)
                        val written = track.write(
                            chunk.bytes,
                            offset,
                            writeSize,
                            AudioTrack.WRITE_BLOCKING
                        )

                        if (written > 0) {
                            offset += written
                        } else {
                            throw IOException("Speaker playback failed")
                        }
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                if (running.get() && setupComplete) {
                    status("SPEAKER PLAYBACK ERROR")
                }
            } finally {
                if (audioTrack === track) audioTrack = null
                try {
                    track?.release()
                } catch (_: Exception) {
                }
            }
        }, "MARK-Audio-Playback").apply {
            isDaemon = true
            start()
        }
    }

    private fun playAudio(bytes: ByteArray) {
        if (bytes.isEmpty() || !setupComplete || !voiceResponsesEnabled()) return

        suppressMicrophone = true
        lastPlaybackChunkAtNanos = System.nanoTime()
        val chunk = PlaybackChunk(playbackGeneration.get(), bytes)
        // Do not discard an older buffered speech chunk: dropping one creates
        // an audible word or syllable gap. If the queue is briefly saturated,
        // discard only the newest fragment rather than blocking WebSocket input.
        playbackQueue.offerLast(chunk)
    }

    private fun interruptPlayback() {
        playbackGeneration.incrementAndGet()
        playbackQueue.clear()
        suppressMicrophone = false
        bargeInSpeechFrames = 0

        try {
            audioTrack?.let { track ->
                track.pause()
                track.flush()
                if (running.get() && setupComplete) track.play()
            }
        } catch (_: Exception) {
        }
    }

    private fun status(message: String) {
        mainHandler.post {
            onStatus(message)
        }
    }

    private fun voiceResponsesEnabled(): Boolean =
        context.getSharedPreferences("jarvis_ui", Context.MODE_PRIVATE)
            .getBoolean("voice_responses", true)

    private fun fail(message: String) {
        running.set(false)
        setupComplete = false
        clearTaskState()
        status(message)

        try {
            webSocket?.close(1000, "Connection error")
        } catch (_: Exception) {
        }
    }

    fun stopListening() {
        stop()
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        setupComplete = false
        clearTaskState()
        suppressMicrophone = false
        bargeInSpeechFrames = 0

        try {
            recorderThread?.interrupt()
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord = null
        recorderThread = null

        playbackGeneration.incrementAndGet()
        playbackQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null

        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }

        echoCanceler = null
        noiseSuppressor = null

        try {
            webSocket?.close(1000, "User stopped")
        } catch (_: Exception) {
        }

        webSocket = null

        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE)
                as AudioManager

        audioManager.mode = AudioManager.MODE_NORMAL

        status("VOICE MODE OFF")
    }

    fun release() {
        stop()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
