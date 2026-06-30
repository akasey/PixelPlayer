package com.theveloper.pixelplay.presentation.navidrome.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.database.NavidromeCacheEntryEntity
import com.theveloper.pixelplay.data.database.NavidromePlaylistEntity
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.navidrome.tunnel.TunnelState
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.utils.formatTimeAgo
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NavidromeDashboardScreen(
    viewModel: NavidromeDashboardViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val syncMessage by viewModel.syncMessage.collectAsStateWithLifecycle()
    val selectedPlaylistSongs by viewModel.selectedPlaylistSongs.collectAsStateWithLifecycle()
    val downloadingIds by viewModel.downloadingIds.collectAsStateWithLifecycle()
    val maxCacheSizeMb by viewModel.maxCacheSizeMb.collectAsStateWithLifecycle()
    val cacheUsage by viewModel.cacheUsage.collectAsStateWithLifecycle()
    val autoDownloadThreshold by viewModel.autoDownloadThreshold.collectAsStateWithLifecycle()
    val autoDownloadWifiOnly by viewModel.autoDownloadWifiOnly.collectAsStateWithLifecycle()
    val autoDownloadMaxSizeMb by viewModel.autoDownloadMaxSizeMb.collectAsStateWithLifecycle()

    val cardShape = AbsoluteSmoothCornerShape(
        cornerRadiusTR = 20.dp, cornerRadiusTL = 20.dp,
        cornerRadiusBR = 20.dp, cornerRadiusBL = 20.dp,
        smoothnessAsPercentTR = 60, smoothnessAsPercentTL = 60,
        smoothnessAsPercentBR = 60, smoothnessAsPercentBL = 60
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.subsonic_dashboard_title),
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {}
            )
        }
    ) { paddingValues ->
        DashboardContent(
            playlists = playlists,
            isSyncing = isSyncing,
            syncProgress = syncProgress,
            syncMessage = syncMessage,
            username = viewModel.username,
            lastSyncTime = viewModel.lastSyncTime,
            selectedPlaylistSongs = selectedPlaylistSongs,
            downloadingIds = downloadingIds,
            maxCacheSizeMb = maxCacheSizeMb,
            cacheUsage = cacheUsage,
            autoDownloadThreshold = autoDownloadThreshold,
            autoDownloadWifiOnly = autoDownloadWifiOnly,
            autoDownloadMaxSizeMb = autoDownloadMaxSizeMb,
            onSetMaxCacheSizeMb = { viewModel.setMaxCacheSizeMb(it) },
            onSetAutoDownloadThreshold = { viewModel.setAutoDownloadThreshold(it) },
            onSetAutoDownloadWifiOnly = { viewModel.setAutoDownloadWifiOnly(it) },
            onSetAutoDownloadMaxSizeMb = { viewModel.setAutoDownloadMaxSizeMb(it) },
            onClearStreamingCache = { viewModel.clearStreamingCache() },
            onSyncAll = { viewModel.syncAllPlaylistsAndSongs() },
            onSyncPlaylist = { viewModel.syncPlaylistSongs(it) },
            onDeletePlaylist = { viewModel.deletePlaylist(it) },
            onLoadPlaylistSongs = { viewModel.loadPlaylistSongs(it) },
            onDownloadSong = { viewModel.downloadSong(it) },
            isCached = { viewModel.isCached(it) },
            onLogout = {
                viewModel.logout()
                onBack()
            },
            cardShape = cardShape,
            paddingValues = paddingValues
        )
    }
}

