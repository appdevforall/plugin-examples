package com.appdevforall.pair.plugin.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.appdevforall.pair.plugin.data.DiscoveredHost
import com.appdevforall.pair.plugin.data.PeerSession
import com.appdevforall.pair.plugin.data.SessionRole
import com.appdevforall.pair.plugin.data.SessionState
import com.appdevforall.pair.plugin.data.StoredSession
import com.appdevforall.pair.plugin.ui.main.PairUiState
import com.appdevforall.pair.plugin.ui.theme.PluginTheme

@Preview(name = "Light", showBackground = true, backgroundColor = 0xFFFAFAF9)
@Preview(
    name = "Dark",
    showBackground = true,
    backgroundColor = 0xFF0A0A0A,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
annotation class ThemePreviews

@Preview(name = "Light", showBackground = true, widthDp = 360, heightDp = 760)
@Preview(
    name = "Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    widthDp = 360,
    heightDp = 760,
)
annotation class ScreenThemePreviews

@Composable
fun PluginPreview(
    contentPadding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    PluginTheme(useHostTheme = false) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Box(modifier = Modifier.padding(contentPadding)) {
                content()
            }
        }
    }
}

object PreviewSamples {

    private val now: Long = System.currentTimeMillis()

    val hostPeer = PeerSession(
        peerId = "peer-host",
        displayName = "Pixel 8 Pro",
        colorIndex = 0,
        isHost = true,
        joinedAtMillis = now - 240_000L,
        currentFile = "/project/app/src/main/java/MainActivity.kt",
        cursorLine = 41,
        cursorColumn = 8,
    )

    val guestPeer = PeerSession(
        peerId = "peer-guest",
        displayName = "Galaxy S24",
        colorIndex = 1,
        isHost = false,
        joinedAtMillis = now - 90_000L,
        currentFile = "/project/app/src/main/res/values/strings.xml",
        cursorLine = 12,
        cursorColumn = 2,
    )

    val idlePeer = PeerSession(
        peerId = "peer-idle",
        displayName = "Galaxy Tab",
        colorIndex = 2,
        isHost = false,
        joinedAtMillis = now - 30_000L,
        currentFile = null,
        cursorLine = null,
        cursorColumn = null,
    )

    val peers: List<PeerSession> = listOf(hostPeer, guestPeer, idlePeer)

    val storedSessions: List<StoredSession> = listOf(
        StoredSession(
            id = "192.168.1.42:7050",
            customName = "Studio iMac",
            address = "192.168.1.42",
            port = 7050,
            role = SessionRole.HOST,
            lastConnectedMillis = now - 300_000L,
        ),
        StoredSession(
            id = "192.168.1.77:7050",
            customName = null,
            address = "192.168.1.77",
            port = 7050,
            role = SessionRole.GUEST,
            lastConnectedMillis = now - 7_200_000L,
        ),
        StoredSession(
            id = "10.0.0.5:7050",
            customName = "Alex laptop",
            address = "10.0.0.5",
            port = 7050,
            role = SessionRole.GUEST,
            lastConnectedMillis = now - 172_800_000L,
        ),
    )

    val discoveredHosts: List<DiscoveredHost> = listOf(
        DiscoveredHost(
            serviceName = "Pixel 8 Pro",
            peerId = "disc-pixel",
            displayName = "Daniel's Pixel 8 Pro",
            host = "192.168.4.21",
            port = 7050,
            token = "preview-token-1",
            protocolVersion = 1,
        ),
        DiscoveredHost(
            serviceName = "Classroom Tablet",
            peerId = "disc-tablet",
            displayName = "Classroom Tablet",
            host = "192.168.4.37",
            port = 7050,
            token = "preview-token-2",
            protocolVersion = 1,
        ),
    )

    val idleSession = SessionState(
        role = SessionRole.IDLE,
        localPeerId = "local",
        localDisplayName = "Pixel 8 Pro",
    )

    val hostSession = SessionState(
        role = SessionRole.HOST,
        localPeerId = "local",
        localDisplayName = "Pixel 8 Pro",
        localAddress = "192.168.1.42",
        localPort = 7050,
        peers = listOf(guestPeer, idlePeer),
    )

    val hostWaitingSession = hostSession.copy(peers = emptyList())

    val guestSession = SessionState(
        role = SessionRole.GUEST,
        localPeerId = "local",
        localDisplayName = "Galaxy S24",
        remoteAddress = "192.168.1.42:7050",
        peers = listOf(hostPeer),
    )

    val homeState = PairUiState(
        session = idleSession,
        recentSessions = storedSessions,
        discoveredHosts = discoveredHosts,
    )

    val homeJoinState = homeState.copy(
        joinMode = true,
        addressInput = "192.168.1.42:7050",
        passcodeInput = "4821",
    )

    val homeErrorState = PairUiState(
        session = idleSession.copy(lastError = "Could not reach host. Check the address."),
        recentSessions = storedSessions,
    )

    val hostUiState = PairUiState(session = hostSession)

    val guestUiState = PairUiState(session = guestSession)
}