package com.luxysiv.puretube.ui.library

import android.app.Application
import com.luxysiv.puretube.ui.base.PureTubeViewModel

/**
 * ViewModel riêng cho trang "Bạn".
 *
 * Dữ liệu thư viện (lịch sử, yêu thích, xem sau) và các thao tác xoá nằm ở
 * lớp dùng chung; lớp này là điểm mở rộng cho trạng thái riêng của trang.
 */
class LibraryViewModel(application: Application) : PureTubeViewModel(application)
