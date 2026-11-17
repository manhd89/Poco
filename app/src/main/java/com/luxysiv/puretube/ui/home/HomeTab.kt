package com.luxysiv.puretube.ui.home

/**
 * Hai tab cấp cao của trang chủ.
 *
 * - [TRENDING] là trang chủ mặc định khi chưa có lịch sử xem: feed thịnh hành
 *   kèm dải chip danh mục.
 * - [FOR_YOU] là trang chủ mặc định khi đã có lịch sử xem: Xem tiếp, Danh sách
 *   kết hợp và Đề xuất cho bạn.
 */
enum class HomeTab(val label: String) {
    TRENDING("Thịnh hành"),
    FOR_YOU("Dành cho bạn")
}