@Composable
private fun DashboardContent(
    playlists: List<NavidromePlaylistEntity>,
    isSyncing: Boolean,
    syncProgress: Float?,
    syncMessage: String?,
    username: String?,
    lastSyncTime: Long,
    selectedPlaylistSongs: List<Song>,
    downloadingIds: Set<String>,
    maxCacheSizeMb: Int,
    cacheUsage: com.theveloper.pixelplay.data.navidrome.CacheUsage,
    autoDownloadThreshold: Int,
    autoDownloadWifiOnly: Boolean,
    autoDownloadMaxSizeMb: Int,
    onSetMaxCacheSizeMb: (Int) -> Unit,
    onSetAutoDownloadThreshold: (Int) -> Unit,
    onSetAutoDownloadWifiOnly: (Boolean) -> Unit,
    onSetAutoDownloadMaxSizeMb: (Int) -> Unit,
    onClearStreamingCache: () -> Unit,
    onSyncAll: () -> Unit,
    onSyncPlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onLoadPlaylistSongs: (String) -> Unit,
    onDownloadSong: (String) -> Unit,
    isCached: (String) -> Boolean,
    onLogout: () -> Unit,
    cardShape: AbsoluteSmoothCornerShape,
    paddingValues: PaddingValues
) {
    var expandedPlaylistId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
    ) {
        // Sync status banner
        AnimatedVisibility(
            visible = syncMessage != null,
            enter = slideInVertically(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + fadeIn(),
            exit = fadeOut()
        ) {
            syncMessage?.let { message ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = cardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = if (message.contains("failed"))
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSyncing && syncProgress == null) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = GoogleSansRounded,
                                modifier = Modifier.weight(1f)
                            )
                            if (isSyncing && syncProgress != null) {
                                Text(
                                    text = "${(syncProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = GoogleSansRounded,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (isSyncing && syncProgress != null) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { syncProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                            )
                        }
                    }
                }
            }
        }

        // User info header
        username?.let { name ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = cardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = androidx.compose.ui.graphics.vector.ImageVector.vectorResource(id = R.drawable.ic_navidrome),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = Color.Unspecified
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = GoogleSansRounded,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.cloud_dashboard_playlists_synced_count, playlists.size),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Last synced: ${formatTimeAgo(lastSyncTime)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = GoogleSansRounded,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        SubsonicMenuCard(isSyncing = isSyncing, onSyncAll = onSyncAll, onLogout = onLogout, cardShape = cardShape)

        NavidromeTunnelCard(cardShape = cardShape)

        NavidromeCacheCard(
            maxCacheSizeMb = maxCacheSizeMb,
            cacheUsage = cacheUsage,
            autoDownloadThreshold = autoDownloadThreshold,
            autoDownloadWifiOnly = autoDownloadWifiOnly,
            autoDownloadMaxSizeMb = autoDownloadMaxSizeMb,
            onSetMaxCacheSizeMb = onSetMaxCacheSizeMb,
            onSetAutoDownloadThreshold = onSetAutoDownloadThreshold,
            onSetAutoDownloadWifiOnly = onSetAutoDownloadWifiOnly,
            onSetAutoDownloadMaxSizeMb = onSetAutoDownloadMaxSizeMb,
            onClearStreamingCache = onClearStreamingCache,
            cardShape = cardShape,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Downloaded songs now live in their own top-level Library → Downloads screen.
        PlaylistsTab(
            playlists = playlists,
            isSyncing = isSyncing,
            selectedPlaylistSongs = selectedPlaylistSongs,
            expandedPlaylistId = expandedPlaylistId,
            downloadingIds = downloadingIds,
            onPlaylistClick = { playlistId ->
                expandedPlaylistId = if (expandedPlaylistId == playlistId) null else playlistId
                if (expandedPlaylistId != null) onLoadPlaylistSongs(playlistId)
            },
            onSyncPlaylist = onSyncPlaylist,
            onDeletePlaylist = onDeletePlaylist,
            onDownloadSong = onDownloadSong,
            isCached = isCached,
            onSyncAll = onSyncAll,
            cardShape = cardShape
        )
    }
}

