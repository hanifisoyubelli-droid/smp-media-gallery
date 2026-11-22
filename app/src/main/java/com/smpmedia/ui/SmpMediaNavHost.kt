package com.smpmedia.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smpmedia.ui.screens.BackupScreen
import com.smpmedia.ui.screens.CollectionDetailScreen
import com.smpmedia.ui.screens.CollectionsScreen
import com.smpmedia.ui.screens.FolderMappingScreen
import com.smpmedia.ui.screens.MainScreen
import com.smpmedia.ui.screens.SettingsScreen

object Routes {
    const val PHOTOS = "photos"
    const val COLLECTIONS = "collections"
    const val COLLECTION = "collection"
    const val BACKUP = "backup"
    const val SETTINGS = "settings"
    const val MAPPING = "mapping"

    /** Bir klasörün içindeki fotoğraflar. */
    fun collection(bucketId: String): String = "$COLLECTION/${Uri.encode(bucketId)}"

    /** Eşleştirme ekranı; istenirse bir klasör seçili olarak açılır. */
    fun mapping(bucketId: String? = null): String =
        if (bucketId.isNullOrBlank()) MAPPING else "$MAPPING?bucketId=$bucketId"
}

private data class BottomItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val bottomItems = listOf(
    BottomItem(Routes.PHOTOS, "Fotoğraflar", Icons.Outlined.PhotoLibrary),
    BottomItem(Routes.COLLECTIONS, "Koleksiyonlar", Icons.Outlined.Folder),
    BottomItem(Routes.BACKUP, "Yedekleme", Icons.Outlined.CloudUpload),
    BottomItem(Routes.SETTINGS, "Ayarlar", Icons.Outlined.Settings)
)

@Composable
fun SmpMediaNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = bottomItems.any { it.route == currentRoute }

    Scaffold(
        // Ekranların kendi TopAppBar'ları durum çubuğu boşluğunu yönetir.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.PHOTOS,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.PHOTOS) {
                MainScreen(
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }
            composable(Routes.COLLECTIONS) {
                CollectionsScreen(
                    onOpenCollection = { bucketId ->
                        navController.navigate(Routes.collection(bucketId))
                    }
                )
            }
            composable(
                route = "${Routes.COLLECTION}/{bucketId}",
                arguments = listOf(
                    navArgument("bucketId") {
                        type = NavType.StringType
                    }
                )
            ) { entry ->
                CollectionDetailScreen(
                    bucketId = entry.arguments?.getString("bucketId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenMapping = { bucketId ->
                        navController.navigate(Routes.mapping(bucketId))
                    }
                )
            }
            composable(Routes.BACKUP) {
                BackupScreen(
                    onOpenMappings = { navController.navigate(Routes.mapping()) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenMappings = { navController.navigate(Routes.mapping()) }
                )
            }
            composable(
                route = "${Routes.MAPPING}?bucketId={bucketId}",
                arguments = listOf(
                    navArgument("bucketId") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { entry ->
                FolderMappingScreen(
                    initialBucketId = entry.arguments?.getString("bucketId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }
        }
    }
}
