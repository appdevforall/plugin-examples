package com.appdevforall.python.plugin

import android.util.Log
import android.widget.Toast
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.BuildActionCategory
import com.itsaky.androidide.plugins.extensions.BuildActionExtension
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.extensions.PluginBuildAction
import com.itsaky.androidide.plugins.extensions.ToolbarActionIds
import com.itsaky.androidide.plugins.services.IdeCommandService
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.services.IdeUIService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Python Tools plugin.
 *
 * Contributes two project templates — a Flask web app and a starter Python project — built from
 * `.peb` Pebble assets. When the open project is in the Python domain it hides the built-in Gradle
 * toolbar actions and contributes Python build actions instead; non-Python projects are untouched.
 * On activate it ensures a Python interpreter is present, installing it via Termux `pkg` if not.
 *
 * Structure follows the flask-plugin standard (asset-based templates, vector icons, `sh -c`
 * commands). The Python-domain gating and the interpreter bootstrap are intentional additions: the
 * plugin must not steal the toolbar from Java/Kotlin/Android projects, and it must be usable on a
 * fresh device without Python pre-installed.
 */
class PythonToolsPlugin : IPlugin, BuildActionExtension {

    private var pluginContext: PluginContext? = null
    private var templateService: IdeTemplateService? = null
    private var projectService: IdeProjectService? = null
    private var editorService: IdeEditorService? = null
    private var commandService: IdeCommandService? = null
    private var uiService: IdeUIService? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var installJob: Job? = null

    override fun initialize(context: PluginContext): Boolean {
        pluginContext = context
        templateService = context.services.get(IdeTemplateService::class.java)
        projectService = context.services.get(IdeProjectService::class.java)
        editorService = context.services.get(IdeEditorService::class.java)
        commandService = context.services.get(IdeCommandService::class.java)
        uiService = context.services.get(IdeUIService::class.java)
        Log.i(TAG, "Python Tools initialized")
        return true
    }

    override fun activate(): Boolean {
        installPyHooks()
        registerTemplates()
        installJob = scope.launch { ensurePython() }
        Log.i(TAG, "Python Tools activated")
        return true
    }

    override fun deactivate(): Boolean {
        installJob?.cancel()
        templateService?.let { service ->
            service.unregisterTemplate(FLASK_CGT)
            service.unregisterTemplate(STARTER_CGT)
        }
        Log.i(TAG, "Python Tools deactivated")
        return true
    }

    override fun dispose() {
        scope.cancel()
        pluginContext = null
        templateService = null
        projectService = null
        editorService = null
        commandService = null
        uiService = null
    }

    // region Build toolbar (Python domain only)

    override fun toolbarActionsToHide(): Set<String> =
        if (isPythonProjectOpen()) ToolbarActionIds.BUILD_HIDEABLE else emptySet()

    override fun getBuildActions(): List<PluginBuildAction> {
        if (!isPythonProjectOpen()) return emptyList()

        val actions = mutableListOf(
            PluginBuildAction(
                id = "python.run.app",
                name = "Run app",
                description = "Run the Python app (app.py, main.py, manage.py, __main__.py, or wsgi.py)",
                icon = R.drawable.ic_run_server,
                category = BuildActionCategory.BUILD,
                command = shell(
                    "if [ -f app.py ]; then exec python app.py; " +
                        "elif [ -f main.py ]; then exec python main.py; " +
                        "elif [ -f manage.py ]; then exec python manage.py runserver; " +
                        "elif [ -f __main__.py ]; then exec python __main__.py; " +
                        "elif [ -f wsgi.py ]; then exec python wsgi.py; " +
                        "else echo 'No Python entry point found " +
                        "(looked for app.py, main.py, manage.py, __main__.py, wsgi.py)'; exit 1; fi"
                ),
                timeoutMs = 1_800_000,
            ),
        )

        val current = editorService?.getCurrentFile()
        if (current != null && current.name.endsWith(".py")) {
            actions.add(
                PluginBuildAction(
                    id = "python.run.currentFile",
                    name = "Run current file",
                    description = "Run ${current.name}",
                    icon = R.drawable.ic_run_python,
                    category = BuildActionCategory.BUILD,
                    command = shell("exec python \"${current.absolutePath}\""),
                    timeoutMs = 1_800_000,
                ),
            )
        }

        actions.add(
            PluginBuildAction(
                id = "python.sync.deps",
                name = "Install requirements",
                description = "Install dependencies from requirements.txt",
                icon = R.drawable.ic_sync_deps,
                category = BuildActionCategory.BUILD,
                command = shell("pip install -r requirements.txt"),
                timeoutMs = 300_000,
            ),
        )
        actions.add(
            PluginBuildAction(
                id = "python.test",
                name = "Run tests",
                description = "Run the test suite with pytest",
                icon = R.drawable.ic_run_tests,
                category = BuildActionCategory.TEST,
                command = shell("pip show pytest > /dev/null 2>&1 || pip install pytest -q; python -m pytest -q"),
                timeoutMs = 600_000,
            ),
        )
        return actions
    }

