package com.itsaky.androidide.plugins.stt

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.ShowAsAction
import com.itsaky.androidide.plugins.extensions.ToolbarAction
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeUIService
import com.itsaky.androidide.plugins.services.LlmInferenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SpeechToTextPlugin"

/**
 * Speech-to-Text Plugin provides voice-to-code capabilities.
 *
 * Features:
 * - Voice capture via Android [SpeechRecognizer] (on-device when available)
 * - Optional LLM-based code generation from the transcript
 * - Editor integration for inserting the result at the cursor
 *
 * The plugin surfaces a "Voice to Code" action in the editor toolbar by
 * implementing [UIExtension.getToolbarActions]. The action is only visible
 * while a file is open in the editor.
 */
class SpeechToTextPlugin : IPlugin, UIExtension, DocumentationExtension {

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var context: PluginContext
    private var llmService: LlmInferenceService? = null
    private var editorService: IdeEditorService? = null
    private var uiService: IdeUIService? = null

    /** Held only between startListening() and the terminal result/error callback. */
    private var speechRecognizer: SpeechRecognizer? = null

    /** Drives the toolbar icon via [ToolbarAction.iconProvider]. */
    private enum class RecordingState { IDLE, RECORDING, PROCESSING }

    @Volatile
    private var recordingState = RecordingState.IDLE

    /**
     * Updates the recording state and asks the IDE to rebuild the toolbar so the
     * icon provider is re-evaluated and the new (possibly animated) icon is shown.
     */
    private fun setState(state: RecordingState) {
        recordingState = state
        uiService?.refreshToolbarActions()
    }

