package com.itsaky.androidide.plugins.aiagentmcp.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.client.McpCredentials
import com.itsaky.androidide.plugins.aiagentmcp.client.McpSession
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
     * @property secretsUnreadable whether a stored token or header cannot be decrypted on this
     *   device, which the field has to say aloud: it looks stored, but nothing can send it.
     * @property headers the extra headers configured for the server.
     */
    data class FormState(
        val hasToken: Boolean,
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
     * What an empty token field means when testing: send whatever is stored.
     *
     * The mirror of [tokenToStore], and here rather than in the screen so the one convention the
     * dialog's placeholder promises is written once.
     *
     * @param id the server being tested.
     * @param typed the current contents of the token field.
     * @return the token to send, empty when the server needs none.
     * @throws UnreadableSecretException when a token is stored but cannot be decrypted here.
     */
    private fun tokenToSend(id: String, typed: String): String {
        tokenToStore(typed)?.let { return it }
        return when (val stored = McpServerStore.token(id)) {
            is SecureTokenStore.Stored.Value -> stored.plain
            SecureTokenStore.Stored.Absent -> ""
            SecureTokenStore.Stored.Unreadable ->
                throw UnreadableSecretException("The stored token for '$id' cannot be decrypted.")
        }
    }

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
     * Performs the handshake and asks for the tool list, without storing anything.
     *
     * A server that handshakes but exposes no tool catalogue is still reported as reachable — some
     * expose only prompts or resources, which this plugin does not use.
     *
     * @param server the server to test, with the values currently in the form.
     * @param typedToken the current contents of the token field; empty means "use the stored one",
     *   which is resolved here so the screen never holds a decrypted credential.
     * @param headers the extra headers currently in the form, so a test exercises what a real call
     *   would send rather than what was last saved.
     * @param onResult receives the sentence to show.
     */
    fun testConnection(
        server: McpServer,
        typedToken: String,
        headers: Map<String, String> = emptyMap(),
        onResult: (String) -> Unit,
    ) {
        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                // The form's own values, not the store's: a test has to exercise the unsaved edit.
                val typed = try {
                    McpCredentials(tokenToSend(server.id, typedToken), headers)
                } catch (e: UnreadableSecretException) {
                    return@withContext McpErrorFormatter.format(
                        getContext()?.androidContext, server.name, e
                    )
                }
                val session = McpSession(server.url.trim(), { typed })
                try {
                    session.initialize()
                    val tools = try {
                        session.listTools()
                    } catch (e: Exception) {
                        // A missing catalogue is not a failed connection.
                        emptyList<McpTool>()
                    }
                    val name = session.serverName ?: server.name
                    if (tools.isEmpty()) {
                        string(R.string.mcp_status_connected_no_tools, name)
                    } else {
                        string(R.string.mcp_status_connected, name, tools.size)
                    }
                } catch (e: Exception) {
                    McpErrorFormatter.format(getContext()?.androidContext, server.name, e)
                } finally {
                    runCatching { session.close() }
                }
            }
            onResult(message)
        }
    }

    /**
     * Re-reads one server's tool catalogue and stores it.
     * @param server the server to refresh.
     * @param onResult receives the sentence to show and the tools now known.
     */
    fun refreshTools(server: McpServer, onResult: (String, List<McpTool>) -> Unit) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val tools = McpToolCatalog.refresh(server)
                    string(R.string.mcp_status_tools_refreshed, tools.size) to tools
                } catch (e: Exception) {
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
