package org.appdevforall.composepreview

/**
 * Hands the current file + source from the toolbar/menu action to [ComposePreviewFragment]
 * across the openPluginScreen boundary (which carries no Bundle). Mirrors the
 * SketchToUiState pattern used by sketch-to-ui-plugin.
 */
object ComposePreviewState {

    @Volatile
    var filePath: String? = null
        private set

    @Volatile
    var sourceCode: String? = null
        private set

    fun set(filePath: String?, sourceCode: String?) {
        this.filePath = filePath
        this.sourceCode = sourceCode
    }
}
