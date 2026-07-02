package org.appdevforall.codeonthego.layouteditor

/**
 * Hands data from the editor to its sub-screens (opened via openPluginScreen, which carries no
 * Bundle). Mirrors [LayoutEditorState].
 */
object EditorSubScreenState {

    @Volatile
    var previewLayoutFile: LayoutFile? = null

    @Volatile
    var xml: String? = null
}
