package org.appdevforall.getaimodels

import org.appdevforall.getaimodels.download.ModelDownloader

/**
 * Hands the plugin-scoped [ModelDownloader] to fragments, which the IDE constructs itself and so
 * cannot take constructor arguments. The only mutable state outside a fragment: it holds the
 * application context, never an Activity or View, and [detach] clears it on plugin teardown.
 */
object GetAiModelsRuntime {

    @Volatile
    private var downloader: ModelDownloader? = null

    fun attach(downloader: ModelDownloader) {
        this.downloader = downloader
    }

    fun detach() {
        downloader?.dispose()
        downloader = null
    }

    /** Null when the plugin is not active; the UI degrades to a "not available" message. */
    fun downloader(): ModelDownloader? = downloader
}
