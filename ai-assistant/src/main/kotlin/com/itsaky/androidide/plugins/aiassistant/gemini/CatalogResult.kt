package com.itsaky.androidide.plugins.aiassistant.gemini

/**
 * Outcome of one model-catalog lookup against ai-core's Gemini backend.
 *
 * A closed hierarchy, so callers cannot treat "ai-core isn't installed" and "Google refused the
 * key" alike — which the old `emptyList()`-on-every-failure bridge forced them to do.
 */
sealed interface CatalogResult {

    /** The backend answered. [models] may be empty, which is itself suspicious for a valid key. */
    data class Success(val models: List<String>) : CatalogResult

    /** No "gemini" backend was resolvable — ai-core is missing, disabled, or not yet active. */
    data object NoBackend : CatalogResult

    /**
     * The lookup failed. [cause] is the *unwrapped* failure — the API's [java.io.IOException] for
     * an HTTP error, a [java.util.concurrent.TimeoutException], or a reflection failure when the
     * cross-plugin contract has changed.
     */
    data class Failed(val cause: Throwable) : CatalogResult
}
