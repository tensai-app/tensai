package io.github.tensaiapp.ui.screens.bench

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/** The bench screens are debug-only (see the debug source set): a release graph has none of them. */
@Suppress("UNUSED_PARAMETER", "UnusedReceiverParameter")
fun NavGraphBuilder.benchGraph(navController: NavHostController) = Unit

/** No DEBUG section in a release's Settings. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun BenchSettingsSection(onOpen: () -> Unit) = Unit
