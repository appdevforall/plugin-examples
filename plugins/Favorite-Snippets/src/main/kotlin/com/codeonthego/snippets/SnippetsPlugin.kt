package com.codeonthego.snippets

import android.util.Log
import com.codeonthego.snippets.ui.SnippetManagerFragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.SnippetContribution
import com.itsaky.androidide.plugins.extensions.SnippetExtension
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEditorTabService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeSnippetService
import java.io.File

class SnippetsPlugin : IPlugin, SnippetExtension, EditorTabExtension, UIExtension {

    private var pluginContext: PluginContext? = null
    private var cachedContributions: List<SnippetContribution>? = null
    private var snippetsLastModified: Long = 0

    override fun initialize(context: PluginContext): Boolean {
        pluginContext = context
        instance = this
        Log.i(TAG, "Custom Snippets plugin initialized")
        return true
    }

    override fun activate(): Boolean {
        Log.i(TAG, "Custom Snippets plugin activated")
        return true
    }

    override fun deactivate(): Boolean {
        cachedContributions = null
        snippetsLastModified = 0
        Log.i(TAG, "Custom Snippets plugin deactivated")
        return true
    }

    override fun dispose() {
        pluginContext = null
        cachedContributions = null
        instance = null
        Log.i(TAG, "Custom Snippets plugin disposed")
    }

    override fun getMainEditorTabs(): List<EditorTabItem> = listOf(
        EditorTabItem(
            id = TAB_ID,
            title = "Snippets",
            icon = R.drawable.ic_snippet,
            fragmentFactory = { SnippetManagerFragment() },
            isCloseable = true,
            isPersistent = false,
            order = 100
        )
    )

    override fun getSideMenuItems(): List<NavigationItem> = listOf(
        NavigationItem(
            id = "manage_snippets",
            title = "Manage Snippets",
            icon = R.drawable.ic_snippet,
            group = "tools",
            order = 10,
            action = { openSnippetsTab() }
        )
    )

    private fun openSnippetsTab() {
        val ctx = pluginContext ?: return
        val tabService = ctx.services.get(IdeEditorTabService::class.java) ?: run {
            Log.e(TAG, "Editor tab service not available")
            return
        }

        if (!tabService.isTabSystemAvailable()) {
            Log.e(TAG, "Tab system not available")
            return
        }

        try {
            if (tabService.selectPluginTab(TAB_ID)) {
                Log.i(TAG, "Opened snippets manager tab")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening snippets tab", e)
        }
    }

    override fun getSnippetContributions(): List<SnippetContribution> {
        val projectRoot = getProjectRoot() ?: return cachedContributions ?: emptyList()
        val snippetsFile = File(projectRoot, SNIPPETS_PATH)

        if (!snippetsFile.exists()) {
            bootstrap(snippetsFile)
        }

        val currentMtime = if (snippetsFile.exists()) snippetsFile.lastModified() else 0L
        if (currentMtime != snippetsLastModified || cachedContributions == null) {
            cachedContributions = loadSnippets(snippetsFile)
            snippetsLastModified = currentMtime
            Log.d(TAG, "Loaded ${cachedContributions?.size ?: 0} snippet contributions")
        }

        return cachedContributions ?: emptyList()
    }

    fun getProjectRoot(): File? {
        val ctx = pluginContext ?: return null
        val projectService = ctx.services.get(IdeProjectService::class.java) ?: return null
        return projectService.getCurrentProject()?.rootDir
    }

    fun getSnippetsFile(): File? {
        val root = getProjectRoot() ?: return null
        return File(root, SNIPPETS_PATH)
    }

    fun invalidateCache() {
        cachedContributions = null
        snippetsLastModified = 0
    }

    fun refreshRegistry() {
        try {
            val snippetService = pluginContext?.services?.get(IdeSnippetService::class.java)
            snippetService?.refreshSnippets(PLUGIN_ID)
        } catch (e: Exception) {
            Log.d(TAG, "Snippet service not available", e)
        }
    }

    private fun bootstrap(snippetsFile: File) {
        val defaults = SnippetsConfig(
            snippets = listOf(
                SnippetEntry(
                    language = "java",
                    scope = "local",
                    prefix = "sout",
                    description = "System.out.println",
                    body = listOf("System.out.println(\$1);\$0")
                ),
                SnippetEntry(
                    language = "java",
                    scope = "local",
                    prefix = "logi",
                    description = "Log.i with TAG",
                    body = listOf("Log.i(TAG, \$1);\$0")
                ),
                SnippetEntry(
                    language = "java",
                    scope = "local",
                    prefix = "trycatch",
                    description = "Try-catch block",
                    body = listOf(
                        "try {",
                        "\t\$1",
                        "} catch (\${2:Exception} e) {",
                        "\t\$0",
                        "}"
                    )
                ),
                SnippetEntry(
                    language = "java",
                    scope = "member",
                    prefix = "singleton",
                    description = "Singleton instance pattern",
                    body = listOf(
                        "private static volatile \${1:ClassName} instance;",
                        "",
                        "public static \$1 getInstance() {",
                        "\tif (instance == null) {",
                        "\t\tsynchronized (\$1.class) {",
                        "\t\t\tif (instance == null) {",
                        "\t\t\t\tinstance = new \$1();",
                        "\t\t\t}",
                        "\t\t}",
                        "\t}",
                        "\treturn instance;",
                        "}"
                    )
                )
            )
        )

        try {
            SnippetsConfigParser.write(snippetsFile, defaults)
            Log.i(TAG, "Created default snippets.json with ${defaults.snippets.size} snippets")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write default snippets.json", e)
        }
    }

    private fun loadSnippets(snippetsFile: File): List<SnippetContribution> {
        val config = SnippetsConfigParser.parse(snippetsFile) ?: return emptyList()
        return config.snippets.map { entry ->
            SnippetContribution(
                language = entry.language,
                scope = entry.scope,
                prefix = entry.prefix,
                description = entry.description,
                body = entry.body
            )
        }
    }

    companion object {
        private const val TAG = "CustomSnippets"
        const val PLUGIN_ID = "com.codeonthego.snippets"
        const val TAB_ID = "com.codeonthego.snippets.manager"
        const val SNIPPETS_PATH = ".cg/snippets.json"

        var instance: SnippetsPlugin? = null
            private set
    }
}