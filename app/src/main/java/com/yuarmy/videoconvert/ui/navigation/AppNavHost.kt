package com.yuarmy.videoconvert.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yuarmy.videoconvert.service.ConversionController
import com.yuarmy.videoconvert.ui.convert.ConvertScreen
import com.yuarmy.videoconvert.ui.home.HomeScreen
import com.yuarmy.videoconvert.ui.job.JobScreen
import com.yuarmy.videoconvert.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CONVERT = "convert?uri={uri}"
    const val JOB = "job"

    fun convert(uri: Uri): String = "convert?uri=${Uri.encode(uri.toString())}"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onVideoPicked = { uri -> navController.navigate(Routes.convert(uri)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.CONVERT,
            arguments = listOf(navArgument("uri") { type = NavType.StringType }),
        ) {
            ConvertScreen(
                onBack = { navController.popBackStack() },
                onStarted = { navController.navigate(Routes.JOB) },
            )
        }
        composable(Routes.JOB) {
            JobScreen(
                onDone = {
                    ConversionController.reset()
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
