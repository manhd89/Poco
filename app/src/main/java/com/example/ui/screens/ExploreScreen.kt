package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Search
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
import com.example.ui.viewmodel.ExploreUiState

@Composable
fun ExploreScreen(
    exploreState: ExploreUiState,
    savedVideoIds: Set<String>,
    extraBottomPadding: Dp,
    onSearchClick: () -> Unit,
    onRetry: () -> Unit,
    onVideoClick: (VideoItem) -> Unit,
    onChannelClick: (ChannelInfo) -> Unit,
    onToggleSave: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.LocalFireDepartment,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.nav_explore),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            IconButton(
                onClick = onSearchClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("explore_search_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.search_action),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 330.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 16.dp + extraBottomPadding,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.popular_channels_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(exploreState.curatedChannels, key = { it.id }) { channel ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .width(116.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onChannelClick(channel) }
                                    .testTag("featured_channel_${channel.id}"),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    ChannelAvatar(
                                        name = channel.name,
                                        avatarUrl = channel.avatarUrl,
                                        sizeDp = 52,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = channel.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (channel.category.isNotBlank()) {
                                        Text(
                                            text = channel.category,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.trending_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            when (val trending = exploreState.trendingState) {
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
                                .padding(vertical = 24.dp),
                        )
                    }
                }

                is ResourceState.Success -> {
                    itemsIndexed(trending.data, key = { _, item -> item.id }) { index, video ->
                        VideoFeedCard(
                            video = video,
                            isSaved = savedVideoIds.contains(video.id),
                            rankNumber = index + 1,
                            onClick = { onVideoClick(video) },
                            onChannelClick = {
                                if (video.channelId.isNotBlank()) {
                                    onChannelClick(
                                        ChannelInfo(
                                            id = video.channelId,
                                            name = video.channelName,
                                            avatarUrl = video.channelAvatarUrl,
                                        ),
                                    )
                                }
                            },
                            onToggleSave = { onToggleSave(video) },
                        )
                    }
                }
            }
        }
    }
}
