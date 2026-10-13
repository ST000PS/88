package com.st000ps.powerstone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("library", "Catalogue", Icons.Filled.Album),
    Tab("favs", "Favourites", Icons.Filled.Favorite),
    Tab("account", "Account", Icons.Filled.Person),
)

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PowerstoneTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route
                val onPlayer = route == "player"

                Scaffold(
                    bottomBar = {
                        if (!onPlayer) Column {
                            if (state.now.mediaId != null) MiniPlayer(state, vm) { nav.navigate("player") }
                            NavigationBar(containerColor = Panel) {
                                tabs.forEach { tab ->
                                    NavigationBarItem(
                                        selected = route == tab.route,
                                        onClick = {
                                            nav.navigate(tab.route) {
                                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(tab.icon, null) },
                                        label = { Text(tab.label) },
                                        colors = NavigationBarItemDefaults.colors(indicatorColor = Edge),
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    NavHost(nav, startDestination = "library", modifier = Modifier.padding(padding)) {
                        composable("library") { LibraryScreen(state, vm) { id -> nav.navigate("release/$id") } }
                        composable("release/{id}") { entry ->
                            val rel = vm.release(entry.arguments?.getString("id") ?: "")
                            if (rel != null) ReleaseScreen(rel, state, vm) { nav.popBackStack() }
                        }
                        composable("favs") { FavsScreen(state, vm) { nav.navigate("account") } }
                        composable("account") { AccountScreen(state, vm) }
                        composable("player") { PlayerScreen(state, vm) { nav.popBackStack() } }
                    }
                }
            }
        }
    }
}
