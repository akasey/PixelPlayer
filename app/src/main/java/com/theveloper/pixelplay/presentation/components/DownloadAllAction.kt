package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R

/**
 * Bulk download / remove control for an album's or artist's Navidrome songs, designed for a top-bar
 * actions slot. [navidromeIds] is the full set of downloadable (Navidrome) song ids in the
 * collection; the control renders nothing when there are none (e.g. an all-local album).
 *
 * State:
 *  - any id in flight → progress spinner;
 *  - all ids downloaded → "downloaded" affordance that, on tap, confirms then removes them all;
 *  - otherwise → download affordance that downloads every not-yet-downloaded id.
 */
@Composable
fun DownloadAllAction(
    navidromeIds: List<String>,
    downloadedIds: Set<String>,
    downloadingIds: Set<String>,
    onDownloadAll: (List<String>) -> Unit,
    onRemoveAll: (List<String>) -> Unit,
) {
    if (navidromeIds.isEmpty()) return

    val anyDownloading = remember(navidromeIds, downloadingIds) {
        navidromeIds.any { it in downloadingIds }
    }
    val allDownloaded = remember(navidromeIds, downloadedIds) {
        navidromeIds.all { it in downloadedIds }
    }
    var showRemoveConfirm by remember { mutableStateOf(false) }

    when {
        anyDownloading -> {
            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
        }

        allDownloaded -> {
            FilledTonalIconButton(onClick = { showRemoveConfirm = true }) {
                Icon(
                    imageVector = Icons.Rounded.DownloadDone,
                    contentDescription = stringResource(R.string.library_downloads_remove)
                )
            }
        }

        else -> {
            IconButton(onClick = { onDownloadAll(navidromeIds) }) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.library_download_all)
                )
            }
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text(stringResource(R.string.library_downloads_remove_confirm_title)) },
            text = { Text(stringResource(R.string.library_downloads_remove_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveConfirm = false
                    onRemoveAll(navidromeIds)
                }) {
                    Text(
                        text = stringResource(R.string.common_remove),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
