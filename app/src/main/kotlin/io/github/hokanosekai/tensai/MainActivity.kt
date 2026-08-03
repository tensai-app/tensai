package io.github.hokanosekai.tensai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import io.github.hokanosekai.tensai.data.ThemeRepository
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.navigation.TensaiShell
import io.github.hokanosekai.tensai.ui.theme.TensaiTheme
import io.github.hokanosekai.tensai.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val themeRepository by lazy { ThemeRepository(applicationContext) }

    /** Null until the first-run check below resolves — the splash screen stays up until then. */
    private val hasModelsState = mutableStateOf<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            hasModelsState.value = RuntimeGraph.modelRepository.models.first().isNotEmpty()
        }
        splashScreen.setKeepOnScreenCondition { hasModelsState.value == null }

        setContent {
            val themeMode by themeRepository.themeMode.collectAsState(initial = ThemeMode.System)
            val hasModels = hasModelsState.value
            TensaiTheme(themeMode = themeMode) {
                if (hasModels != null) {
                    TensaiShell(hasModels = hasModels)
                }
            }
        }
    }
}
