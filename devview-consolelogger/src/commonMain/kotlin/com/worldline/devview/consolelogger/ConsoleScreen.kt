package com.worldline.devview.consolelogger

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.toMutableStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.worldline.devview.consolelogger.model.ConsoleLog
import com.worldline.devview.consolelogger.model.LogLevel
import com.worldline.devview.consolelogger.preview.ConsoleLogListPreviewParameterProvider
import com.worldline.devview.consolelogger.theme.LocalLogColorScheme
import com.worldline.devview.consolelogger.theme.LogColorScheme
import com.worldline.devview.consolelogger.theme.rememberLogColorScheme

/**
 * Main UI component for displaying captured console logs.
 *
 * Renders a scrollable, auto-following list of console log lines colored by [LogLevel],
 * with a bottom bar providing per-level filter chips and a text filter over tag/message.
 * Scrolling up disables auto-follow; a floating action button re-enables it and jumps back
 * to the latest entry.
 *
 * ## Usage
 * ```kotlin
 * CompositionLocalProvider(LocalConsoleLogs provides ConsoleLogger.logs) {
 *     ConsoleScreen(modifier = Modifier.fillMaxSize())
 * }
 * ```
 *
 * @param modifier Modifier to be applied to the root [Scaffold].
 * @param bottomPadding Additional bottom padding applied to the bottom bar content, used to
 *        avoid overlap with system UI elements when this screen is nested inside another
 *        [Scaffold].
 *
 * @see ConsoleLogger
 * @see LocalConsoleLogs
 * @see ConsoleLog
 */
@Composable
public fun ConsoleScreen(modifier: Modifier = Modifier, bottomPadding: Dp = 0.dp) {
    val consoleLogs = LocalConsoleLogs.current
    val logColorScheme = rememberLogColorScheme()

    val selectedFilters = remember {
        LogLevel.entries.map { it to false }.toMutableStateMap()
    }

    var filterQuery by remember { mutableStateOf(value = "") }

    val filteredConsoleLogs by remember {
        derivedStateOf(policy = structuralEqualityPolicy()) {
            consoleLogs.filter { log ->
                val matchesLevel =
                    selectedFilters.values.all { !it } || selectedFilters[log.level] == true
                val matchesQuery = filterQuery.isBlank() ||
                    log.tag.contains(other = filterQuery, ignoreCase = true) ||
                    log.message.contains(other = filterQuery, ignoreCase = true)
                matchesLevel && matchesQuery
            }
        }
    }

    var enableAutoScroll by remember { mutableStateOf(value = true) }
    val lazyColumnState = rememberLazyListState()

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f && enableAutoScroll) {
                    enableAutoScroll = false
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(key1 = filteredConsoleLogs.size, key2 = enableAutoScroll) {
        if (filteredConsoleLogs.isNotEmpty() && enableAutoScroll) {
            lazyColumnState.animateScrollToItem(index = filteredConsoleLogs.size - 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            AnimatedVisibility(visible = !enableAutoScroll) {
                FloatingActionButton(
                    modifier = Modifier.testTag(tag = "scroll_to_bottom_button"),
                    onClick = { enableAutoScroll = true }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = "Resume auto-scroll"
                    )
                }
            }
        },
        bottomBar = {
            Surface {
                Column {
                    HorizontalDivider()
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .animateContentSize(),
                        horizontalArrangement = Arrangement.spacedBy(space = 8.dp)
                    ) {
                        LogLevel.entries.forEach { level ->
                            val selected = selectedFilters[level] == true
                            val levelColors = logColorScheme[level]
                            FilterChip(
                                modifier = Modifier.testTag(
                                    tag = "console_level_chip_${level.name}"
                                ),
                                selected = selected,
                                onClick = { selectedFilters[level] = !selected },
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = levelColors.label
                                ),
                                colors = FilterChipDefaults.filterChipColors(
                                    labelColor = levelColors.unselectedContent,
                                    selectedLabelColor = levelColors.selectedContent,
                                    selectedLeadingIconColor = levelColors.selectedContent,
                                    selectedContainerColor = levelColors.selectedContainer
                                ),
                                leadingIcon = {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Done,
                                            contentDescription = null
                                        )
                                    }
                                },
                                label = { Text(text = level.name) }
                            )
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .padding(bottom = bottomPadding)
                                .testTag(tag = "console_filter_field"),
                            value = filterQuery,
                            onValueChange = { filterQuery = it },
                            placeholder = { Text(text = "Filter by tag or message...") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null
                                )
                            },
                            trailingIcon = {
                                AnimatedVisibility(visible = filterQuery.isNotEmpty()) {
                                    IconButton(
                                        modifier = Modifier.testTag(tag = "clear_filter_button"),
                                        onClick = { filterQuery = "" }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Clear filter"
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.medium
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        if (filteredConsoleLogs.isEmpty()) {
            Text(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues = paddingValues)
                    .padding(all = 16.dp)
                    .testTag(tag = "console_empty_state"),
                text = if (consoleLogs.isEmpty()) {
                    "Console logs will appear here"
                } else {
                    "No logs match your filter"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues = paddingValues)
                    .imePadding()
                    .nestedScroll(connection = nestedScrollConnection),
                state = lazyColumnState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(space = 4.dp)
            ) {
                itemsIndexed(items = filteredConsoleLogs) { index, log ->
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(tag = "console_log_item_$index"),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        text = buildAnnotatedString {
                            withStyle(
                                style = SpanStyle(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                append(text = "${log.formattedTimestamp} ")
                            }
                            withStyle(style = SpanStyle(color = logColorScheme[log.level].label)) {
                                if (log.tag.isNotEmpty()) {
                                    append(text = "${log.tag}: ")
                                }
                                append(text = log.message)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun ConsoleScreenPreview(
    @PreviewParameter(ConsoleLogListPreviewParameterProvider::class) consoleLogs: List<ConsoleLog>
) {
    MaterialTheme {
        CompositionLocalProvider(
            LocalConsoleLogs provides consoleLogs,
            LocalLogColorScheme provides LogColorScheme.Light
        ) {
            ConsoleScreen(modifier = Modifier.fillMaxSize())
        }
    }
}
