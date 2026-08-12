package com.appdevforall.pair.plugin

import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

object PairTooltips {

    const val CATEGORY: String = "plugin_" + PairPlugin.PLUGIN_ID

    const val SIDEBAR: String = "pair.sidebar"
    const val HOST_SESSION: String = "pair.host_session"
    const val JOIN_SESSION: String = "pair.join_session"
    const val JOIN_ADDRESS: String = "pair.join_address"
    const val JOIN_PASSCODE: String = "pair.join_passcode"
    const val JOIN_CONNECT: String = "pair.join_connect"
    const val DEVICE_NAME: String = "pair.device_name"
    const val NEARBY_HOST: String = "pair.nearby_host"
    const val RECENT_SESSION: String = "pair.recent_session"
    const val INVITE_CARD: String = "pair.invite_card"
    const val DISCOVERABLE: String = "pair.discoverable"
    const val PEER_CURSORS: String = "pair.peer_cursors"
    const val RESYNC: String = "pair.resync"
    const val STOP_SESSION: String = "pair.stop_session"
    const val PULL_PROJECT: String = "pair.pull_project"
    const val DISCONNECT: String = "pair.disconnect"

    fun entries(): List<PluginTooltipEntry> = listOf(
        entry(
            tag = SIDEBAR,
            summary = "Pair programming over local WiFi: one device hosts, others join, edits and cursors sync live.",
            detail = """
                <p>Opens the <b>Pair</b> tab, which runs a real-time collaboration
                session between devices on the same network. No server, no account.</p>
                <ul>
                  <li><b>Host a session</b>: your device shows an invite card with its
                  address and a 4 digit passcode.</li>
                  <li><b>Join a session</b>: type the host's address and passcode, or
                  pick the host from the Nearby list.</li>
                  <li>Edits, cursor positions, and file opens flow between peers as
                  they happen; each peer appears in the peer list with a colored
                  cursor in the editor.</li>
                  <li>Past sessions are listed on the Home screen to rename, delete,
                  or reconnect.</li>
                </ul>
            """.trimIndent(),
        ),
        entry(
            tag = HOST_SESSION,
            summary = "Start hosting so other devices on this WiFi can join your editor.",
            detail = """
                <p>Starts a session on this device and shows an invite card with your
                address and a 4 digit passcode. Share those with the person joining.
                Your edits, cursor moves, and open files sync to every peer while the
                session runs.</p>
            """.trimIndent(),
        ),
        entry(
            tag = JOIN_SESSION,
            summary = "Open the join form to connect to a host on this network.",
            detail = """
                <p>Shows fields for the host's address and passcode. Both values are on
                the host's invite card. While the form is open the same button closes
                it again.</p>
            """.trimIndent(),
        ),
        entry(
            tag = JOIN_ADDRESS,
            summary = "The host's address in ip:port form, shown on their invite card.",
            detail = """
                <p>Type it exactly as the host sees it, for example
                <code>192.168.1.42:7050</code>. A full invite link that carries a token
                also works, and then no passcode is needed.</p>
            """.trimIndent(),
        ),
        entry(
            tag = JOIN_PASSCODE,
            summary = "The 4 digit code from the host's invite card.",
            detail = """
                <p>Confirms that the host invited you. Leave it empty only when the
                address already contains an invite token.</p>
            """.trimIndent(),
        ),
        entry(
            tag = JOIN_CONNECT,
            summary = "Connect to the host using the address and passcode above.",
            detail = """
                <p>Active once the address is filled and either a passcode is entered
                or the address carries an invite token. On success this screen switches
                to the connected session view.</p>
            """.trimIndent(),
        ),
        entry(
            tag = DEVICE_NAME,
            summary = "Change the name other peers see for this device.",
            detail = """
                <p>The name appears in every peer list and on your cursor label in the
                other editors.</p>
            """.trimIndent(),
        ),
        entry(
            tag = NEARBY_HOST,
            summary = "A device hosting on this network. Tap the row to join it.",
            detail = """
                <p>Hosts show up here while they have Discoverable turned on. Joining
                from this list needs no address and no passcode.</p>
            """.trimIndent(),
        ),
        entry(
            tag = RECENT_SESSION,
            summary = "A past session. Tap to reconnect, use the menu to rename or delete.",
            detail = """
                <p>Tapping the row connects to the same address and port again. The
                menu renames the entry or deletes it from the list. Deleting does not
                touch any files.</p>
            """.trimIndent(),
        ),
        entry(
            tag = INVITE_CARD,
            summary = "The address and passcode another device needs to join this session.",
            detail = """
                <p>Tap the address or the passcode to copy it. The person joining
                enters both on their Join form. When Discoverable is on they can pick
                this device from their Nearby list instead.</p>
            """.trimIndent(),
        ),
        entry(
            tag = DISCOVERABLE,
            summary = "Announce this session so nearby devices see it in their Nearby list.",
            detail = """
                <p>Uses local network discovery on the current WiFi. Devices joining
                from the Nearby list connect without typing the address or passcode.
                Turn it off to allow joining by address and passcode only.</p>
            """.trimIndent(),
        ),
        entry(
            tag = PEER_CURSORS,
            summary = "Show or hide other peers' carets in your editor.",
            detail = """
                <p>Each peer gets a colored caret with their name at their position in
                the file. This switch only affects your device.</p>
            """.trimIndent(),
        ),
        entry(
            tag = RESYNC,
            summary = "Push the host's current content to all peers to repair drift.",
            detail = """
                <p>Appears when a peer's content no longer matches the host. Resync
                replaces every peer's copy of the open files with the host version.</p>
            """.trimIndent(),
        ),
        entry(
            tag = STOP_SESSION,
            summary = "End the session and disconnect all peers.",
            detail = """
                <p>Peers are disconnected and stop receiving edits. Files on every
                device stay as they are. The invite address and passcode stop
                working.</p>
            """.trimIndent(),
        ),
        entry(
            tag = PULL_PROJECT,
            summary = "Copy the host's project files that are missing on this device.",
            detail = """
                <p>Fetches the host's project over the session connection and shows
                progress as a file count. When it finishes, a prompt offers to open
                the synced project.</p>
            """.trimIndent(),
        ),
        entry(
            tag = DISCONNECT,
            summary = "Leave this session. The host and other peers keep working.",
            detail = """
                <p>Only this device disconnects. Files already pulled stay on this
                device. Reconnect later from the Recent list or with the address and
                passcode.</p>
            """.trimIndent(),
        ),
    )

    private fun entry(tag: String, summary: String, detail: String): PluginTooltipEntry =
        PluginTooltipEntry(
            tag = tag,
            summary = summary,
            detail = detail,
            buttons = listOf(
                PluginTooltipButton(
                    description = "User guide",
                    uri = "index.html",
                    order = 0,
                )
            ),
        )
}
