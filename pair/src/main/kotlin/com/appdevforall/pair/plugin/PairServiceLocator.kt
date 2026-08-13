package com.appdevforall.pair.plugin

import android.os.Build
import com.appdevforall.pair.plugin.data.DeviceSettingsStore
import com.appdevforall.pair.plugin.data.PairDiscoveryService
import com.appdevforall.pair.plugin.data.SessionHistoryStore
import com.appdevforall.pair.plugin.domain.EditBroker
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeEnvironmentService
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeProjectService
import java.io.File

object PairServiceLocator {

    private var instance: Container? = null

    fun init(context: PluginContext) {
        if (instance != null) return
        val editorService = context.services.get(IdeEditorService::class.java)
            ?: error("PairPlugin: IdeEditorService not available")
        val fileService = context.services.get(IdeFileService::class.java)
            ?: error("PairPlugin: IdeFileService not available")
        val projectService = context.services.get(IdeProjectService::class.java)
        val deviceSettings = DeviceSettingsStore(dataFile(context, "device-settings.json"))
        val broker = EditBroker(
            editorService = editorService,
            fileService = fileService,
            projectService = projectService,
            logger = context.logger,
            displayName = deviceSettings.deviceName() ?: defaultDisplayName(),
            showPeerCursors = deviceSettings.showPeerCursors(),
        )
        val history = SessionHistoryStore(dataFile(context, "session-history.json"))
        val discovery = PairDiscoveryService(context.androidContext, context.logger)
        instance = Container(context, broker, history, discovery, deviceSettings)
    }

    private fun dataFile(context: PluginContext, name: String): File {
        val dir = runCatching {
            context.services.get(IdeEnvironmentService::class.java)?.getPluginDataDirectory()
        }.getOrNull() ?: File(context.androidContext.filesDir, "pair")
        return File(dir, name)
    }

    fun get(): Container = instance ?: error("PairServiceLocator not initialized")

    fun shutdown() {
        instance?.discovery?.shutdown()
        instance?.broker?.dispose()
        instance = null
    }

    private fun defaultDisplayName(): String {
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
        return model
    }

    class Container internal constructor(
        val pluginContext: PluginContext,
        val broker: EditBroker,
        val history: SessionHistoryStore,
        val discovery: PairDiscoveryService,
        val deviceSettings: DeviceSettingsStore,
    )
}
