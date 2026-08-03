package io.github.hokanosekai.tensai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.hokanosekai.tensai.data.ThemeRepository
import io.github.hokanosekai.tensai.ui.navigation.TensaiShell
import io.github.hokanosekai.tensai.ui.theme.TensaiTheme
import io.github.hokanosekai.tensai.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    private val themeRepository by lazy { ThemeRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by themeRepository.themeMode.collectAsState(initial = ThemeMode.System)
            TensaiTheme(themeMode = themeMode) {
                TensaiShell()
            }
        }
    }
}
