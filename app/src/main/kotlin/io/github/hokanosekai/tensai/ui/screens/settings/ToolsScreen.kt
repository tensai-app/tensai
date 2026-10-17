package io.github.hokanosekai.tensai.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.agent.DefaultTierGrants
import io.github.hokanosekai.tensai.agent.Tool
import io.github.hokanosekai.tensai.data.SearchProviderChoice
import io.github.hokanosekai.tensai.ui.components.TensaiButton
import io.github.hokanosekai.tensai.ui.components.TensaiButtonSize
import io.github.hokanosekai.tensai.ui.components.TensaiButtonVariant
import io.github.hokanosekai.tensai.ui.components.rememberReducedMotion
import io.github.hokanosekai.tensai.ui.screens.chat.SwitchVisual
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.tensaiColors
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import io.github.hokanosekai.tensai.ui.tools.ToolUiCatalog
import io.github.hokanosekai.tensai.ui.tools.resolve

private val ToolGroup.icon: ImageVector
    get() = when (this) {
        ToolGroup.OnPhone -> Icons.Rounded.Smartphone
        ToolGroup.PhoneActions -> Icons.Rounded.TouchApp
        ToolGroup.Internet -> Icons.Rounded.Language
    }

private val ToolGroup.titleRes: Int
    get() = when (this) {
        ToolGroup.OnPhone -> R.string.tools_tier_local
        ToolGroup.PhoneActions -> R.string.tools_tier_phone
        ToolGroup.Internet -> R.string.tools_tier_internet
    }

private val SearchProviderChoice.nameRes: Int
    get() = when (this) {
        SearchProviderChoice.DuckDuckGo -> R.string.tools_provider_ddg
        SearchProviderChoice.Brave -> R.string.tools_provider_brave
        SearchProviderChoice.Tavily -> R.string.tools_provider_tavily
    }

private val SearchProviderChoice.subtitleRes: Int
    get() = when (this) {
        SearchProviderChoice.DuckDuckGo -> R.string.tools_provider_ddg_sub
        SearchProviderChoice.Brave -> R.string.tools_provider_brave_sub
        SearchProviderChoice.Tavily -> R.string.tools_provider_tavily_sub
    }

private val SearchProviderChoice.placeholderRes: Int?
    get() = when (this) {
        SearchProviderChoice.DuckDuckGo -> null
        SearchProviderChoice.Brave -> R.string.tools_key_placeholder_brave
        SearchProviderChoice.Tavily -> R.string.tools_key_placeholder_tavily
    }

/**
 * Settings > Tools (TEN-174): one card per tier, with the switch that grants it and a tap that opens the tier.
 * The Internet card carries its warning, and the footer says what is never automatic.
 */
