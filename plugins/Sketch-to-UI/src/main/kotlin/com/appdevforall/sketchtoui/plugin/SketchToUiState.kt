package com.appdevforall.sketchtoui.plugin

object SketchToUiState {
    @Volatile
    var layoutFilePath: String? = null

    @Volatile
    var layoutFileName: String? = null

    fun setLayoutFile(path: String?, name: String?) {
        layoutFilePath = path
        layoutFileName = name
    }
}
