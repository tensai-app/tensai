package io.github.hokanosekai.tensai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Bootstrap()
        }
    }
}

// Placeholder until the Calm Night theme lands (TEN-21).
@Composable
private fun Bootstrap() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0E17)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "天", color = Color(0xFFA78BFA), fontSize = 96.sp)
    }
}
