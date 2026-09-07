package org.appdevforall.codeonthego.layouteditor

/**
 * Hands the current layout file from the toolbar action to [LayoutEditorFragment] across the
 * openPluginScreen boundary (which carries no Bundle). Mirrors compose-preview's
 * ComposePreviewState / sketch-to-ui's SketchToUiState pattern.
 *
 * The two values mirror the Intent extras the in-app EditorActivity used to receive:
 * [filePath] = the project resource directory (the path before "layout"), and
 * [layoutFileName] = the layout file name without extension.
 */
object LayoutEditorState {

    @Volatile
    var filePath: String? = null
        private set

    @Volatile
    var layoutFileName: String? = null
        private set

    fun set(filePath: String?, layoutFileName: String?) {
        this.filePath = filePath
        this.layoutFileName = layoutFileName
    }
}
