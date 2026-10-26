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
    const val DecodeRun = "bench/decode/{run}"
    const val DecodeCompare = "bench/decode-compare/{runs}"

    fun compare(runIds: List<String>) = "bench/compare/" + runIds.joinToString(",")

    fun prompt(runId: String, promptId: String, key: String) = "bench/prompt/$runId/$key?prompt=${Uri.encode(promptId)}"

    fun decodeRun(runId: String) = "bench/decode/$runId"

    fun decodeCompare(runIds: List<String>) = "bench/decode-compare/" + runIds.joinToString(",")
}

/**
 * Debug builds only (the release source set adds nothing): the bench screen, Compare and the prompt detail (TEN-187),
 * a decode run's result and the comparison of two (TEN-190). [onDiscover] and [onImport] open the Models screen on
 * its Discover tab or its import, as the chat's model picker does.
 */
fun NavGraphBuilder.benchGraph(navController: NavHostController, onDiscover: () -> Unit, onImport: () -> Unit) {
    composable(TensaiDestination.Bench.route) {
        BenchScreen(
            onBack = { navController.popBackStack() },
            onCompare = { navController.navigate(BenchRoutes.compare(it)) { launchSingleTop = true } },
            onOpenDecodeRun = { navController.navigate(BenchRoutes.decodeRun(it)) { launchSingleTop = true } },
            onCompareDecode = { navController.navigate(BenchRoutes.decodeCompare(it)) { launchSingleTop = true } },
            // returnToChat: the load pops back to where it was opened from, here the bench.
            onLoadModel = { navController.navigate(TensaiDestination.Loading.route(it, returnToChat = true)) { launchSingleTop = true } },
            onManage = { navController.navigate(TensaiDestination.Models.route) { launchSingleTop = true } },
            onDiscover = onDiscover,
            onImport = onImport,
        )
    }
    composable(BenchRoutes.DecodeRun) { entry ->
        DecodeRunScreen(runId = entry.arguments?.getString("run").orEmpty(), onBack = { navController.popBackStack() })
    }
    composable(BenchRoutes.DecodeCompare) { entry ->
        DecodeCompareScreen(
            runIds = entry.arguments?.getString("runs").orEmpty().split(',').filter { it.isNotBlank() },
            onBack = { navController.popBackStack() },
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
