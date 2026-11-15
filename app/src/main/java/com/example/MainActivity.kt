package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.data.model.ChannelInfo
import com.example.ui.components.PlayerOverlay
import com.example.ui.navigation.ChannelRoute
import com.example.ui.navigation.ExploreRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.LibraryRoute
import com.example.ui.navigation.SearchRoute
import com.example.ui.screens.ChannelScreen
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.theme.XemTubeTheme
import com.example.ui.viewmodel.XemTubeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: XemTubeViewModel = viewModel()
            val isDarkTheme by viewModel.isDarkTheme.collectAsStateWithLifecycle()

            XemTubeTheme(darkTheme = isDarkTheme) {
                XemTubeApp(viewModel = viewModel, isDarkTheme = isDarkTheme)
            }
        }
    }
}

private data class TopTabItem(
    val route: Any,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

@Composable
fun XemTubeApp(
    viewModel: XemTubeViewModel,
    isDarkTheme: Boolean,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val homeState by viewModel.homeState.collectAsStateWithLifecycle()
    val exploreState by viewModel.exploreState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val channelState by viewModel.channelState.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val libraryState by viewModel.libraryState.collectAsStateWithLifecycle()
    val savedVideoIds by viewModel.savedVideoIds.collectAsStateWithLifecycle()
    val subscribedChannelIds by viewModel.subscribedChannelIds.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()

    val tabs = listOf(
        TopTabItem(HomeRoute, R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home, "tab_home"),
        TopTabItem(ExploreRoute, R.string.nav_explore, Icons.Filled.Explore, Icons.Outlined.Explore, "tab_explore"),
        TopTabItem(LibraryRoute, R.string.nav_library, Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary, "tab_library"),
    )

    val isTopLevelRoute = currentDestination?.let { dest ->
        dest.hasRoute<HomeRoute>() || dest.hasRoute<ExploreRoute>() || dest.hasRoute<LibraryRoute>()
    } ?: true

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpandedWidth = maxWidth >= 840.dp
        val hasMiniPlayer = playerState.activeVideo != null && !playerState.isExpanded
        val miniPlayerPadding = if (hasMiniPlayer) 84.dp else 0.dp
        val bottomBarHeightForMiniPlayer = if (!isExpandedWidth && isTopLevelRoute) 80.dp else 0.dp

        val navigateToTab: (Any) -> Unit = { route ->
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        val openChannel: (ChannelInfo) -> Unit = { channel ->
            navController.navigate(
                ChannelRoute(
                    channelId = channel.id,
                    channelName = channel.name,
                    avatarUrl = channel.avatarUrl,
                ),
            )
        }

        Row(modifier = Modifier.fillMaxSize()) {
            if (isExpandedWidth && isTopLevelRoute) {
                NavigationRail(
                    modifier = Modifier
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    tabs.forEach { tab ->
                        val selected = when (tab.route) {
                            is HomeRoute -> currentDestination?.hasRoute<HomeRoute>() == true
                            is ExploreRoute -> currentDestination?.hasRoute<ExploreRoute>() == true
                            is LibraryRoute -> currentDestination?.hasRoute<LibraryRoute>() == true
                            else -> false
                        }
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateToTab(tab.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = stringResource(tab.labelRes),
                                )
                            },
                            label = { Text(stringResource(tab.labelRes)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                            modifier = Modifier.testTag(tab.testTag),
                        )
                    }
                }
            }

            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.statusBars,
                bottomBar = {
                    if (!isExpandedWidth && isTopLevelRoute) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            windowInsets = WindowInsets.navigationBars,
                        ) {
                            tabs.forEach { tab ->
                                val selected = when (tab.route) {
                                    is HomeRoute -> currentDestination?.hasRoute<HomeRoute>() == true
                                    is ExploreRoute -> currentDestination?.hasRoute<ExploreRoute>() == true
                                    is LibraryRoute -> currentDestination?.hasRoute<LibraryRoute>() == true
                                    else -> false
                                }
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = stringResource(tab.labelRes),
                                        )
                                    },
                                    label = { Text(stringResource(tab.labelRes)) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                    modifier = Modifier.testTag(tab.testTag),
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                val navBottomPad = if (isExpandedWidth || !isTopLevelRoute) {
                    Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                } else {
                    Modifier
                }

                NavHost(
                    navController = navController,
                    startDestination = HomeRoute,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .then(navBottomPad),
                ) {
                    composable<HomeRoute> {
                        HomeScreen(
                            homeState = homeState,
                            savedVideoIds = savedVideoIds,
                            isDarkTheme = isDarkTheme,
                            extraBottomPadding = miniPlayerPadding,
                            onSelectCategory = viewModel::selectCategory,
                            onRefresh = viewModel::refreshHome,
                            onSearchClick = { navController.navigate(SearchRoute) },
                            onToggleTheme = viewModel::toggleTheme,
                            onVideoClick = viewModel::playVideo,
                            onChannelClick = openChannel,
                            onToggleSave = viewModel::toggleSaveVideo,
                        )
                    }

                    composable<ExploreRoute> {
                        ExploreScreen(
                            exploreState = exploreState,
                            savedVideoIds = savedVideoIds,
                            extraBottomPadding = miniPlayerPadding,
                            onSearchClick = { navController.navigate(SearchRoute) },
                            onRetry = viewModel::loadExplore,
                            onVideoClick = viewModel::playVideo,
                            onChannelClick = openChannel,
                            onToggleSave = viewModel::toggleSaveVideo,
                        )
                    }

                    composable<LibraryRoute> {
                        LibraryScreen(
                            libraryState = libraryState,
                            savedVideoIds = savedVideoIds,
                            extraBottomPadding = miniPlayerPadding,
                            onVideoClick = viewModel::playVideo,
                            onChannelClick = openChannel,
                            onToggleSave = viewModel::toggleSaveVideo,
                            onRemoveHistory = viewModel::removeFromWatchHistory,
                            onClearHistory = viewModel::clearWatchHistory,
                            onBrowseHome = { navigateToTab(HomeRoute) },
                        )
                    }

                    composable<SearchRoute> {
                        SearchScreen(
                            searchState = searchState,
                            recentSearches = recentSearches,
                            savedVideoIds = savedVideoIds,
                            extraBottomPadding = miniPlayerPadding,
                            onQueryChange = viewModel::updateSearchQuery,
                            onSubmitSearch = viewModel::submitSearch,
                            onDeleteRecent = viewModel::deleteSearchHistory,
                            onBack = { navController.popBackStack() },
                            onVideoClick = viewModel::playVideo,
                            onChannelClick = openChannel,
                            onToggleSave = viewModel::toggleSaveVideo,
                        )
                    }

                    composable<ChannelRoute> { entry ->
                        val route = entry.toRoute<ChannelRoute>()
                        LaunchedEffect(route.channelId) {
                            viewModel.loadChannel(route.channelId, route.channelName, route.avatarUrl)
                        }
                        ChannelScreen(
                            channelState = channelState,
                            subscribedChannelIds = subscribedChannelIds,
                            savedVideoIds = savedVideoIds,
                            extraBottomPadding = miniPlayerPadding,
                            onBack = { navController.popBackStack() },
                            onRetry = {
                                viewModel.loadChannel(route.channelId, route.channelName, route.avatarUrl)
                            },
                            onToggleSubscribe = viewModel::toggleSubscribeChannel,
                            onVideoClick = viewModel::playVideo,
                            onToggleSave = viewModel::toggleSaveVideo,
                        )
                    }
                }
            }
        }

        // Collapsible Full/Mini YouTube Player Overlay
        PlayerOverlay(
            playerState = playerState,
            savedVideoIds = savedVideoIds,
            subscribedChannelIds = subscribedChannelIds,
            bottomBarHeightPadding = bottomBarHeightForMiniPlayer,
            onExpand = viewModel::expandPlayer,
            onMinimize = viewModel::minimizePlayer,
            onClose = viewModel::closePlayer,
            onPlayVideo = viewModel::playVideo,
            onToggleSave = viewModel::toggleSaveVideo,
            onToggleSubscribe = viewModel::toggleSubscribeChannel,
            onOpenChannel = openChannel,
            onProgressUpdate = viewModel::updatePlaybackProgress,
            onFullscreenChanged = viewModel::setFullscreen,
            onSetPlaying = viewModel::setPlayingState,
            onSetPlaybackSpeed = viewModel::setPlaybackSpeed,
            onEmbedError = viewModel::markEmbedError,
        )
    }
}
