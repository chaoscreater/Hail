package com.aistra.hail.ui.logs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import com.aistra.hail.R
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.ui.theme.AppTheme
import com.aistra.hail.utils.ApiLog
import com.aistra.hail.utils.ApiLogEntry
import com.aistra.hail.utils.HUI
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsFragment : MainFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AppTheme { LogsScreen() } }
        }

    @Preview(showBackground = true)
    @Composable
    fun PreviewLogsScreen() = AppTheme { LogsScreen() }

    @Composable
    private fun LogsScreen() {
        var entries by remember { mutableStateOf(ApiLog.getAll()) }
        var searchActive by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        val filteredEntries = remember(entries, query) {
            if (query.isBlank()) entries else entries.filter { matchesQuery(it, query) }
        }
        // Establishes the theme's background + a correctly contrasting LocalContentColor for the
        // Text composables below — without this, they default to black regardless of dark mode.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = dimensionResource(R.dimen.padding_medium),
                        vertical = dimensionResource(R.dimen.padding_small)
                    ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (searchActive) {
                        val focusRequester = remember { FocusRequester() }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f).focusRequester(focusRequester),
                            placeholder = { Text(text = stringResource(R.string.action_search)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                            colors = TextFieldDefaults.colors(
                                unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent
                            )
                        )
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        IconButton(onClick = { searchActive = false; query = "" }) {
                            Icon(imageVector = Icons.Outlined.Close, contentDescription = stringResource(R.string.action_search))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.msg_logs_count, filteredEntries.size),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row {
                        if (!searchActive) {
                            IconButton(onClick = { searchActive = true }, enabled = entries.isNotEmpty()) {
                                Icon(imageVector = Icons.Outlined.Search, contentDescription = stringResource(R.string.action_search))
                            }
                        }
                        val logsLabel = stringResource(R.string.title_logs)
                        TextButton(
                            onClick = {
                                HUI.copyText(filteredEntries.joinToString("\n") { formatLogEntry(it) })
                                HUI.showToast(R.string.msg_text_copied, logsLabel)
                            }, enabled = filteredEntries.isNotEmpty()
                        ) {
                            Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = null)
                            Spacer(modifier = Modifier.width(dimensionResource(R.dimen.padding_extra_small)))
                            Text(text = stringResource(R.string.action_copy_logs))
                        }
                        TextButton(
                            onClick = {
                                lifecycleScope.launch {
                                    ApiLog.clear()
                                    entries = ApiLog.getAll()
                                }
                            }, enabled = entries.isNotEmpty()
                        ) {
                            Icon(imageVector = Icons.Outlined.DeleteSweep, contentDescription = null)
                            Spacer(modifier = Modifier.width(dimensionResource(R.dimen.padding_extra_small)))
                            Text(text = stringResource(R.string.action_clear_logs))
                        }
                    }
                }
                HorizontalDivider()
                if (filteredEntries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(if (entries.isEmpty()) R.string.msg_logs_empty else R.string.msg_logs_no_match),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredEntries) { entry -> LogRow(entry) }
                    }
                }
            }
        }
    }

    private fun matchesQuery(entry: ApiLogEntry, query: String): Boolean =
        entry.action.contains(query, ignoreCase = true) ||
            entry.packageName?.contains(query, ignoreCase = true) == true ||
            entry.detail?.contains(query, ignoreCase = true) == true

    @Composable
    private fun LogRow(entry: ApiLogEntry) = Column(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = dimensionResource(R.dimen.padding_medium),
            vertical = dimensionResource(R.dimen.padding_small)
        )
    ) {
        Text(text = entry.action.substringAfterLast('.'), style = MaterialTheme.typography.bodyLarge)
        Text(text = formatLogEntryDetail(entry), style = MaterialTheme.typography.bodySmall)
        entry.detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = dimensionResource(R.dimen.padding_small)))
    }

    private fun formatLogEntryDetail(entry: ApiLogEntry) = buildString {
        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestamp)))
        entry.packageName?.let { append(" · $it") }
    }

    private fun formatLogEntry(entry: ApiLogEntry) =
        "${entry.action.substringAfterLast('.')}  ${formatLogEntryDetail(entry)}" +
            (entry.detail?.let { "\n  $it" } ?: "")
}
