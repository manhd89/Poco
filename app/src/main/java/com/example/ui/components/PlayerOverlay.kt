package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ChannelInfo
import com.example.data.model.ResourceState
import com.example.data.model.VideoItem
import com.example.ui.viewmodel.PlayerUiState

@Composable
fun PlayerOverlay(
    playerState: PlayerUiState,
    savedVideoIds: Set<String>,
    subscribedChannelIds: Set<String>,
    bottomBarHeightPadding: androidx.compose.ui.unit.Dp,
    onExpand: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onPlayVideo: (VideoItem) -> Unit,
    onToggleSave: (VideoItem) -> Unit,
    onToggleSubscribe: (ChannelInfo) -> Unit,
    onOpenChannel: (ChannelInfo) -> Unit,
    onProgressUpdate: (Float, Float, Boolean) -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onSetPlaying: (Boolean) -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onEmbedError: () -> Unit,
) {
    val activeVideo = playerState.activeVideo ?: return
    val context = LocalContext.current
    val controller = rememberYouTubePlayerController()

    BackHandler(enabled = playerState.isExpanded || playerState.isFullscreen) {
        if (playerState.isFullscreen) {
            controller.exitFullscreen()
        } else {
            onMinimize()
        }
    }

    val openExternalYouTube: () -> Unit = {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/watch?v=${activeVideo.id}"),
        )
        runCatching { context.startActivity(intent) }
    }

    val shareVideo: () -> Unit = {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, activeVideo.title)
            putExtra(
                Intent.EXTRA_TEXT,
                "${activeVideo.title}\nhttps://www.youtube.com/watch?v=${activeVideo.id}",
            )
        }
        runCatching {
            context.startActivity(Intent.createChooser(shareIntent, activeVideo.title))
        }
    }

    val sharedWebPlayer = remember {
        androidx.compose.runtime.movableContentOf { videoId: String, speed: Float ->
            YouTubeWebPlayer(
                videoId = videoId,
                playbackSpeed = speed,
                controller = controller,
                onProgressUpdate = onProgressUpdate,
                onFullscreenChanged = onFullscreenChanged,
                onEmbedError = onEmbedError,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    if (playerState.isExpanded) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                // Video Stage
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black),
                ) {
                    sharedWebPlayer(activeVideo.id, playerState.playbackSpeed)

                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .minimumInteractiveComponentSize()
                            .testTag("minimize_player_button"),
                    ) {
                        IconButton(onClick = onMinimize) {
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.minimize_player),
                                tint = Color.White,
                            )
                        }
                    }
                }

                // Content below player
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    ExpandedPlayerDetails(
                        playerState = playerState,
                        video = activeVideo,
                        isSaved = savedVideoIds.contains(activeVideo.id),
                        isSubscribed = subscribedChannelIds.contains(activeVideo.channelId),
                        onTogglePlayPause = {
                            if (playerState.isPlaying) {
                                controller.pause()
                                onSetPlaying(false)
                            } else {
                                controller.play()
                                onSetPlaying(true)
                            }
                        },
                        onSeekBy = { controller.seekBy(it) },
                        onSetSpeed = onSetPlaybackSpeed,
                        onToggleSave = onToggleSave,
                        onShare = shareVideo,
                        onOpenExternal = openExternalYouTube,
                        onToggleSubscribe = {
                            onToggleSubscribe(
                                ChannelInfo(
                                    id = activeVideo.channelId,
                                    name = activeVideo.channelName,
                                    avatarUrl = activeVideo.channelAvatarUrl,
                                ),
                            )
                        },
                        onChannelClick = {
                            if (activeVideo.channelId.isNotBlank()) {
                                onMinimize()
                                onOpenChannel(
                                    ChannelInfo(
                                        id = activeVideo.channelId,
                                        name = activeVideo.channelName,
                                        avatarUrl = activeVideo.channelAvatarUrl,
                                    ),
                                )
                            }
                        },
                        onSelectRelated = onPlayVideo,
                        savedVideoIds = savedVideoIds,
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 720.dp),
                    )
                }
            }
        }
    } else {
        // Docked Mini-Player bar above Bottom Navigation
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomBarHeightPadding)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .clickable(onClick = onExpand)
                    .testTag("mini_player_bar"),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(104.dp)
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Black),
                        ) {
                            sharedWebPlayer(activeVideo.id, playerState.playbackSpeed)
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeVideo.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = activeVideo.channelName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        IconButton(
                            onClick = {
                                if (playerState.isPlaying) {
                                    controller.pause()
                                    onSetPlaying(false)
                                } else {
                                    controller.play()
                                    onSetPlaying(true)
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("mini_play_pause_button"),
                        ) {
                            Icon(
                                imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.play_pause),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("mini_close_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close_player),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    val progress = if (playerState.durationSeconds > 0f) {
                        (playerState.currentSeconds / playerState.durationSeconds).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedPlayerDetails(
    playerState: PlayerUiState,
    video: VideoItem,
    isSaved: Boolean,
    isSubscribed: Boolean,
    savedVideoIds: Set<String>,
    onTogglePlayPause: () -> Unit,
    onSeekBy: (Int) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggleSave: (VideoItem) -> Unit,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onToggleSubscribe: () -> Unit,
    onChannelClick: () -> Unit,
    onSelectRelated: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var descriptionExpanded by remember(video.id) { mutableStateOf(false) }
    var showSpeedPicker by remember { mutableStateOf(false) }

    val detail = (playerState.detailState as? ResourceState.Success)?.data
    val fullDescription = detail?.description?.ifBlank { video.description } ?: video.description
    val subscriberCount = detail?.subscriberCountText.orEmpty()
    val likeCount = detail?.likeCountText.orEmpty()
    val related = detail?.relatedVideos.orEmpty()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (playerState.embedError) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.embed_restricted_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onOpenExternal) {
                            Text(stringResource(R.string.open_external))
                        }
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(6.dp))
                val meta = listOfNotNull(
                    video.viewCountText.takeIf { it.isNotBlank() },
                    video.publishedText.takeIf { it.isNotBlank() },
                    likeCount.takeIf { it.isNotBlank() }?.let { "$it lượt thích" },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quick playback controls bar
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onSeekBy(-10) },
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Replay10,
                                    contentDescription = "Tua lùi 10 giây",
                                )
                            }
                            IconButton(
                                onClick = onTogglePlayPause,
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Icon(
                                    imageVector = if (playerState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(R.string.play_pause),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(
                                onClick = { onSeekBy(10) },
                                modifier = Modifier.minimumInteractiveComponentSize(),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Forward10,
                                    contentDescription = "Tua tới 10 giây",
                                )
                            }
                        }

                        if (playerState.durationSeconds > 0f) {
                            Text(
                                text = "${formatSeconds(playerState.currentSeconds)} / ${formatSeconds(playerState.durationSeconds)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        TextButton(onClick = { showSpeedPicker = !showSpeedPicker }) {
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("${playerState.playbackSpeed}x")
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showSpeedPicker,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    LazyRow(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)) { rate ->
                            FilterChip(
                                selected = playerState.playbackSpeed == rate,
                                onClick = {
                                    onSetSpeed(rate)
                                    showSpeedPicker = false
                                },
                                label = { Text("${rate}x") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action pills
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        FilledTonalButton(
                            onClick = { onToggleSave(video) },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = if (isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(if (isSaved) R.string.saved_video else R.string.save_video),
                            )
                        }
                    }
                    item {
                        FilledTonalButton(
                            onClick = onShare,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.share_video))
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = onOpenExternal,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.open_external))
                        }
                    }
                }
            }
        }

        // Channel card
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = video.channelId.isNotBlank(), onClick = onChannelClick)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelAvatar(
                    name = video.channelName,
                    avatarUrl = video.channelAvatarUrl,
                    sizeDp = 44,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.channelName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subscriberCount.isNotBlank()) {
                        Text(
                            text = subscriberCount,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (video.channelId.isNotBlank()) {
                    Button(
                        onClick = onToggleSubscribe,
                        colors = if (isSubscribed) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            )
                        },
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Text(
                            text = stringResource(if (isSubscribed) R.string.subscribed else R.string.subscribe),
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // Expandable description
        if (fullDescription.isNotBlank()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clickable { descriptionExpanded = !descriptionExpanded },
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.description_label),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(
                                    if (descriptionExpanded) R.string.show_less else R.string.show_more,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = fullDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // Related videos
        item {
            Text(
                text = stringResource(R.string.related_videos),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )
        }

        if (playerState.detailState is ResourceState.Loading && related.isEmpty()) {
            items(4) {
                ShimmerVideoCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        } else {
            items(related, key = { it.id }) { item ->
                VideoCompactRow(
                    video = item,
                    isSaved = savedVideoIds.contains(item.id),
                    onClick = { onSelectRelated(item) },
                    onToggleSave = { onToggleSave(item) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
                )
            }
        }
    }
}

private fun formatSeconds(sec: Float): String {
    val total = sec.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%d:%02d", m, s)
    }
}
