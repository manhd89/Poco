package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ChannelInfo
import com.example.data.model.ResourceState
import com.example.data.model.VideoItem
import com.example.ui.components.ChannelAvatar
import com.example.ui.components.ShimmerVideoCard
import com.example.ui.components.VideoFeedCard
import com.example.ui.viewmodel.ChannelUiState

@Composable
fun ChannelScreen(
    channelState: ChannelUiState,
    subscribedChannelIds: Set<String>,
    savedVideoIds: Set<String>,
    extraBottomPadding: Dp,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleSubscribe: (ChannelInfo) -> Unit,
    onVideoClick: (VideoItem) -> Unit,
    onToggleSave: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val channel = channelState.channel ?: return
    val isSubscribed = subscribedChannelIds.contains(channel.id)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("channel_back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.back_action),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 330.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 20.dp + extraBottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primaryContainer,
                                            MaterialTheme.colorScheme.secondaryContainer,
                                        ),
                                    ),
                                ),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ChannelAvatar(
                                name = channel.name,
                                avatarUrl = channel.avatarUrl,
                                sizeDp = 58,
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = channel.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (channel.subscriberText.isNotBlank()) {
                                    Text(
                                        text = channel.subscriberText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Button(
                                onClick = { onToggleSubscribe(channel) },
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
                                modifier = Modifier.testTag("channel_subscribe_button"),
                            ) {
                                Text(
                                    text = stringResource(
                                        if (isSubscribed) R.string.subscribed else R.string.subscribe,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            when (val res = channelState.videosState) {
                is ResourceState.Loading -> {
                    items(4) { ShimmerVideoCard() }
                }

                is ResourceState.Error -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ErrorStateCard(
                            message = stringResource(R.string.error_load_videos),
                            onRetry = onRetry,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                        )
                    }
                }

                is ResourceState.Success -> {
                    items(res.data, key = { it.id }) { video ->
                        VideoFeedCard(
                            video = video,
                            isSaved = savedVideoIds.contains(video.id),
                            onClick = { onVideoClick(video) },
                            onChannelClick = {},
                            onToggleSave = { onToggleSave(video) },
                        )
                    }
                }
            }
        }
    }
}