    override fun onActionStarted(actionId: String) {
        Log.i(TAG, "Action started: $actionId")
    }

    override fun onActionCompleted(actionId: String, result: CommandResult) {
        val status = when (result) {
            is CommandResult.Success -> "completed (${result.durationMs}ms)"
            is CommandResult.Failure -> "failed (exit ${result.exitCode})"
            is CommandResult.Cancelled -> "stopped"
        }
        Log.i(TAG, "Action $actionId $status")
        when (result) {
            is CommandResult.Failure ->
                if (isRunAction(actionId) && isMissingDependencyFailure(result)) {
                    installRequirements()
                } else {
                    notify("${actionLabel(actionId)} failed (exit ${result.exitCode})")
                }
            is CommandResult.Cancelled ->
                if (isRunAction(actionId)) notify("${actionLabel(actionId)} stopped.")
            is CommandResult.Success -> Unit
        }
    }

    private fun actionLabel(actionId: String): String = when (actionId) {
        "python.run.app" -> "Run app"
        "python.run.currentFile" -> "Run current file"
        "python.sync.deps" -> "Install requirements"
        "python.test" -> "Run tests"
        else -> actionId
    }

    private fun isRunAction(actionId: String): Boolean =
        actionId == "python.run.app" || actionId == "python.run.currentFile"

    private fun isMissingDependencyFailure(result: CommandResult.Failure): Boolean {
        val output = result.stderr + "\n" + result.stdout
        return output.contains("ModuleNotFoundError") ||
            output.contains("No module named") ||
            output.contains("ImportError")
    }

    private fun installRequirements() {
        val cmd = commandService ?: return
        val root = projectService?.getCurrentProject()?.rootDir ?: return
        if (!File(root, "requirements.txt").exists()) {
            notify("Run failed: a dependency is missing and no requirements.txt was found.")
            return
        }
        notify("Missing dependencies — installing from requirements.txt…")
        scope.launch {
            val result = try {
                cmd.executeCommand(shell("pip install -r requirements.txt"), timeoutMs = 300_000L).await()
            } catch (t: Throwable) {
                notify("Could not start dependency install.")
                return@launch
            }
            when (result) {
                is CommandResult.Success -> notify("Dependencies installed. Tap Run again.")
                is CommandResult.Failure -> notify("Dependency install failed (exit ${result.exitCode}).")
                is CommandResult.Cancelled -> Unit
            }
        }
    }

    private fun isPythonProjectOpen(): Boolean =
        PythonDomain.isPythonProject(projectService?.getCurrentProject()?.rootDir)

    private fun shell(script: String): CommandSpec.ShellCommand {
        val environment = mutableMapOf("PYTHONUNBUFFERED" to "1")
        pyHooksDir()?.absolutePath?.let { environment["PYTHONPATH"] = it }
        return CommandSpec.ShellCommand(
            executable = "sh",
            arguments = listOf("-c", script),
            environment = environment,
        )
    }

    private fun pyHooksDir(): File? =
        pluginContext?.resources?.getPluginDirectory()?.let { File(it, "pyhooks") }

    private fun installPyHooks() {
        val dir = pyHooksDir() ?: return
        runCatching {
            dir.mkdirs()
            File(dir, "sitecustomize.py").writeText(SITE_CUSTOMIZE)
        }.onFailure { Log.e(TAG, "Failed to install Python process hooks", it) }
    }

    // endregion

    // region Templates

