package com.cotg.cotgndk.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.cotg.cotgndk.R
import com.cotg.cotgndk.cotgndk
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeArchiveService
import com.itsaky.androidide.plugins.services.IdeEnvironmentService
import com.itsaky.androidide.plugins.services.IdeProjectService
import com.itsaky.androidide.plugins.services.ArchiveFormat
import kotlinx.coroutines.*
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

class cotgndkFragment : Fragment() {

    companion object {
        private const val PLUGIN_ID = "com.cotg.cotgndk"
        private const val NDK_DOWNLOAD_URL = "https://github.com/aman-khan-786/cotgx-ndk/releases/download/V1.0/cotgx-ndk-ultimate.tar.gz"
        private const val EXPECTED_NDK_FILENAME = "cotgx-ndk-ultimate.tar.gz"
    }

    private var statusText: TextView? = null
    private var scrollView: ScrollView? = null
    private var actionButton: MaterialButton? = null
    private var btnCopyLogs: MaterialButton? = null
    private var btnDownloadNdk: MaterialButton? = null
    private var checkExportLibcpp: CheckBox? = null
    private var checkParallelMode: CheckBox? = null

    private var envService: IdeEnvironmentService? = null
    private var projectService: IdeProjectService? = null

    private var selectedNdkFile: File? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        setupServices()
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusText = view.findViewById(R.id.statusText)
        scrollView = view.findViewById(R.id.scrollView)
        actionButton = view.findViewById(R.id.actionButton)
        btnCopyLogs = view.findViewById(R.id.btnCopyLogs)
        btnDownloadNdk = view.findViewById(R.id.btnDownloadNdk)
        checkExportLibcpp = view.findViewById(R.id.checkExportLibcpp)
        checkParallelMode = view.findViewById(R.id.checkParallelMode)

        actionButton?.setOnClickListener { startCompilation() }
        btnCopyLogs?.setOnClickListener { copyLogsToClipboard() }
        btnDownloadNdk?.setOnClickListener { openDownloadLink() }

