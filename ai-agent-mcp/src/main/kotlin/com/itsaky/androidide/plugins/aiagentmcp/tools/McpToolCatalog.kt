package com.itsaky.androidide.plugins.aiagentmcp.tools

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "$LOG_PREFIX.McpToolCatalog"

/**
 * What each configured server last said its tools are.
 *
 * The agent reads the tool list on a UI-adjacent path and the contract forbids blocking on the
 * network there, so the list has to be answered from memory. This is that memory: filled by an
 * explicit [refresh] — on activation, on Refresh tools, after an edit — and read by the source.
 */
object McpToolCatalog {

    private val toolsByServer = ConcurrentHashMap<String, List<McpTool>>()

    /**
     * The cached tools for a server.
     * @param serverId the server.
     * @return its tools, empty before the first successful refresh.
     */
    fun tools(serverId: String): List<McpTool> = toolsByServer[serverId].orEmpty()

    /**
     * Re-reads one server's tool list over the network and caches it.
     *
     * Blocking, so call it off the main thread. The stored known-tool names are updated too, which
     * is what drops toggles for tools the server no longer offers.
     *
     * @param server the server to ask.
     * @return the tools it listed.
     * @throws java.io.IOException when the server cannot be reached or refuses.
     */
    fun refresh(server: McpServer): List<McpTool> {
        val tools = McpConnections.session(server).listTools()
        toolsByServer[server.id] = tools
        McpServerStore.setKnownTools(server.id, tools.map { it.name })
        Log.i(TAG, "Server '${server.name}' listed ${tools.size} tool(s)")
        return tools
    }

    /**
     * Refreshes every enabled server, tolerating the ones that fail.
     * @return how many servers answered.
     */
    fun refreshAll(): Int {
        var refreshed = 0
        for (server in McpServerStore.servers().filter { it.enabled }) {
            try {
                refresh(server)
                refreshed++
            } catch (e: Exception) {
                // One unreachable server must not cost the user the tools of the others.
                Log.w(TAG, "Could not list tools for '${server.name}': ${e.message}")
            }
        }
        return refreshed
    }

    /** Forgets a server's tools, for one that was removed or edited. */
    fun forget(serverId: String) {
        toolsByServer.remove(serverId)
    }

    /** Forgets everything, for the plugin shutting down. */
    fun clear() {
        toolsByServer.clear()
    }
}
