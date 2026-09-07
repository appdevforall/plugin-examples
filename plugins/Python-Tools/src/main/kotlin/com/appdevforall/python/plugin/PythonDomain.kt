package com.appdevforall.python.plugin

import java.io.File


internal object PythonDomain {

    private const val REQUIREMENTS = "requirements.txt"
    private const val PYPROJECT = "pyproject.toml"

    private val GRADLE_MARKERS = listOf(
        "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"
    )

    @Volatile
    private var cachedRoot: String? = null

    @Volatile
    private var cachedResult: Boolean = false

    @Synchronized
    fun isPythonProject(root: File?): Boolean {
        if (root == null) return false
        val path = root.absolutePath
        if (path == cachedRoot) return cachedResult
        val result = detect(root)
        cachedRoot = path
        cachedResult = result
        return result
    }

    private fun detect(root: File): Boolean {
        if (GRADLE_MARKERS.any { File(root, it).exists() }) return false
        if (File(root, REQUIREMENTS).exists()) return true
        if (File(root, PYPROJECT).exists()) return true
        val pyFiles = root.listFiles { f -> f.isFile && f.name.endsWith(".py") }
        return pyFiles != null && pyFiles.isNotEmpty()
    }
}
