package com.appdevforall.python.plugin

import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

internal object PythonToolsDocumentation {

    const val CATEGORY = "plugin_${PythonToolsPlugin.PLUGIN_ID}"
    const val DOCS_ASSET_PATH = "docs"

    fun entries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = tagFor(PythonToolsPlugin.ACTION_RUN_APP),
            summary = "Runs the project. For a plain Python project <code>main.py</code> runs; " +
                "for a Flask project <code>app.py</code> runs.",
            detail = """
                <p>Looks for an entry point in the project root and runs the first one it finds:</p>
                <ol>
                  <li><code>app.py</code> - a Flask app</li>
                  <li><code>main.py</code> - a plain Python project</li>
                  <li><code>manage.py</code> - started with <code>runserver</code></li>
                  <li><code>__main__.py</code></li>
                  <li><code>wsgi.py</code></li>
                </ol>
                <p>Output streams unbuffered into <b>Build Output</b>. While the process is alive the
                toolbar button becomes <b>Cancel Run app</b>; tap it to stop the process.</p>
                <p>If the run fails because a module is missing and the project has a
                <code>requirements.txt</code>, the dependencies are installed for you and you are asked
                to tap Run again. A run is stopped after 30 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-app"),
        ),
        PluginTooltipEntry(
            tag = tagFor(PythonToolsPlugin.ACTION_RUN_CURRENT_FILE),
            summary = "Runs the Python file open in the editor, leaving the project entry point alone.",
            detail = """
                <p>Appears only while a <code>.py</code> file is open, and runs exactly that file by its
                full path - useful for a script that is not the project entry point.</p>
                <p>Output streams into <b>Build Output</b> and the button becomes
                <b>Cancel Run current file</b> while the file runs. Missing dependencies are installed
                from <code>requirements.txt</code> just as they are for Run app. A run is stopped after
                30 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-current-file"),
        ),
        PluginTooltipEntry(
            tag = tagFor(PythonToolsPlugin.ACTION_SYNC_DEPS),
            summary = "Downloads and installs dependencies from requirements.txt (if present).",
            detail = """
                <p>Runs <code>pip install -r requirements.txt</code> in the project root and streams
                pip's output into <b>Build Output</b>.</p>
                <p>The same install runs on its own when a run fails because a module is missing, so
                reach for this button after you edit <code>requirements.txt</code> yourself. The install
                is stopped after 5 minutes.</p>
            """.trimIndent(),
            buttons = buttons("install-requirements"),
        ),
        PluginTooltipEntry(
            tag = tagFor(PythonToolsPlugin.ACTION_TEST),
            summary = "Runs the test suite with pytest, installing pytest first if it is missing.",
            detail = """
                <p>Runs <code>python -m pytest -q</code> in the project root. When pytest is not
                installed yet it is fetched with pip before the tests start.</p>
                <p>Results stream into <b>Build Output</b>. The run is stopped after 10 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-tests"),
        ),
    )

    fun tagFor(actionId: String): String = "${PythonToolsPlugin.PLUGIN_ID}.$actionId"

    private fun buttons(anchor: String): List<PluginTooltipButton> = listOf(
        PluginTooltipButton(
            description = "How this command works",
            uri = "index.html#$anchor",
            order = 0,
        ),
        PluginTooltipButton(
            description = "About Code On The Go plugins",
            uri = "i/plugins-adfa.html",
            order = 1,
            directPath = true,
        ),
    )
}