    private fun currentIconRes(): Int = when (recordingState) {
        RecordingState.IDLE -> R.drawable.ic_mic
        RecordingState.RECORDING -> R.drawable.ic_waves
        RecordingState.PROCESSING -> R.drawable.ic_processing
    }

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        Log.i(TAG, "SpeechToTextPlugin initialized")
        return true
    }

    override fun activate(): Boolean {
        Log.i(TAG, "SpeechToTextPlugin activating...")

        // Get services from plugin context
        llmService = context.services.get(LlmInferenceService::class.java)
        editorService = context.services.get(IdeEditorService::class.java)
        uiService = context.services.get(IdeUIService::class.java)

        if (llmService == null) {
            Log.w(TAG, "LlmInferenceService not available - voice generation disabled")
        }
        if (editorService == null) {
            Log.w(TAG, "IdeEditorService not available - editor integration disabled")
        }
        if (uiService == null) {
            Log.w(TAG, "IdeUIService not available - toolbar icon will not animate between states")
        }

        Log.i(TAG, "SpeechToTextPlugin activated")
        return true
    }

    override fun deactivate(): Boolean {
        Log.i(TAG, "SpeechToTextPlugin deactivating")
        destroyRecognizer()
        return true
    }

    override fun dispose() {
        Log.i(TAG, "SpeechToTextPlugin disposed")
        destroyRecognizer()
        // Tear down the transcript-processing scope so no LLM/generation coroutine
        // outlives the plugin after unload.
        scope.cancel()
    }

    /**
     * Contributes the "Voice to Code" button to the editor toolbar. The IDE
     * registers this via [UIExtension] and shows it while an editor is open.
     */
    override fun getToolbarActions(): List<ToolbarAction> = listOf(
        ToolbarAction(
            id = TOOLBAR_ACTION_ID,
            title = str(R.string.stt_action_title),
            // Static fallback for hosts that don't support iconProvider.
            icon = R.drawable.ic_mic,
            showAsAction = ShowAsAction.IF_ROOM,
            order = 100,
            action = { startVoiceCapture() }
        ).apply {
            // Dynamic icon: mic (idle) -> animated waves (recording) -> spinner (processing).
            iconProvider = { currentIconRes() }
            // Voice-to-code inserts into the active editor, so the button is only usable
            // while a file is open. The host greys it out and blocks taps when this is false,
            // and re-evaluates it whenever the toolbar is rebuilt (including editor changes).
            isEnabledProvider = { hasOpenFile() }
        }
    )

    /** True when there is a file open in the editor to insert transcribed text into. */
    private fun hasOpenFile(): Boolean = try {
        editorService?.getCurrentFile() != null
    } catch (e: Exception) {
        false
    }
    override fun getTooltipCategory(): String = "plugin_speech_to_text"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLBAR_ACTION_ID,
            summary = "Voice to Code: tap, speak, and insert the transcript — or code generated from it — at the cursor.",
            detail = """
                <p>The <b>microphone</b> button in the editor toolbar records a
                short voice command and inserts the result at the cursor.</p>
                <p>Recognition uses Android's on-device recognizer when available.
                If the <b>AI Core</b> plugin is installed, the transcript is turned
                into code by the model; otherwise the raw transcript is inserted.</p>
                <p>The button is enabled only while a file is open, and microphone
                permission is requested on first use.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "Speech to Text guide",
                    uri = "index.html",
                    order = 0
                )
            )
        )
    )

    /** Subdirectory under src/main/assets/ holding the Tier 3 offline docs. */
    override fun getTier3DocsAssetPath(): String = "docs"

    /**
     * Entry point for the toolbar action. Runs on the UI thread (the toolbar
     * action item requires it), which is also required to construct and drive
     * [SpeechRecognizer].
     */
    private fun startVoiceCapture() {
        val ctx = hostContext()

        // Belt-and-suspenders: the toolbar already disables the button when no file is open,
        // but guard here too so a stale enabled state can't start a pointless recording.
        if (!hasOpenFile()) {
            toast(str(R.string.stt_need_open_file))
            return
        }

        if (!hasMicrophonePermission()) {
            requestMicrophonePermission()
            toast(str(R.string.stt_need_mic_permission))
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            toast(str(R.string.stt_recognition_unavailable))
            return
        }

        try {
            destroyRecognizer()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer.setRecognitionListener(recognitionListener)
            speechRecognizer = recognizer

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                // Prefer on-device recognition; falls back to network if unsupported.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            toast(str(R.string.stt_listening))
            recognizer.startListening(intent)
            setState(RecordingState.RECORDING)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition", e)
            toast(str(R.string.stt_start_failed))
            destroyRecognizer()
            setState(RecordingState.IDLE)
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val transcript = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            destroyRecognizer()
            if (transcript.isNullOrBlank()) {
                toast(str(R.string.stt_no_words))
                setState(RecordingState.IDLE)
                return
            }
            setState(RecordingState.PROCESSING)
            handleTranscript(transcript)
        }

        override fun onError(error: Int) {
            destroyRecognizer()
            toast(describeError(error))
            setState(RecordingState.IDLE)
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Takes the recognized text, optionally runs it through the LLM to produce
     * code, and inserts the result at the cursor.
     */
    private fun handleTranscript(transcript: String) {
        Log.i(TAG, "Recognized: $transcript")
        scope.launch {
            // With AI Core present we try to generate code; a null result means generation
            // failed (details are logged). Without it, we insert the raw transcript.
            val output = if (llmService != null) {
                generateCodeFromVoice(transcript)
            } else {
                transcript
            }
            withContext(Dispatchers.Main) {
                try {
                    if (output == null) {
                        toast(str(R.string.stt_generation_failed))
                        return@withContext
                    }
                    val inserted = insertCodeAtCursor(output)
                    toast(
                        if (inserted) str(R.string.stt_inserted)
                        else str(R.string.stt_recognized_no_file, transcript)
                    )
                } finally {
                    // Back to idle (mic) regardless of how processing ended.
                    setState(RecordingState.IDLE)
                }
            }
        }
    }

    /**
     * Generates code from a voice command using the LLM.
     *
     * @param voiceText The transcribed text from speech-to-text
     * @param language Programming language context (e.g., "kotlin", "java")
     * @return Generated code snippet, or null if generation failed (details are logged).
     */
    suspend fun generateCodeFromVoice(voiceText: String, language: String = "kotlin"): String? {
        return try {
            val service = llmService ?: run {
                Log.w(TAG, "LlmInferenceService not available - cannot generate code")
                return null
            }

            // Build a completion prompt for code generation
            val prompt = """
                User request: $voiceText

                Generate $language code to fulfill this request. Return only the code, no explanation.
                Code:
            """.trimIndent()

            val config = LlmInferenceService.LlmConfig("local")
            val response = service.generateCompletion(prompt, config).get()
            if (response.success) {
                response.text.trim()
            } else {
                Log.w(TAG, "Code generation failed: ${response.error}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating code from voice", e)
            null
        }
    }

    /**
     * Inserts generated code at the cursor position.
     */
    fun insertCodeAtCursor(code: String): Boolean {
        // insertTextAtCursor throws SecurityException if the plugin lacks FILESYSTEM_WRITE
        // (declared in the manifest). Never let a service error crash the host process.
        return try {
            editorService?.insertTextAtCursor(code) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to insert text at cursor", e)
            false
        }
    }

    /**
     * Recognizes voice commands (REFACTOR, CREATE_CLASS, etc.).
     * Returns the intent type or null if no match.
     */
    fun recognizeIntent(voiceText: String): String? {
        val text = voiceText.lowercase()
        return when {
            text.contains("refactor") -> "REFACTOR"
            text.contains("create") && (text.contains("class") || text.contains("function")) -> "CREATE_CLASS"
            text.contains("undo") -> "UNDO"
            text.contains("redo") -> "REDO"
            text.contains("format") -> "FORMAT"
            text.contains("delete") || text.contains("remove") -> "DELETE"
            else -> null
        }
    }

    /**
     * Checks if microphone permission is granted.
     * @return true if RECORD_AUDIO permission is granted, false otherwise
     */
    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            hostContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Requests microphone permission from the user.
     * Returns immediately if already granted.
     */
    fun requestMicrophonePermission() {
        if (hasMicrophonePermission()) {
            Log.d(TAG, "Microphone permission already granted")
            return
        }

        Log.d(TAG, "Requesting microphone permission...")
        try {
            // Must be the host Activity: the plugin's androidContext is a ContextThemeWrapper,
            // never an Activity, and RECORD_AUDIO is owned by the host app's UID.
            val activity = hostActivity()
            if (activity != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    PERMISSION_REQUEST_CODE
                )
            } else {
                Log.w(TAG, "No host Activity available to request microphone permission")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting microphone permission", e)
        }
    }

    private fun destroyRecognizer() {
        val recognizer = speechRecognizer ?: return
        speechRecognizer = null
        runOnMain {
            try {
                recognizer.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to destroy SpeechRecognizer", e)
            }
        }
    }

    /**
     * Maps a [SpeechRecognizer] error code to a full, user-facing message that says
     * what went wrong and what the user can do about it.
     */
    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> str(R.string.stt_error_audio)
        SpeechRecognizer.ERROR_CLIENT -> str(R.string.stt_error_client)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> str(R.string.stt_error_permissions)
        SpeechRecognizer.ERROR_NETWORK -> str(R.string.stt_error_network)
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> str(R.string.stt_error_network_timeout)
        SpeechRecognizer.ERROR_NO_MATCH -> str(R.string.stt_error_no_match)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> str(R.string.stt_error_busy)
        SpeechRecognizer.ERROR_SERVER -> str(R.string.stt_error_server)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> str(R.string.stt_error_speech_timeout)
        else -> str(R.string.stt_error_unknown, error)
    }

    /**
     * The foreground host Activity, if any. Required for anything that talks to the
     * window manager or requests runtime permissions — the plugin's own
     * [PluginContext.androidContext] reports the plugin package while running under the
     * host UID, which the framework rejects (SecurityException: package not in UID).
     */
    private fun hostActivity(): Activity? = uiService?.getCurrentActivity()

    /**
     * A host-owned Context (package + UID match the running process). Prefer the
     * foreground Activity; fall back to the host application context, which the
     * plugin resource context delegates to.
     */
    private fun hostContext(): Context =
        hostActivity() ?: context.androidContext.applicationContext

    /**
     * Resolves a plugin string resource. Uses [PluginContext.androidContext] (which carries the
     * plugin's own resources) rather than the host Context, whose resources don't include ours.
     */
    private fun str(resId: Int, vararg formatArgs: Any): String =
        context.androidContext.getString(resId, *formatArgs)

    private fun toast(message: String) = runOnMain {
        try {
            Toast.makeText(hostContext(), message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show toast", e)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val TOOLBAR_ACTION_ID = "stt_voice_to_code"
    }
}
