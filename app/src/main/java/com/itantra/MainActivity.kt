package com.itantra

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import android.content.ComponentCallbacks2
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.itantra.ui.MainScreen
import com.itantra.ui.theme.ITantraTheme

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so the status and navigation bar icons are always light.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            ITantraTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(mainViewModel)
                }
            }
        }
    }

    override fun onStop() {
        mainViewModel.onForegroundLost()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        mainViewModel.onForegroundGained()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mainViewModel.releaseTranslationRuntime()
    }
}