@Composable
fun ToolsScreen(
    onOpenGroup: (ToolGroup) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ToolsViewModel = remember { ToolsViewModel() },
) {
    val granted by viewModel.grants.collectAsState(initial = DefaultTierGrants.granted())
    val provider by viewModel.searchProvider.collectAsState(initial = SearchProviderChoice.DuckDuckGo)
    val registered = remember(viewModel) { viewModel.registeredTools() }
    val reducedMotion = rememberReducedMotion()

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
    ) {
        for (group in ToolGroup.entries) {
            val on = group.isOn(granted)
            val count = group.toolsOf(registered).size
            SectionCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpenGroup(group) }
                        .padding(start = 16.dp, top = 13.dp, bottom = 13.dp),
                ) {
                    Icon(group.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(group.titleRes),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = groupSupporting(group, on, count, provider),
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Box(modifier = Modifier.width(1.dp).height(28.dp).background(MaterialTheme.tensaiTints.outline))
                    val title = stringResource(group.titleRes)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .toggleable(value = on, role = Role.Switch, onValueChange = { viewModel.setGroup(group, it) })
                            .semantics { contentDescription = title }
                            .heightIn(min = 48.dp)
                            .padding(start = 4.dp, end = 16.dp),
                    ) {
                        SwitchVisual(on, reducedMotion)
                    }
                }
                if (group == ToolGroup.Internet) {
                    InternetWarning()
                }
            }
        }
        Text(
            text = stringResource(R.string.tools_footer),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** "7 tools", "8 actions · the target app confirms", and for Internet "Off · 4 tools" or "On · DuckDuckGo". */
@Composable
private fun groupSupporting(group: ToolGroup, on: Boolean, count: Int, provider: SearchProviderChoice): String = when (group) {
    ToolGroup.OnPhone -> pluralStringResource(R.plurals.tools_count_tools, count, count)
    ToolGroup.PhoneActions -> pluralStringResource(R.plurals.tools_tier_phone_supporting, count, count)
    ToolGroup.Internet -> when {
        on -> stringResource(R.string.tools_tier_internet_on, stringResource(provider.nameRes))
        count > 0 -> pluralStringResource(R.plurals.tools_tier_internet_off, count, count)
        else -> stringResource(R.string.tools_tier_off)
    }
}

/** Under the Internet switch: what leaves the phone. Warning tint, same in the card and the tier screen. */
@Composable
private fun InternetWarning() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.tensaiTints.warning12)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.tensaiColors.warning, modifier = Modifier.size(20.dp))
        Text(
            text = stringResource(R.string.tools_internet_warning),
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * One tier of Settings > Tools: its switch, its tools and, for Internet, the search provider.
 *
 * The Internet card lists the registered tools (`web_search`, `web_fetch`, `weather`, `wikipedia`) and carries the
 * search provider choice, its key (stored encrypted) and the Test button, which sends one real query through the
 * chosen provider alone (TEN-181).
 */
@Composable
fun ToolGroupScreen(
    group: ToolGroup,
    modifier: Modifier = Modifier,
    viewModel: ToolsViewModel = remember { ToolsViewModel() },
) {
    val granted by viewModel.grants.collectAsState(initial = DefaultTierGrants.granted())
    val on = group.isOn(granted)
    val tools = remember(viewModel, group) { group.toolsOf(viewModel.registeredTools()) }
    val reducedMotion = rememberReducedMotion()

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
    ) {
        SectionCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = on, role = Role.Switch, onValueChange = { viewModel.setGroup(group, it) })
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(
                        when (group) {
                            ToolGroup.OnPhone -> R.string.tools_use_local
                            ToolGroup.PhoneActions -> R.string.tools_use_phone
                            ToolGroup.Internet -> R.string.tools_use_internet
                        },
                    ),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                SwitchVisual(on, reducedMotion)
            }
            if (group == ToolGroup.Internet) InternetWarning()
        }

        if (group == ToolGroup.Internet) {
            Column(modifier = Modifier.alpha(if (on) 1f else 0.38f)) {
                SectionLabel(stringResource(R.string.tools_provider_eyebrow), Modifier.padding(top = 14.dp))
                SearchProviderSection(viewModel, enabled = on)
            }
        }

        val eyebrow = when (group) {
            ToolGroup.PhoneActions -> pluralStringResource(R.plurals.tools_count_actions, tools.size, tools.size)
            else -> pluralStringResource(R.plurals.tools_count_tools, tools.size, tools.size)
        }
        SectionLabel(eyebrow, Modifier.padding(top = 14.dp))
        Column(modifier = Modifier.alpha(if (on) 1f else 0.5f)) {
            SectionCard {
                if (tools.isEmpty()) {
                    Text(
                        text = stringResource(
                            when (group) {
                                ToolGroup.OnPhone -> R.string.tools_empty_local
                                ToolGroup.PhoneActions -> R.string.tools_empty_phone
                                ToolGroup.Internet -> R.string.tools_empty_internet
                            },
                        ),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
                tools.forEachIndexed { index, tool ->
                    if (index > 0) RowDivider()
                    ToolRow(tool)
                }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: Tool) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = ToolUiCatalog.icon(tool.name),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = tool.name, fontFamily = TensaiMono, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            ToolUiCatalog.description(tool.name)?.let {
                Text(
                    text = it.resolve(),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** The provider radio card, then the key card: the field of a provider that needs one, and the Test button. */
@Composable
private fun SearchProviderSection(viewModel: ToolsViewModel, enabled: Boolean) {
    val selected by viewModel.searchProvider.collectAsState(initial = SearchProviderChoice.DuckDuckGo)
    SectionCard {
        SearchProviderChoice.entries.forEachIndexed { index, provider ->
            if (index > 0) RowDivider()
            val isSelected = provider == selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton) { viewModel.setSearchProvider(provider) }
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(provider.nameRes),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(provider.subtitleRes),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
    Column(modifier = Modifier.padding(top = 12.dp)) {
        SectionCard {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (selected.needsKey) {
                    KeyField(viewModel, selected, enabled)
                }
                KeyStatusRow(viewModel, selected, enabled)
            }
        }
    }
}

/** The label and the masked field of [provider]'s API key: committed on Done or when the field loses focus. */
@Composable
private fun KeyField(viewModel: ToolsViewModel, provider: SearchProviderChoice, enabled: Boolean) {
    var value by remember(provider) { mutableStateOf("") }
    var shown by remember(provider) { mutableStateOf(false) }
    var focused by remember(provider) { mutableStateOf(false) }
    var invalid by remember(provider) { mutableStateOf(false) }
    val mask by remember(provider) { viewModel.keyMask(provider) }.collectAsState(initial = null)

    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val label = stringResource(R.string.tools_key_label, stringResource(provider.nameRes))

    fun commit() {
        val typed = value
        if (typed.isBlank()) return
        viewModel.saveKey(provider, typed) { saved ->
            if (saved) value = ""
            invalid = !saved
        }
    }

    Text(text = label, fontSize = 12.sp, color = secondary)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(44.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .border(1.dp, if (focused) primary else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(start = 14.dp, end = 2.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = {
                value = it
                invalid = false
            },
            singleLine = true,
            enabled = enabled,
            textStyle = TextStyle(fontFamily = TensaiMono, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(primary),
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label }
                .onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = mask ?: provider.placeholderRes?.let { stringResource(it) }.orEmpty(),
                            fontFamily = TensaiMono,
                            fontSize = 14.sp,
                            color = secondary,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        IconButton(onClick = { shown = !shown }, enabled = enabled) {
            Icon(
                imageVector = if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription = stringResource(if (shown) R.string.tools_key_hide else R.string.tools_key_show),
                tint = secondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (invalid) {
        Text(
            text = stringResource(R.string.tools_key_invalid),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** The line under the field: where the key lives (or the saved mask), the last test result, and the Test button. */
@Composable
private fun KeyStatusRow(viewModel: ToolsViewModel, provider: SearchProviderChoice, enabled: Boolean) {
    val mask by remember(provider) { viewModel.keyMask(provider) }.collectAsState(initial = null)
    val test by viewModel.testState.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = if (provider.needsKey) 8.dp else 0.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (provider.needsKey) {
                Text(
                    text = if (mask != null) stringResource(R.string.tools_key_saved, mask.orEmpty()) else stringResource(R.string.tools_key_stored),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val result = when (val state = test) {
                SearchTestState.Idle -> null
                is SearchTestState.Running -> stringResource(R.string.tools_test_running) to false
                is SearchTestState.Answered -> stringResource(R.string.tools_test_ok, state.provider, state.millis) to false
                is SearchTestState.Failed -> state.message.resolve() to true
            }
            if (result != null) {
                Text(
                    text = result.first,
                    fontSize = 12.5.sp,
                    color = if (result.second) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (mask != null) {
            TensaiButton(
                text = stringResource(R.string.tools_key_remove),
                onClick = { viewModel.removeKey(provider) },
                variant = TensaiButtonVariant.Text,
                size = TensaiButtonSize.Sm,
                enabled = enabled,
                contentColor = MaterialTheme.colorScheme.error,
            )
        }
        TensaiButton(
            text = stringResource(R.string.tools_test),
            onClick = { viewModel.testSearch(provider) },
            variant = TensaiButtonVariant.Text,
            size = TensaiButtonSize.Sm,
            enabled = enabled && test !is SearchTestState.Running,
        )
    }
}
