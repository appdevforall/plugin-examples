package com.itsaky.androidide.plugins.aiagentmcp.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.aiagentmcp.errors.McpErrorFormatter
import com.itsaky.androidide.plugins.aiagentmcp.security.SecureTokenStore
import com.itsaky.androidide.plugins.aiagentmcp.security.UnreadableSecretException
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State and background work for the MCP settings pane.
 *
 * Everything that touches the Keystore or the network happens here on [Dispatchers.IO]; the
 * fragment only renders what this exposes.
 *
 * @param getContext this plugin's context, for preferences and its own strings.
 */
class McpSettingsViewModel(
    private val getContext: () -> PluginContext?,
) : ViewModel() {

    private val _servers = MutableStateFlow(McpServerStore.servers())

    /** The configured servers, re-read after every change. */
    val servers: StateFlow<List<McpServer>> = _servers.asStateFlow()

    /** Re-reads the stored servers, e.g. after returning to the screen. */
    fun reload() {
        _servers.value = McpServerStore.servers()
    }

    /**
     * What the add/edit dialog needs from the store before it can show a server.
     * @property hasToken whether a token is stored, so the field can say so.
     * @property hasHeaders whether headers are stored, from key presence rather than a decrypt:
     *   [headers] comes back empty for headers this device can no longer read, and "stored but
     *   unreadable" has to count as a stored credential or the control that clears it hides.
     * @property secretsUnreadable whether a stored token or header cannot be decrypted on this
     *   device, which the field has to say aloud: it looks stored, but nothing can send it.
     * @property headers the extra headers configured for the server.
     */
    data class FormState(
        val hasToken: Boolean,
        val hasHeaders: Boolean,
        val secretsUnreadable: Boolean,
        val headers: Map<String, String>,
    )

    /** A server with a fresh id, ready for the dialog to fill in. */
    fun newServer(): McpServer = McpServerStore.newServer("", "")

    /**
     * The tools switched on for a server, for rendering its tool list.
     * @param id the server.
     * @return the enabled tool names; empty when the server is unknown.
     */
    fun enabledTools(id: String): Set<String> = McpServerStore.server(id)?.enabledTools.orEmpty()

    /**
     * Reads what the dialog needs about an existing server.
     *
     * One load rather than two, and off the main thread: both are Keystore decrypts. The token is
     * decrypted rather than merely counted, because "stored" and "stored but unreadable here" are
     * what the field has to tell apart.
     *
     * @param id the server being edited.
     * @param onLoaded receives the state, on the main thread.
     */
    fun loadForm(id: String, onLoaded: (FormState) -> Unit) {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                val token = McpServerStore.token(id)
                val headers = try {
                    McpServerStore.headers(id)
                } catch (e: UnreadableSecretException) {
                    null
                }
                FormState(
                    hasToken = McpServerStore.hasToken(id),
                    hasHeaders = McpServerStore.hasHeaders(id),
                    secretsUnreadable =
                        token is SecureTokenStore.Stored.Unreadable || headers == null,
                    headers = headers.orEmpty(),
                )
            }
            onLoaded(state)
        }
    }

    /**
     * What an empty token field means when saving: leave whatever is stored alone.
     * @param typed the current contents of the token field.
     * @return the token to store, or null to keep the stored one.
     */
    fun tokenToStore(typed: String): String? = typed.trim().takeIf { it.isNotEmpty() }

    /**
     * Stores the edited name and URL of a server and, when given, its token.
     *
     * Only those fields are written: the tool switches are saved as they are tapped, and the
     * dialog's snapshot predates them.
     *
     * @param server the server to store.
     * @param token the token to store, or null to leave the stored one alone.
     * @param headers the extra headers to store, replacing whatever was there.
     * @param onDone receives the merged record and a status sentence, null when everything worked.
     */
    fun save(
        server: McpServer,
        token: String?,
        headers: Map<String, String> = emptyMap(),
        onDone: (McpServer, String?) -> Unit,
    ) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val merged = McpServerStore.saveDetails(server)
                val tokenStored = token?.let { McpServerStore.setToken(server.id, it.trim()) } ?: true
                val headersStored = McpServerStore.setHeaders(server.id, headers)
                // A credential change has to invalidate the session, or the old one keeps working.
                McpConnections.invalidate(server.id)
                val failure = when {
                    !tokenStored -> string(R.string.mcp_token_save_failed)
                    !headersStored -> string(R.string.mcp_headers_save_failed)
                    else -> null
                }
                merged to failure
            }
            reload()
            onDone(outcome.first, outcome.second)
        }
    }

    /**
     * Forgets a server's stored credential: the token and every extra header.
     *
     * The token field never shows what is stored, and an empty field means "keep it", so this is
     * the only way back to "this server needs no credential" — which is also what unblocks an
     * `http://` URL for a server that once had one.
     *
     * @param id the server to strip.
     * @param onDone receives true when nothing is stored any more, on the main thread.
     */
    fun clearCredential(id: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val cleared = withContext(Dispatchers.IO) {
                val tokenCleared = McpServerStore.setToken(id, "")
                val headersCleared = McpServerStore.setHeaders(id, emptyMap())
                // The session was keyed by the credential it no longer has.
                McpConnections.invalidate(id)
                tokenCleared && headersCleared
            }
            onDone(cleared)
        }
    }

    /**
     * Removes a server, its token, its session and its cached tools.
     * @param id the server to remove.
     */
    fun delete(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                McpConnections.invalidate(id)
                McpToolCatalog.forget(id)
                McpServerStore.remove(id)
            }
            reload()
        }
    }

    /**
     * Switches a whole server on or off.
     * @param id the server.
     * @param enabled whether it may contribute tools.
     */
    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { McpServerStore.setEnabled(id, enabled) }
            reload()
        }
    }

    /**
     * Switches one of a server's tools on or off.
     * @param id the server.
     * @param toolName the tool.
     * @param enabled whether the agent may see it.
     */
    fun setToolEnabled(id: String, toolName: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                McpServerStore.setToolEnabled(id, toolName, enabled)
            }
            reload()
        }
    }

    /**
     * Connects to a saved server: handshake, tool list, and the list cached for the agent. Reads
     * the URL and credentials from the store, so save the form first. A server that handshakes but
     * offers no tool catalogue is still reachable — some expose only prompts or resources.
     *
     * @param server the server to connect to, as stored.
     * @param onResult receives the sentence to show and the tools now known.
     */
    fun connect(server: McpServer, onResult: (String, List<McpTool>) -> Unit) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val listing = McpToolCatalog.connect(server)
                    val name = listing.serverName ?: server.name
                    val message = if (listing.tools.isEmpty()) {
                        string(R.string.mcp_status_connected_no_tools, name)
                    } else {
                        string(R.string.mcp_status_connected, name, listing.tools.size)
                    }
                    message to listing.tools
                } catch (e: Exception) {
                    // The cached tools: a failed reconnect must not blank out the switches.
                    McpErrorFormatter.format(getContext()?.androidContext, server.name, e) to
                        McpToolCatalog.tools(server.id)
                }
            }
            reload()
            onResult(outcome.first, outcome.second)
        }
    }

    /**
     * Resolves a string against this plugin's own resources.
     * @param resId the string resource.
     * @param args format arguments.
     * @return the resolved string, or empty when the context is gone.
     */
    private fun string(resId: Int, vararg args: Any?): String =
        getContext()?.androidContext?.getString(resId, *args).orEmpty()
}

/**
 * Builds [McpSettingsViewModel] with this plugin's context.
 * @param getContext supplier of the plugin context.
 */
class McpSettingsViewModelFactory(
    private val getContext: () -> PluginContext?,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        McpSettingsViewModel(getContext) as T
}