@Composable
private fun PlaylistsTab(
    playlists: List<NavidromePlaylistEntity>,
    isSyncing: Boolean,
    selectedPlaylistSongs: List<Song>,
    expandedPlaylistId: String?,
    downloadingIds: Set<String>,
    onPlaylistClick: (String) -> Unit,
    onSyncPlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onDownloadSong: (String) -> Unit,
    isCached: (String) -> Boolean,
    onSyncAll: () -> Unit,
    cardShape: AbsoluteSmoothCornerShape
) {
    if (playlists.isEmpty() && !isSyncing) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.CloudQueue,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.cloud_dashboard_playlists_empty_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cloud_dashboard_playlists_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    } else {
        // Regular Column (not LazyColumn) so the whole dashboard scrolls as one surface;
        // a dashboard only ever shows a handful of playlists.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (playlists.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.cloud_dashboard_title_playlists),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = GoogleSansRounded,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onSyncAll) {
                        Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.cloud_dashboard_action_sync), fontFamily = GoogleSansRounded)
                    }
                }
            }
            playlists.forEach { playlist ->
                PlaylistCard(
                    playlist = playlist,
                    onSyncClick = { onSyncPlaylist(playlist.id) },
                    onDeleteClick = { onDeletePlaylist(playlist.id) },
                    onClick = { onPlaylistClick(playlist.id) },
                    cardShape = cardShape,
                    isSyncing = isSyncing,
                    isExpanded = expandedPlaylistId == playlist.id
                )
                if (expandedPlaylistId == playlist.id && selectedPlaylistSongs.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    selectedPlaylistSongs.forEach { song ->
                        val navidromeId = song.contentUriString.removePrefix("navidrome://")
                        SongCard(
                            song = song,
                            isDownloading = downloadingIds.contains(navidromeId),
                            isCached = isCached(navidromeId),
                            onDownloadClick = { onDownloadSong(navidromeId) },
                            cardShape = cardShape
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SubsonicMenuCard(
    isSyncing: Boolean,
    onSyncAll: () -> Unit,
    onLogout: () -> Unit,
    cardShape: AbsoluteSmoothCornerShape
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.cloud_dashboard_quick_actions),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.subsonic_dashboard_quick_actions_subtitle),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = onSyncAll,
                    enabled = !isSyncing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.cloud_sync_status_syncing), fontFamily = GoogleSansRounded)
                    } else {
                        Icon(Icons.Rounded.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.cloud_dashboard_action_sync_library), fontFamily = GoogleSansRounded)
                    }
                }
                FilledTonalButton(
                    onClick = onLogout,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.cloud_dashboard_action_disconnect), fontFamily = GoogleSansRounded)
                }
            }
        }
    }
}

