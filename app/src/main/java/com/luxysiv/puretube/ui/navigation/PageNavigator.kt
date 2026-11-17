package com.luxysiv.puretube.ui.navigation

import android.content.Context
import android.content.Intent
import com.luxysiv.puretube.ui.home.HomeActivity
import com.luxysiv.puretube.ui.library.LibraryActivity
import com.luxysiv.puretube.ui.search.SearchActivity
import com.luxysiv.puretube.ui.settings.SettingsActivity
import com.luxysiv.puretube.ui.subscriptions.SubscriptionsActivity

/**
 * Điều hướng giữa các trang.
 *
 * Mỗi trang là một Activity độc lập. [FLAG_ACTIVITY_REORDER_TO_FRONT] đưa
 * Activity đã có lên trước thay vì tạo bản sao, nhờ vậy trạng thái cuộn và dữ
 * liệu của trang được giữ nguyên khi chuyển qua lại giữa các tab.
 */
object PageNavigator {

    fun open(context: Context, tab: AppTab) {
        val target = when (tab) {
            AppTab.HOME -> HomeActivity::class.java
            AppTab.SUBSCRIPTIONS -> SubscriptionsActivity::class.java
            AppTab.LIBRARY -> LibraryActivity::class.java
            AppTab.SEARCH -> SearchActivity::class.java
            AppTab.SETTINGS -> SettingsActivity::class.java
        }
        context.startActivity(
            Intent(context, target).addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }
}
