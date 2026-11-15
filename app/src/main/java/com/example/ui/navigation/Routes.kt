package com.example.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object ExploreRoute

@Serializable
data object LibraryRoute

@Serializable
data object SearchRoute

@Serializable
data class ChannelRoute(
    val channelId: String,
    val channelName: String,
    val avatarUrl: String = "",
)
