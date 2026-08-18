package com.aistra.hail.ui.logs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
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
                    Text(
                        text = stringResource(R.string.msg_logs_count, entries.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row {
                        val logsLabel = stringResource(R.string.title_logs)
                        TextButton(
                            onClick = {
                                HUI.copyText(entries.joinToString("\n") { formatLogEntry(it) })
                                HUI.showToast(R.string.msg_text_copied, logsLabel)
                            }, enabled = entries.isNotEmpty()
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
                if (entries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.msg_logs_empty),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(entries) { entry -> LogRow(entry) }
                    }
                }
            }
        }
    }

    @Composable
    private fun LogRow(entry: ApiLogEntry) = Column(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = dimensionResource(R.dimen.padding_medium),
            vertical = dimensionResource(R.dimen.padding_small)
        )
    ) {
        Text(text = entry.action.substringAfterLast('.'), style = MaterialTheme.typography.bodyLarge)
        Text(text = formatLogEntryDetail(entry), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(modifier = Modifier.padding(top = dimensionResource(R.dimen.padding_small)))
    }

    private fun formatLogEntryDetail(entry: ApiLogEntry) = buildString {
        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestamp)))
        entry.packageName?.let { append(" · $it") }
    }

    private fun formatLogEntry(entry: ApiLogEntry) =
        "${entry.action.substringAfterLast('.')}  ${formatLogEntryDetail(entry)}"
}