@Composable
private fun NavidromeCacheCard(
    maxCacheSizeMb: Int,
    cacheUsage: com.theveloper.pixelplay.data.navidrome.CacheUsage,
    autoDownloadThreshold: Int,
    autoDownloadWifiOnly: Boolean,
    autoDownloadMaxSizeMb: Int,
    onSetMaxCacheSizeMb: (Int) -> Unit,
    onSetAutoDownloadThreshold: (Int) -> Unit,
    onSetAutoDownloadWifiOnly: (Boolean) -> Unit,
    onSetAutoDownloadMaxSizeMb: (Int) -> Unit,
    onClearStreamingCache: () -> Unit,
    cardShape: AbsoluteSmoothCornerShape,
) {
    // Local slider state so dragging is smooth; the pref is committed on release.
    var sliderValue by remember(maxCacheSizeMb) { mutableStateOf(maxCacheSizeMb.toFloat()) }
    var thresholdValue by remember(autoDownloadThreshold) { mutableStateOf(autoDownloadThreshold.toFloat()) }
    var budgetValue by remember(autoDownloadMaxSizeMb) { mutableStateOf(autoDownloadMaxSizeMb.toFloat()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.navidrome_cache_card_title),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(
                    R.string.navidrome_cache_usage_downloads_split,
                    formatBytes(cacheUsage.downloadBytes),
                    formatBytes(cacheUsage.manualDownloadBytes),
                    formatBytes(cacheUsage.autoDownloadBytes)
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.navidrome_cache_usage_streaming, formatBytes(cacheUsage.streamingBytes)),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.navidrome_cache_max_size_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded
                )
                Text(
                    text = stringResource(R.string.navidrome_cache_max_size_value, sliderValue.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSetMaxCacheSizeMb(sliderValue.toInt()) },
                valueRange = 100f..5000f,
                steps = 48, // ~100 MB increments
            )
            Text(
                text = stringResource(R.string.navidrome_cache_size_hint),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.navidrome_auto_download_section),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = GoogleSansRounded,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Auto-download threshold (0 = off).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.navidrome_auto_download_threshold_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded
                )
                Text(
                    text = if (thresholdValue.toInt() <= 0)
                        stringResource(R.string.navidrome_auto_download_threshold_off)
                    else
                        stringResource(R.string.navidrome_auto_download_threshold_value, thresholdValue.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = thresholdValue,
                onValueChange = { thresholdValue = it },
                onValueChangeFinished = { onSetAutoDownloadThreshold(thresholdValue.toInt()) },
                valueRange = 0f..20f,
                steps = 19,
            )
            Text(
                text = stringResource(R.string.navidrome_auto_download_threshold_hint),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            // Auto-download Wi-Fi-only toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.navidrome_auto_download_wifi_only_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoDownloadWifiOnly,
                    onCheckedChange = onSetAutoDownloadWifiOnly
                )
            }

            // Auto-download budget (0 = unlimited).
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.navidrome_auto_download_budget_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded
                )
                Text(
                    text = if (budgetValue.toInt() <= 0)
                        stringResource(R.string.navidrome_auto_download_budget_unlimited)
                    else
                        stringResource(R.string.navidrome_auto_download_budget_value, budgetValue.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = budgetValue,
                onValueChange = { budgetValue = it },
                onValueChangeFinished = { onSetAutoDownloadMaxSizeMb(budgetValue.toInt()) },
                // 0 = unlimited, then 500 MB .. 10 GB in ~500 MB steps.
                valueRange = 0f..10000f,
                steps = 19,
            )
            Text(
                text = stringResource(R.string.navidrome_auto_download_budget_hint),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onClearStreamingCache,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.navidrome_cache_clear_action), fontFamily = GoogleSansRounded)
            }
        }
    }
}

@Composable
private fun NavidromeTunnelCard(
    cardShape: AbsoluteSmoothCornerShape,
    viewModel: NavidromeTunnelViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val endpoint by viewModel.endpoint.collectAsStateWithLifecycle()
    val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()

    val pickConf = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                viewModel.importConfig("") // surfaces an error
            } else {
                viewModel.importConfig(text)
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Private tunnel (WireGuard)",
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Route Navidrome traffic through an app-only WireGuard tunnel to reach a " +
                    "private server. No system VPN is used.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = GoogleSansRounded,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!viewModel.isSupported) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "This build does not include the WireGuard engine. Rebuild with " +
                        "-Ppixelplay.enableWireguard=true after running tools/wireguard/build-aar.sh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = GoogleSansRounded
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable tunnel",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = GoogleSansRounded
                    )
                    Text(
                        text = endpoint?.let { "Endpoint: $it" } ?: "No config imported",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = GoogleSansRounded,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { viewModel.setEnabled(it) },
                    enabled = endpoint != null && viewModel.isSupported
                )
            }

            // Live connection state.
            val stateLabel = when (val s = tunnelState) {
                is TunnelState.Down -> "Disconnected"
                is TunnelState.Connecting -> "Connecting…"
                is TunnelState.Up -> "Connected (SOCKS :${s.socksPort})"
                is TunnelState.Error -> "Error: ${s.message}"
            }
            val stateColor = when (tunnelState) {
                is TunnelState.Up -> MaterialTheme.colorScheme.primary
                is TunnelState.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.bodySmall,
                color = stateColor,
                fontFamily = GoogleSansRounded
            )

            if (tunnelState is TunnelState.Up) {
                stats?.let { TunnelStatsBlock(it) }
            }

            importError?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = GoogleSansRounded
                )
            }
            when (val r = testResult) {
                is TunnelTestResult.Running -> TunnelTestLine("Testing…", MaterialTheme.colorScheme.onSurfaceVariant)
                is TunnelTestResult.Success -> TunnelTestLine("Test succeeded", MaterialTheme.colorScheme.primary)
                is TunnelTestResult.Failure -> TunnelTestLine("Test failed: ${r.message}", MaterialTheme.colorScheme.error)
                TunnelTestResult.Idle -> {}
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilledTonalButton(
                    onClick = { pickConf.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Upload .conf", fontFamily = GoogleSansRounded)
                }
                FilledTonalButton(
                    onClick = { viewModel.testTunnel() },
                    enabled = endpoint != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Test", fontFamily = GoogleSansRounded)
                }
            }
            if (endpoint != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { viewModel.clearConfig() }) {
                    Text("Remove config", color = MaterialTheme.colorScheme.error, fontFamily = GoogleSansRounded)
                }
            }
        }
    }
}

