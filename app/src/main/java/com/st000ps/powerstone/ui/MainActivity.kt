package com.st000ps.powerstone.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("library", "Catalogue", Icons.Filled.Album),
    Tab("playlists", "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    Tab("me", "MY 88.8", Icons.Filled.Radio),
    Tab("account", "Account", Icons.Filled.Person),
)

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            PowerstoneTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route
                val fullScreen = route == "player" || route == "sound"
                val snackbar = remember { SnackbarHostState() }
                // Window size, not screen size: in split-screen / freeform this is our half of the screen.
                val config = LocalConfiguration.current
                val useRail = config.screenHeightDp < 480 || config.screenWidthDp >= 600
                val selectTab: (Tab) -> Unit = { tab ->
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }

                LaunchedEffect(Unit) { vm.messages.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) } }
                LaunchedEffect(Unit) { vm.nav.collect { req -> navigate(nav, req) } }
                LaunchedEffect(Unit) {
                    vm.shares.collect { (url, title) ->
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, title)
                        startActivity(Intent.createChooser(send, title))
                    }
                }

                Row(Modifier.fillMaxSize().background(Ink)) {
                if (useRail && !fullScreen) NavigationRail(containerColor = Panel, modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))) {
                    tabs.forEach { tab ->
                        NavigationRailItem(
                            selected = route?.substringBefore('?') == tab.route,
                            onClick = { selectTab(tab) },
                            icon = { Icon(tab.icon, tab.label) },
                            label = if (config.screenHeightDp >= 360) ({ Text(tab.label) }) else null,
                            colors = NavigationRailItemDefaults.colors(indicatorColor = Edge),
                        )
                    }
                }
                Scaffold(
                    modifier = Modifier.weight(1f),
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        if (!fullScreen) Column {
                            if (state.now.mediaId != null) MiniPlayer(state, vm) { nav.navigate("player") }
                            if (!useRail) NavigationBar(containerColor = Panel) {
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = route?.substringBefore('?') == tab.route,
                                        onClick = { selectTab(tab) },
                                        icon = { Icon(tab.icon, null) },
                                        label = { Text(tab.label) },
                                        colors = NavigationBarItemDefaults.colors(indicatorColor = Edge),
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    val openRelease: (String) -> Unit = { nav.navigate("release/${Uri.encode(it)}") }
                    val openTag: (String) -> Unit = { nav.navigate("tag/${Uri.encode(it)}") }
                    NavHost(nav, startDestination = "library", modifier = Modifier.padding(padding)) {
                        composable("library") { LibraryScreen(state, vm, openRelease, openTag) { nav.navigate("bpm") } }
                        composable("bpm") { BpmScreen(state, vm) { nav.popBackStack() } }
                        composable(
                            "release/{id}?t={t}",
                            arguments = listOf(navArgument("t") { type = NavType.IntType; defaultValue = -1 }),
                        ) { e ->
                            val rel = vm.release(e.arguments?.getString("id") ?: "")
                            val t = e.arguments?.getInt("t")?.takeIf { it > 0 }
                            if (rel != null) ReleaseScreen(rel, t, state, vm, openTag) { nav.popBackStack() }
                            else BackBar("Not found", { nav.popBackStack() })
                        }
                        composable("playlists") {
                            PlaylistsScreen(state, vm, open = { nav.navigate("playlist/$it") }, openFavs = { nav.navigate("me") })
                        }
                        composable("playlist/{id}") { e -> PlaylistScreen(e.arguments?.getString("id") ?: "", state, vm) { nav.popBackStack() } }
                        composable("shared/{id}") { e -> SharedPlaylistScreen(e.arguments?.getString("id") ?: "", state, vm) { nav.popBackStack() } }
                        composable("tag/{tag}") { e -> TagScreen(e.arguments?.getString("tag") ?: "", state, vm) { nav.popBackStack() } }
                        composable(
                            "me?wrap={wrap}",
                            arguments = listOf(navArgument("wrap") { type = NavType.IntType; defaultValue = -1 }),
                        ) { e -> MeScreen(state, vm, e.arguments?.getInt("wrap")?.takeIf { it > 0 }, openRelease, openTag) }
                        composable("account") { AccountScreen(state, vm) { nav.navigate("sound") } }
                        composable("player") {
                            PlayerScreen(state, vm, close = { nav.popBackStack() }, openSound = { nav.navigate("sound") }, openRelease = {
                                nav.popBackStack(); openRelease(it)
                            })
                        }
                        composable("sound") { SoundScreen(state, vm) { nav.popBackStack() } }
                    }
                }
                }
            }
        }
    }

    private fun navigate(nav: NavHostController, req: NavRequest) = when (req) {
        is NavRequest.Release -> nav.navigate("release/${Uri.encode(req.id)}?t=${req.track ?: -1}")
        is NavRequest.Playlist -> nav.navigate("playlist/${req.id}")
        is NavRequest.Shared -> nav.navigate("shared/${Uri.encode(req.id)}")
        is NavRequest.Tag -> nav.navigate("tag/${Uri.encode(req.tag)}")
        is NavRequest.Me -> nav.navigate("me?wrap=${req.wrapYear ?: -1}") { launchSingleTop = true }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(vm::openLink)
        intent?.getStringExtra(com.st000ps.powerstone.widget.PlayerWidget.EXTRA_WIDGET_ACTION)?.let {
            intent.removeExtra(com.st000ps.powerstone.widget.PlayerWidget.EXTRA_WIDGET_ACTION)
            vm.widgetAction(it)
        }
    }
}
