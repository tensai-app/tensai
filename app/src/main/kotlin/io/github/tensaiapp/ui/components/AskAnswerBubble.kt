package io.github.tensaiapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the user answered to a question of the model (TEN-187), as a user bubble: the question small and secondary,
 * the answer under it ([answers] joined with ", "). Same shape and width as the user's [MessageBubble]; plain text.
 */
@Composable
fun AskAnswerBubble(question: String, answers: List<String>, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(modifier = Modifier.fillMaxWidth(0.82f), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                    Text(
                        text = question,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = answers.joinToString(", "),
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