@Composable
private fun TunnelTestLine(text: String, color: Color) {
    Spacer(Modifier.height(4.dp))
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, fontFamily = GoogleSansRounded)
}

@Composable
private fun TunnelStatsBlock(stats: TunnelStatsUi) {
    Spacer(Modifier.height(10.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(12.dp)
    ) {
        TunnelStatRow("Last handshake", handshakeAgo(stats.lastHandshakeEpochSec))
        Spacer(Modifier.height(6.dp))
        TunnelStatRow(
            "Download",
            "${formatRate(stats.downBytesPerSec)}  ·  ${formatBytes(stats.rxBytes)} total"
        )
        Spacer(Modifier.height(6.dp))
        TunnelStatRow(
            "Upload",
            "${formatRate(stats.upBytesPerSec)}  ·  ${formatBytes(stats.txBytes)} total"
        )
    }
}

@Composable
private fun TunnelStatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = GoogleSansRounded
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = GoogleSansRounded,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun handshakeAgo(epochSec: Long): String {
    if (epochSec <= 0L) return "never"
    val secs = (System.currentTimeMillis() / 1000L - epochSec).coerceAtLeast(0L)
    return when {
        secs < 5L -> "just now"
        secs < 60L -> "${secs}s ago"
        secs < 3600L -> "${secs / 60L}m ${secs % 60L}s ago"
        else -> "${secs / 3600L}h ${(secs % 3600L) / 60L}m ago"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.size - 1) {
        value /= 1024.0
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}

private fun formatRate(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

@Composable
private fun SongCard(
    song: Song,
    isDownloading: Boolean,
    isCached: Boolean,
    onDownloadClick: () -> Unit,
    cardShape: AbsoluteSmoothCornerShape
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (song.albumArtUriString != null) {
                    SmartImage(
                        model = song.albumArtUriString,
                        contentDescription = song.title,
                        contentScale = ContentScale.Crop,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            when {
                isCached -> Icon(
                    Icons.Rounded.CloudDone,
                    contentDescription = "Downloaded",
                    modifier = Modifier
                        .size(40.dp)
                        .padding(8.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                isDownloading -> CircularProgressIndicator(
                    modifier = Modifier.size(40.dp).padding(8.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                else -> IconButton(onClick = onDownloadClick) {
                    Icon(
                        Icons.Rounded.CloudDownload,
                        contentDescription = "Download",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: NavidromePlaylistEntity,
    onSyncClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onClick: () -> Unit,
    cardShape: AbsoluteSmoothCornerShape,
    isSyncing: Boolean,
    isExpanded: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = cardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (playlist.coverArtId != null) {
                    SmartImage(
                        model = "navidrome_cover://${playlist.coverArtId}",
                        contentDescription = playlist.name,
                        contentScale = ContentScale.Crop,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = GoogleSansRounded,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.cloud_dashboard_song_count, playlist.songCount),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = GoogleSansRounded,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalIconButton(
                onClick = onSyncClick,
                enabled = !isSyncing,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    Icons.Rounded.Sync,
                    contentDescription = stringResource(R.string.cloud_cd_sync),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            FilledTonalIconButton(
                onClick = onDeleteClick,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = stringResource(R.string.common_remove),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
