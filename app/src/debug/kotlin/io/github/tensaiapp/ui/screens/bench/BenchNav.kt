package io.github.tensaiapp.ui.screens.bench

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.tensaiapp.R
import io.github.tensaiapp.ui.navigation.TensaiDestination
import io.github.tensaiapp.ui.screens.settings.Chevron
import io.github.tensaiapp.ui.screens.settings.SectionCard
import io.github.tensaiapp.ui.screens.settings.SettingsRow
import io.github.tensaiapp.ui.theme.tensaiColors

/** The bench routes under [TensaiDestination.Bench]: they draw their own top bar, the shell none. */
private object BenchRoutes {
    const val Compare = "bench/compare/{runs}"
    const val Prompt = "bench/prompt/{run}/{key}?prompt={prompt}"

    fun compare(runIds: List<String>) = "bench/compare/" + runIds.joinToString(",")

    fun prompt(runId: String, promptId: String, key: String) = "bench/prompt/$runId/$key?prompt=${Uri.encode(promptId)}"
}

/** Debug builds only (the release source set adds nothing): the bench screen, Compare and the prompt detail (TEN-187). */
fun NavGraphBuilder.benchGraph(navController: NavHostController) {
    composable(TensaiDestination.Bench.route) {
        BenchScreen(
            onBack = { navController.popBackStack() },
            onCompare = { navController.navigate(BenchRoutes.compare(it)) { launchSingleTop = true } },
            onOpenModels = { navController.navigate(TensaiDestination.Models.route) { launchSingleTop = true } },
        )
    }
    composable(BenchRoutes.Compare) { entry ->
        BenchCompareScreen(
            runIds = entry.arguments?.getString("runs").orEmpty().split(',').filter { it.isNotBlank() },
            onBack = { navController.popBackStack() },
            onOpenPrompt = { run, prompt, key -> navController.navigate(BenchRoutes.prompt(run, prompt, key)) { launchSingleTop = true } },
        )
    }
    composable(BenchRoutes.Prompt) { entry ->
        BenchPromptScreen(
            runId = entry.arguments?.getString("run").orEmpty(),
            promptId = entry.arguments?.getString("prompt").orEmpty(),
            runKey = entry.arguments?.getString("key").orEmpty(),
            onBack = { navController.popBackStack() },
        )
    }
}

/** Settings' DEBUG section, debug builds only: one row to the bench screen. */
@Composable
internal fun BenchSettingsSection(onOpen: () -> Unit) {
    Eyebrow(stringResource(R.string.bench_settings_section), color = MaterialTheme.tensaiColors.warning)
    SectionCard {
        SettingsRow(
            icon = Icons.Rounded.Science,
            title = stringResource(R.string.bench_screen_title),
            supporting = stringResource(R.string.bench_settings_sub),
            onClick = onOpen,
            trailing = { Chevron() },
        )
    }
}
