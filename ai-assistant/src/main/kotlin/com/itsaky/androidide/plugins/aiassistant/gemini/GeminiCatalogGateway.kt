package com.itsaky.androidide.plugins.aiassistant.gemini

import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The one place ai-assistant asks ai-core's Gemini backend for a model catalog.
 *
 * An abstraction the ViewModel can fake in tests, so the unchecked cross-classloader contract
 * lives behind a single seam that fails in one recognisable way.
 */
interface GeminiCatalogGateway {

    /**
     * Models available to the key currently saved on disk. Used to populate the model picker,
     * where "which key" is never in question.
     */
    fun listModelsForSavedKey(): CatalogResult

    /**
     * Models available to [apiKey], which need not be — and during key entry is not — the saved
     * one. This is what makes checking a key before persisting it possible.
     */
    fun listModels(apiKey: String): CatalogResult
}

/**
 * [GeminiCatalogGateway] over ai-core's `GeminiBackend`, reached by reflection.
 *
 * `listModels` isn't on [LlmInferenceService.LlmBackend], so this is an unchecked contract: every
 * break is a [CatalogResult.Failed], never an empty catalog that would read as "this key works".
 *
 * @param backendProvider resolves the "gemini" backend; injectable so tests need no SharedServices
 */
class ReflectiveGeminiCatalogGateway(
    private val backendProvider: () -> Any? = ::resolveGeminiBackend
) : GeminiCatalogGateway {

    companion object {
        private const val TAG = "GeminiCatalogGateway"

        /** Backend id registered by ai-core's `GeminiBackend.getId()`. */
        private const val BACKEND_ID = "gemini"

        private const val METHOD_LIST_MODELS = "listModels"

        /**
         * Failsafe cap, well above ai-core's own budget (15 s connect + 15 s read, paginated) so a
         * slow-but-live fetch is never truncated. Bounds a future that may never complete, such as
         * one from an already-cancelled ai-core scope; not the expected wait.
         */
        private const val LIST_MODELS_TIMEOUT_SECONDS = 60L

        /** Default [backendProvider]: the live lookup through the shared service registry. */
        private fun resolveGeminiBackend(): Any? =
            SharedServices.get(LlmInferenceService::class.java)?.getBackend(BACKEND_ID)
    }

    override fun listModelsForSavedKey(): CatalogResult =
        callListModels(paramTypes = emptyArray(), args = emptyArray())

    /**
     * No fallback to the no-arg `listModels()` when ai-core is too old to have this overload: that
     * authenticates with the *saved* key, clearing a candidate on a different credential.
     */
    override fun listModels(apiKey: String): CatalogResult =
        callListModels(paramTypes = arrayOf(String::class.java), args = arrayOf(apiKey))

    /**
     * Invoke `listModels` with the given signature and await its future.
     *
     * Blocks on [CompletableFuture.get], so call it from an IO dispatcher — never the main thread.
     */
    private fun callListModels(paramTypes: Array<Class<*>>, args: Array<Any>): CatalogResult {
        val backend = try {
            backendProvider()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Could not resolve the '$BACKEND_ID' backend", e)
            return CatalogResult.Failed(e)
        } ?: return CatalogResult.NoBackend

        val method = try {
            backend.javaClass.getMethod(METHOD_LIST_MODELS, *paramTypes)
        } catch (e: NoSuchMethodException) {
            val signature = paramTypes.joinToString { it.simpleName }
            android.util.Log.e(
                TAG,
                "ai-core's ${backend.javaClass.name} has no $METHOD_LIST_MODELS($signature): the " +
                    "cross-plugin contract changed. Expected " +
                    "`fun listModels($signature): CompletableFuture<List<String>>`.",
                e
            )
            return CatalogResult.Failed(e)
        }

        val raw = try {
            method.invoke(backend, *args)
        } catch (e: InvocationTargetException) {
            // Unwrap: the interesting failure is the one listModels threw, not the wrapper.
            val cause = e.cause ?: e
            android.util.Log.e(TAG, "$METHOD_LIST_MODELS threw", cause)
            return CatalogResult.Failed(cause)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Could not invoke $METHOD_LIST_MODELS", e)
            return CatalogResult.Failed(e)
        }

        @Suppress("UNCHECKED_CAST")
        val future = raw as? CompletableFuture<List<String>>
        if (future == null) {
            val message =
                "$METHOD_LIST_MODELS returned ${raw?.javaClass?.name}, expected CompletableFuture"
            android.util.Log.e(TAG, message)
            return CatalogResult.Failed(IllegalStateException(message))
        }

        return try {
            CatalogResult.Success(future.get(LIST_MODELS_TIMEOUT_SECONDS, TimeUnit.SECONDS).orEmpty())
        } catch (e: ExecutionException) {
            // The API failure ai-core reported; its message carries the HTTP status.
            CatalogResult.Failed(e.cause ?: e)
        } catch (e: CancellationException) {
            android.util.Log.w(TAG, "$METHOD_LIST_MODELS was cancelled by ai-core", e)
            CatalogResult.Failed(e)
        } catch (e: TimeoutException) {
            future.cancel(true)
            android.util.Log.e(
                TAG,
                "$METHOD_LIST_MODELS did not complete within ${LIST_MODELS_TIMEOUT_SECONDS}s; " +
                    "is ai-core still active?",
                e
            )
            CatalogResult.Failed(e)
        } catch (e: InterruptedException) {
            // Restore the flag so the cancelled coroutine's thread still sees it.
            Thread.currentThread().interrupt()
            future.cancel(true)
            CatalogResult.Failed(e)
        }
    }
}