        checkNdkStatus()
    }

    private fun setupServices() {
        runCatching {
            envService = cotgndk.pluginCtx.services.get(IdeEnvironmentService::class.java)
            projectService = cotgndk.pluginCtx.services.get(IdeProjectService::class.java)
        }
    }

    private fun openDownloadLink() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(NDK_DOWNLOAD_URL))
        startActivity(intent)
    }

    private fun checkNdkStatus() {
        val targetDir = File(envService?.getAndroidHomeDirectory(), "cotgx_ultimate_ndk")
        val downloadFolder = File("/storage/emulated/0/Download")
        val ndkArchive = File(downloadFolder, EXPECTED_NDK_FILENAME)

        if (targetDir.exists() && targetDir.listFiles()?.isNotEmpty() == true) {
            logToConsole("[✓] NDK is already installed and connected.\nReady to compile.")
            actionButton?.isEnabled = true
            btnDownloadNdk?.visibility = View.GONE
        } else if (ndkArchive.exists()) {
            selectedNdkFile = ndkArchive
            logToConsole("[✓] NDK Archive detected in Download folder:\n${ndkArchive.name}\n\nReady to extract and compile.")
            actionButton?.isEnabled = true
            btnDownloadNdk?.visibility = View.GONE
        } else {
            logToConsole("[x] NDK not found!\n\nPlease download the required NDK file from the repository.\nLink: $NDK_DOWNLOAD_URL\n\nEnsure the file '$EXPECTED_NDK_FILENAME' is placed directly inside your Download folder, then reopen this plugin.")
            actionButton?.isEnabled = false
            btnDownloadNdk?.visibility = View.VISIBLE
        }
    }

    private fun copyLogsToClipboard() {
        val text = statusText?.text?.toString()
        if (!text.isNullOrEmpty()) {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("COTG Logs", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Logs Copied to Clipboard!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Nothing to copy!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getActiveProjectPath(): File? {
        return projectService?.getCurrentProject()?.rootDir
    }

    private fun startCompilation() {
        val exportLibcppEnabled = checkExportLibcpp?.isChecked == true
        val isParallelEnabled = checkParallelMode?.isChecked == true
        actionButton?.isEnabled = false
        logToConsole("Starting Build Process...\n")

        scope.launch(Dispatchers.IO) {
            try {
                val targetDir = File(envService?.getAndroidHomeDirectory(), "cotgx_ultimate_ndk")

                if (!prepareNdkEnvironment(targetDir)) return@launch

                val projectPath = getActiveProjectPath()
                if (projectPath == null || !projectPath.exists()) {
                    appendConsole("\n[x] No active project found. Open a project in IDE first.")
                    return@launch
                }

                appendConsole("\n[i] Target Project: ${projectPath.name}")

                val cppDir = File(projectPath, "app/src/main/cpp")
                val buildDir = File(projectPath, "app/build/cotg_native_build/arm64-v8a")
                val jniLibsDir = File(projectPath, "app/src/main/jniLibs/arm64-v8a")

                if (!File(cppDir, "CMakeLists.txt").exists()) {
                    appendConsole("\n[x] CMakeLists.txt not found in app/src/main/cpp!")
                    return@launch
                }

                buildDir.mkdirs()
                jniLibsDir.mkdirs()

                appendConsole("\n[>] Initiating Compiler Engine...")
                val ndkPath = targetDir.absolutePath
                
                val libcppScript = if (exportLibcppEnabled) {
                    """
                    echo "Exporting libc++_shared.so..."
                    find "${'$'}NDK_DIR" -name "libc++_shared.so" -exec cp {} "${jniLibsDir.absolutePath}/" \; 2>/dev/null
                    """.trimIndent()
                } else {
                    "echo \"Exporting of libc++_shared.so is disabled by user.\""
                }

                // Parallel vs Safe mode logic
                val makeCores = if (isParallelEnabled) "-j4" else "-j1"
                appendConsole("\n[i] Multi-Core Mode: ${if (isParallelEnabled) "ON (Fast)" else "OFF (Safe Mode)"}")

                val compileScript = """
                    export NDK_DIR="${ndkPath}"
                    export ANDROID_NDK_HOME="${'$'}NDK_DIR"
                    export ANDROID_NDK="${'$'}NDK_DIR"

                    cd "${buildDir.absolutePath}"

                    echo "[1/8] Cleaning old build cache..."
                    rm -rf * 2>/dev/null

                    echo "[2/8] Fixing LD_LIBRARY_PATH..."
                    L1=${'$'}(find "${'$'}NDK_DIR" -type d -name "lib"   | head -n 1)
                    L2=${'$'}(find "${'$'}NDK_DIR" -type d -name "lib64" | head -n 1)
                    export LD_LIBRARY_PATH="${'$'}L1:${'$'}L2:${'$'}LD_LIBRARY_PATH"

                    echo "[3/8] Locating Clang toolchain..."
                    CLANG_BIN=${'$'}(find "${'$'}NDK_DIR" -name "clang"   | grep "bin/clang${'$'}"   | head -n 1)
                    CLANGXX_BIN=${'$'}(find "${'$'}NDK_DIR" -name "clang++" | grep "bin/clang++${'$'}" | head -n 1)
                    if [ -z "${'$'}CLANG_BIN" ]; then
                        echo "[x] FATAL: clang not found in NDK!"
                        exit 1
                    fi
                    chmod +x "${'$'}CLANG_BIN" "${'$'}CLANGXX_BIN" 2>/dev/null

                    echo "[4/8] Locating Sysroot..."
                    SYSROOT_DIR=${'$'}(find "${'$'}NDK_DIR" -type d -name "sysroot" | head -n 1)
                    if [ -z "${'$'}SYSROOT_DIR" ]; then
                        SYSROOT_DIR="${'$'}NDK_DIR"
                    fi

                    echo "[i] Verifying / provisioning Android platform libraries..."
                    SYSROOT_LIB_DIR="${'$'}SYSROOT_DIR/usr/lib"
                    mkdir -p "${'$'}SYSROOT_LIB_DIR" 2>/dev/null
                    SYSTEM_LIB_DIR="/system/lib64"
                    [ -d "${'$'}SYSTEM_LIB_DIR" ] || SYSTEM_LIB_DIR="/system/lib"

                    for L in liblog libandroid libEGL libGLESv2 libGLESv3 libjnigraphics libmediandk libOpenSLES libvulkan libaaudio libcamera2ndk libnativewindow; do
                        HAVE=${'$'}(find "${'$'}SYSROOT_DIR" -type f -name "${'$'}{L}.so" 2>/dev/null | head -n 1)
                        if [ -z "${'$'}HAVE" ] && [ -f "${'$'}SYSTEM_LIB_DIR/${'$'}{L}.so" ]; then
                            cp "${'$'}SYSTEM_LIB_DIR/${'$'}{L}.so" "${'$'}SYSROOT_LIB_DIR/${'$'}{L}.so" 2>/dev/null \
                                && echo "  [+] Provisioned ${'$'}{L}.so from ${'$'}SYSTEM_LIB_DIR"
                        fi
                    done

                    LIBLOG_FILE=${'$'}(find "${'$'}SYSROOT_DIR" -type f \( -name "liblog.so" -o -name "liblog.a" \) 2>/dev/null | head -n 1)
                    if [ -n "${'$'}LIBLOG_FILE" ]; then
                        echo "  [✓] Android platform libraries available (liblog resolved)."
                    else
                        echo "  [!] -------------------------------------------------------------------"
                        echo "  [!] WARNING: 'liblog' is missing from the sysroot and could not be"
                        echo "  [!] provisioned from ${'$'}SYSTEM_LIB_DIR on this device. Any CMakeLists.txt"
                        echo "  [!] that calls find_library(... log) / links -llog WILL FAIL. Remove the"
                        echo "  [!] 'log' dependency, or use a sysroot that ships liblog.so."
                        echo "  [!] -------------------------------------------------------------------"
                    fi

                    echo "[5/8] Locating JNI headers..."
                    JNI_H=${'$'}(find "${'$'}NDK_DIR" -type f -name "jni.h" | head -n 1)
                    JNI_DIR=${'$'}(dirname "${'$'}JNI_H" 2>/dev/null)

                    echo "[6/8] Locating C++ STL root..."
                    CXX_STL=${'$'}(find "${'$'}NDK_DIR" -type d -name "v1" | grep "c++/v1${'$'}" | head -n 1)

                    echo "[7/8] Locating libc++ library..."
                    LIBCXX_FILE=${'$'}(find "${'$'}NDK_DIR" -type f \( -name "libc++.so" -o -name "libc++.a" \) | head -n 1)
                    LIBCXX_DIR=${'$'}(dirname "${'$'}LIBCXX_FILE" 2>/dev/null)
                    LDFLAGS_EXTRA=""
                    if [ ! -z "${'$'}LIBCXX_DIR" ]; then
                        LDFLAGS_EXTRA="-L${'$'}LIBCXX_DIR -lc++"
                    fi

                    TARGET="-target aarch64-linux-android21"
                    SYSROOT_FLAG="--sysroot=${'$'}SYSROOT_DIR"

                    C_FLAGS="${'$'}TARGET ${'$'}SYSROOT_FLAG"
                    CXX_FLAGS="${'$'}TARGET ${'$'}SYSROOT_FLAG"

                    if [ ! -z "${'$'}CXX_STL" ]; then
                        CXX_FLAGS="${'$'}CXX_FLAGS -isystem ${'$'}CXX_STL"
                    fi

                    if [ ! -z "${'$'}JNI_DIR" ]; then
                        C_FLAGS="${'$'}C_FLAGS     -isystem ${'$'}JNI_DIR"
                        CXX_FLAGS="${'$'}CXX_FLAGS -isystem ${'$'}JNI_DIR"
                    fi

                    CUSTOM_CMAKE=${'$'}(find "${'$'}NDK_DIR" -name "cmake" | grep "bin/cmake${'$'}" | head -n 1)
                    if [ ! -z "${'$'}CUSTOM_CMAKE" ]; then
                        CMAKE_BIN="${'$'}CUSTOM_CMAKE"
                        chmod +x "${'$'}CMAKE_BIN" 2>/dev/null
                    else
                        CMAKE_BIN=${'$'}(command -v cmake 2>/dev/null || echo "/data/data/com.itsaky.androidide/files/usr/bin/cmake")
                    fi
                    
                    CUSTOM_MAKE=${'$'}(find "${'$'}NDK_DIR" -name "make" | grep "bin/make${'$'}" | head -n 1)
                    if [ ! -z "${'$'}CUSTOM_MAKE" ]; then
                        MAKE_BIN="${'$'}CUSTOM_MAKE"
                        chmod +x "${'$'}MAKE_BIN" 2>/dev/null
                    else
                        MAKE_BIN=${'$'}(command -v make 2>/dev/null || echo "/data/data/com.itsaky.androidide/files/usr/bin/make")
                    fi

                    echo "[8/8] Running CMake configuration..."
                    "${'$'}CMAKE_BIN" "${cppDir.absolutePath}" \
                        -DCMAKE_SYSTEM_NAME=Android \
                        -DCMAKE_SYSTEM_VERSION=21 \
                        -DCMAKE_SYSROOT="${'$'}SYSROOT_DIR" \
                        -DCMAKE_FIND_ROOT_PATH="${'$'}SYSROOT_DIR" \
                        -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY \
                        -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
                        -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER \
                        -DCMAKE_C_COMPILER="${'$'}CLANG_BIN" \
                        -DCMAKE_CXX_COMPILER="${'$'}CLANGXX_BIN" \
                        -DCMAKE_MAKE_PROGRAM="${'$'}MAKE_BIN" \
                        -DCMAKE_C_COMPILER_WORKS=1 \
                        -DCMAKE_CXX_COMPILER_WORKS=1 \
                        -DCMAKE_C_FLAGS="${'$'}C_FLAGS" \
                        -DCMAKE_CXX_FLAGS="${'$'}CXX_FLAGS" \
                        -DCMAKE_EXE_LINKER_FLAGS="${'$'}LDFLAGS_EXTRA" \
                        -DCMAKE_SHARED_LINKER_FLAGS="${'$'}LDFLAGS_EXTRA" \
                        -DCMAKE_BUILD_TYPE=Release \
                        -G "Unix Makefiles"

                    if [ ${'$'}? -ne 0 ]; then
                        echo "[x] CMake configuration failed!"
                        exit 1
                    fi

                    echo "Compiling native library (Threads: ${makeCores})..."
                    "${'$'}MAKE_BIN" SHELL=/system/bin/sh ${makeCores}

                    if [ ${'$'}? -ne 0 ]; then
                        echo "[x] Compilation failed at Make stage!"
                        exit 1
                    fi

                    echo "Exporting .so to jniLibs..."
                    cp -r *.so "${jniLibsDir.absolutePath}/" 2>/dev/null
                    
                    ${libcppScript}

                    echo "[★] BUILD SUCCESS!"
                """.trimIndent()

                runCompilation(compileScript, projectPath)
            } catch (e: Exception) {
                appendConsole("\n[x] CRITICAL ERROR: ${e.message}")
            } finally {
                enableButtons()
            }
        }
    }

    private suspend fun prepareNdkEnvironment(targetDir: File): Boolean {
        if (targetDir.exists() && targetDir.listFiles()?.isEmpty() != true) {
            appendConsole("\n[✓] Environment is active and ready.")
            return true
        }

        val archive = selectedNdkFile
        if (archive == null || !archive.exists()) {
            appendConsole("\n[x] NDK Zip not found in Download folder.")
            return false
        }

        return extractNdkArchive(archive, targetDir)
    }

    private suspend fun extractNdkArchive(archive: File, targetDir: File): Boolean {
        targetDir.mkdirs()
        val tempFile = File(targetDir.parentFile, "temp_cotgx_archive.tmp")
        appendConsole("\n[!] Copying environment to workspace...")

        return try {
            archive.inputStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output, 8192)
                }
            }

            appendConsole("\n[!] Extracting via Native OS Engine...")
            val extractCmd = "tar -xzf '${tempFile.absolutePath}' -C '${targetDir.absolutePath}'"
            val process = ProcessBuilder("sh", "-c", extractCmd)
                .redirectErrorStream(true).start()
            streamProcessOutput(process)
            val exitCode = process.waitFor()
            tempFile.delete()

            if (exitCode == 0) {
                appendConsole("\n[✓] Environment Extracted Successfully!")
                true
            } else {
                appendConsole("\n[x] Extraction Failed! Exit Code: $exitCode")
                targetDir.deleteRecursively()
                false
            }
        } catch (e: Exception) {
            appendConsole("\n[x] Extraction Error: ${e.message}")
            tempFile.delete()
            targetDir.deleteRecursively()
            false
        }
    }

    private suspend fun runCompilation(compileScript: String, projectPath: File) {
        val process = ProcessBuilder("sh", "-c", compileScript)
            .directory(projectPath)
            .redirectErrorStream(true)
            .start()

        streamProcessOutput(process)
        val exitCode = process.waitFor()

        if (exitCode == 0) {
            appendConsole("\n\n[★] COMPILATION SUCCESSFUL!")
            appendConsole("\n[★] Files are ready in app/src/main/jniLibs/arm64-v8a/")
        } else {
            appendConsole("\n\n[x] Compilation Failed — exit code $exitCode")
        }
    }

    private suspend fun streamProcessOutput(process: Process) {
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            appendConsole("\n  -> $line")
        }
    }

    private suspend fun appendConsole(msg: String) {
        withContext(Dispatchers.Main) {
            statusText?.append(msg)
            scrollView?.post {
                scrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun logToConsole(msg: String) {
        statusText?.text = msg
    }

    private suspend fun enableButtons() {
        withContext(Dispatchers.Main) {
            actionButton?.isEnabled = true
            actionButton?.text = "Compile Again"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        statusText = null
        scrollView = null
        actionButton = null
        btnCopyLogs = null
        btnDownloadNdk = null
        checkExportLibcpp = null
        checkParallelMode = null
    }
}