    private fun registerTemplates() {
        val service = templateService ?: return
        val ctx = pluginContext ?: return

        runCatching {
            val flask = service.createTemplateBuilder("Python Flask App")
                .description("A Flask web app with routes, templates, static files, and error handling")
                .showPackageNameOption()
                .thumbnailFromAssets("templates/flask/thumb.png", ctx)
                .addTextParameter("Port", "PORT", "5000")
                .addTemplateFromAssets("app.py", "templates/flask/app.py.peb", ctx)
                .addTemplateFromAssets("config.py", "templates/flask/config.py.peb", ctx)
                .addTemplateFromAssets("templates/base.html", "templates/flask/base.html.peb", ctx)
                .addTemplateFromAssets("templates/index.html", "templates/flask/index.html.peb", ctx)
                .addTemplateFromAssets("templates/about.html", "templates/flask/about.html.peb", ctx)
                .addTemplateFromAssets("templates/404.html", "templates/flask/404.html.peb", ctx)
                .addTemplateFromAssets("README.md", "templates/flask/README.md.peb", ctx)
                .addStaticFromAssets("static/css/style.css", "templates/flask/static/css/style.css", ctx)
                .addStaticFromAssets("requirements.txt", "templates/flask/requirements.txt", ctx)
                .addStaticFromAssets(".gitignore", "templates/flask/gitignore", ctx)
                .build(ctx.resources.getPluginDirectory())
            service.registerTemplate(flask)

            val starter = service.createTemplateBuilder("Python Starter")
                .description("A minimal Python project with a main entry point")
                .showPackageNameOption()
                .thumbnailFromAssets("templates/starter/thumb.png", ctx)
                .addTemplateFromAssets("main.py", "templates/starter/main.py.peb", ctx)
                .addTemplateFromAssets("README.md", "templates/starter/README.md.peb", ctx)
                .addStaticFromAssets("requirements.txt", "templates/starter/requirements.txt", ctx)
                .addStaticFromAssets(".gitignore", "templates/starter/gitignore", ctx)
                .build(ctx.resources.getPluginDirectory())
            service.registerTemplate(starter)

            Log.i(TAG, "Registered Python templates: Flask + Starter")
        }.onFailure {
            Log.e(TAG, "Failed to register Python templates", it)
        }
    }

    // endregion

    // region Python interpreter bootstrap

    private suspend fun ensurePython() {
        val cmd = commandService ?: run {
            Log.e(TAG, "IdeCommandService unavailable; cannot manage Python")
            return
        }

        if (pythonAvailable(cmd)) {
            Log.i(TAG, "Python is already installed")
            return
        }

        notify("Python not found. Installing via Termux…")
        val result = try {
            cmd.executeCommand(shell("pkg install python -y"), timeoutMs = 15 * 60_000L).await()
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start the package manager", t)
            notify("Could not start the package manager (pkg). Install Python manually.")
            return
        }

        when (result) {
            is CommandResult.Success ->
                if (pythonAvailable(cmd)) notify("Python installed successfully.")
                else notify("Install finished but Python is still not on PATH.")
            is CommandResult.Failure -> {
                val detail = result.error ?: result.stderr.takeIf { it.isNotBlank() } ?: "exit ${result.exitCode}"
                Log.e(TAG, "pkg install python failed: $detail")
                notify("Failed to install Python: $detail")
            }
            is CommandResult.Cancelled -> Log.i(TAG, "Python installation cancelled")
        }
    }

    private suspend fun pythonAvailable(cmd: IdeCommandService): Boolean = try {
        val result = cmd.executeCommand(shell("python --version"), timeoutMs = 20_000L).await()
        result is CommandResult.Success && result.exitCode == 0
    } catch (t: Throwable) {
        false
    }

    private fun notify(message: String) {
        Log.i(TAG, message)
        val activity = uiService?.takeIf { it.isUIAvailable() }?.getCurrentActivity() ?: return
        activity.runOnUiThread {
            Toast.makeText(activity, "Python Tools: $message", Toast.LENGTH_SHORT).show()
        }
    }

    // endregion

    companion object {
        private const val TAG = "PythonToolsPlugin"
        private const val FLASK_CGT = "PythonFlaskApp.cgt"
        private const val STARTER_CGT = "PythonStarter.cgt"

        private val SITE_CUSTOMIZE = """
            import ctypes
            import signal

            _libc = None
            for _name in ("libc.so", "libc.so.6", None):
                try:
                    _libc = ctypes.CDLL(_name, use_errno=True)
                    break
                except OSError:
                    _libc = None
            if _libc is not None:
                try:
                    _libc.prctl(1, signal.SIGKILL)
                except Exception:
                    pass
        """.trimIndent()
    }
}
