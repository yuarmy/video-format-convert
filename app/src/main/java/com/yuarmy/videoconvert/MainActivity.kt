package com.yuarmy.videoconvert

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.yuarmy.videoconvert.ui.navigation.AppNavHost
import com.yuarmy.videoconvert.ui.theme.VideoConvertTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VideoConvertTheme {
                AppNavHost()
            }
        }
    }
}
