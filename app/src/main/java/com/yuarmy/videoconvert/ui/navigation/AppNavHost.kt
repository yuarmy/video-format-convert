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
import com.yuarmy.videoconvert.ui.scan.ScanScreen
import com.yuarmy.videoconvert.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SCAN = "scan"
    const val CONVERT = "convert?uri={uri}&scan={scan}&reason={reason}"
    const val JOB = "job"

    fun convert(uri: Uri, scan: Boolean = false, reason: String? = null): String {
        return "convert?uri=${Uri.encode(uri.toString())}" +
            "&scan=$scan&reason=${Uri.encode(reason.orEmpty())}"
    }
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onVideoPicked = { uri -> navController.navigate(Routes.convert(uri)) },
                onScan = { navController.navigate(Routes.SCAN) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SCAN) {
            ScanScreen(
                onBack = { navController.popBackStack() },
                onFileSelected = { failure ->
                    val reason = buildString {
                        append(failure.kind.label)
                        failure.detail?.takeIf { it.isNotBlank() }?.let {
                            append("：")
                            append(it)
                        }
                    }
                    navController.navigate(
                        Routes.convert(failure.file.uri, scan = true, reason = reason),
                    )
                },
            )
        }
        composable(
            route = Routes.CONVERT,
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("scan") {
                    type = NavType.BoolType
                    defaultValue = false
                },
                navArgument("reason") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
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
