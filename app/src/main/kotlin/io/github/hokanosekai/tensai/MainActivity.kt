package io.github.hokanosekai.tensai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.hokanosekai.tensai.data.ThemeRepository
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
                GlyphScreen()
            }
        }
    }
}

// Standing in for the real bootstrap screen until the shell UI lands.
@Composable
private fun GlyphScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        KanjiGlyph(size = 150.dp)
    }
}
