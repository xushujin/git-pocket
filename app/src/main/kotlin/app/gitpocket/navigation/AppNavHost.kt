package app.gitpocket.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.gitpocket.feature.commit.CommitScreen
import app.gitpocket.feature.editor.DiffScreen
import app.gitpocket.feature.editor.EditorScreen
import app.gitpocket.feature.repo.BrowseScreen
import app.gitpocket.feature.repo.RepoDetailScreen
import app.gitpocket.feature.repos.ReposScreen
import app.gitpocket.feature.settings.AboutScreen
import app.gitpocket.feature.settings.SettingsScreen

private object Routes {
    const val REPOS = "repos"
    const val REPO = "repo/{repoId}"
    const val BROWSE = "browse/{repoId}?path={path}"
    const val EDITOR = "editor/{repoId}?path={path}"
    const val DIFF = "diff/{repoId}?path={path}"
    const val COMMIT = "commit/{repoId}"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    fun repo(id: String) = "repo/$id"
    fun browse(id: String, path: String) = "browse/$id?path=${Uri.encode(path)}"
    fun editor(id: String, path: String) = "editor/$id?path=${Uri.encode(path)}"
    fun diff(id: String, path: String) = "diff/$id?path=${Uri.encode(path)}"
    fun commit(id: String) = "commit/$id"
}

@Composable
fun AppNavHost(appVersion: String) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.REPOS) {
        composable(Routes.REPOS) {
            ReposScreen(
                onOpenRepo = { navController.navigate(Routes.repo(it)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = Routes.REPO,
            arguments = listOf(navArgument("repoId") { type = NavType.StringType }),
        ) { entry ->
            val repoId = entry.arguments?.getString("repoId").orEmpty()
            RepoDetailScreen(
                repoId = repoId,
                onBack = { navController.popBackStack() },
                onOpenFile = { path -> navController.navigate(Routes.editor(repoId, path)) },
                onOpenCommit = { navController.navigate(Routes.commit(repoId)) },
                onOpenDirectory = { path -> navController.navigate(Routes.browse(repoId, path)) },
            )
        }

        composable(
            route = Routes.BROWSE,
            arguments = listOf(
                navArgument("repoId") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val repoId = entry.arguments?.getString("repoId").orEmpty()
            val path = entry.arguments?.getString("path").orEmpty()
            BrowseScreen(
                repoId = repoId,
                path = path,
                onBack = { navController.popBackStack() },
                onOpenDirectory = { navController.navigate(Routes.browse(repoId, it)) },
                onOpenFile = { navController.navigate(Routes.editor(repoId, it)) },
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument("repoId") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val repoId = entry.arguments?.getString("repoId").orEmpty()
            val path = entry.arguments?.getString("path").orEmpty()
            EditorScreen(
                repoId = repoId,
                path = path,
                onBack = { navController.popBackStack() },
                onOpenDiff = { navController.navigate(Routes.diff(repoId, path)) },
            )
        }

        composable(
            route = Routes.DIFF,
            arguments = listOf(
                navArgument("repoId") { type = NavType.StringType },
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            DiffScreen(
                repoId = entry.arguments?.getString("repoId").orEmpty(),
                path = entry.arguments?.getString("path").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.COMMIT,
            arguments = listOf(navArgument("repoId") { type = NavType.StringType }),
        ) { entry ->
            CommitScreen(
                repoId = entry.arguments?.getString("repoId").orEmpty(),
                onBack = { navController.popBackStack() },
                onCommitted = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenAbout = { navController.navigate(Routes.ABOUT) },
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(
                appVersion = appVersion,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
