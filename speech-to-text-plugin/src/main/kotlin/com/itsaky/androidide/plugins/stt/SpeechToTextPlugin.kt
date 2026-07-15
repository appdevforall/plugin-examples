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
import com.itsaky.androidide.plugins.extensions.ShowAsAction
import com.itsaky.androidide.plugins.extensions.ToolbarAction
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeUIService
import com.itsaky.androidide.plugins.services.LlmInferenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
class SpeechToTextPlugin : IPlugin, UIExtension {

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
    }

    // region UIExtension ------------------------------------------------------

    /**
     * Contributes the "Voice to Code" button to the editor toolbar. The IDE
     * registers this via [UIExtension] and shows it while an editor is open.
     */
    override fun getToolbarActions(): List<ToolbarAction> = listOf(
        ToolbarAction(
            id = "stt_voice_to_code",
            title = "Voice to Code",
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

    // endregion ---------------------------------------------------------------

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
            toast("Open a file in the editor to insert voice input.")
            return
        }

        if (!hasMicrophonePermission()) {
            requestMicrophonePermission()
            toast("Microphone permission needed. Grant it, then tap again.")
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            toast("Speech recognition is not available on this device.")
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
            toast("Listening… speak your command.")
            recognizer.startListening(intent)
            setState(RecordingState.RECORDING)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition", e)
            toast("Could not start recording: ${e.message}")
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
                toast("Didn't catch that. Try again.")
                setState(RecordingState.IDLE)
                return
            }
            setState(RecordingState.PROCESSING)
            handleTranscript(transcript)
        }

        override fun onError(error: Int) {
            destroyRecognizer()
            toast("Recording error: ${describeError(error)}")
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
            val output = if (llmService != null) {
                generateCodeFromVoice(transcript)
            } else {
                transcript
            }
            withContext(Dispatchers.Main) {
                try {
                    if (output.startsWith("Error:")) {
                        toast(output)
                        return@withContext
                    }
                    val inserted = insertCodeAtCursor(output)
                    toast(
                        if (inserted) "Inserted from voice."
                        else "Recognized: \"$transcript\" (open a file to insert)."
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
     * @return Generated code snippet or error message
     */
    suspend fun generateCodeFromVoice(voiceText: String, language: String = "kotlin"): String {
        return try {
            if (llmService == null) {
                return "Error: LlmInferenceService not available"
            }

            // Build a completion prompt for code generation
            val prompt = """
                User request: $voiceText

                Generate $language code to fulfill this request. Return only the code, no explanation.
                Code:
            """.trimIndent()

            val config = LlmInferenceService.LlmConfig("local")
            val response = llmService!!.generateCompletion(prompt, config).get()
            if (response.success) {
                response.text.trim()
            } else {
                "Error: ${response.error}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating code from voice", e)
            "Error: ${e.message}"
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

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK -> "network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no speech matched"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech detected"
        else -> "code $error"
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

    private fun toast(message: String) = runOnMain {
        try {
            Toast.makeText(hostContext(), message, Toast.LENGTH_SHORT).show()
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
    }
}
