package com.luxysiv.puretube.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pha cấp cao của một phiên phát.
 *
 * Luồng bình thường: `IDLE -> RESOLVING -> READY -> (RESOLVING khi đổi bài)
 * -> CLOSED`. Lỗi resolve/phát đi vào `FAILED`; retry quay lại `RESOLVING`.
 * [canTransitionTo] là bảng chuyển trạng thái tường minh, nhờ đó mọi điểm đổi
 * pha trong [PlaybackCoordinator] đều đi qua cùng một guard thay vì gán enum
 * rải rác.
 */
enum class SessionPhase {
    IDLE,
    RESOLVING,
    READY,
    FAILED,
    CLOSED;

    fun canTransitionTo(next: SessionPhase): Boolean = when (this) {
        IDLE -> next == RESOLVING || next == FAILED || next == CLOSED
        RESOLVING -> next == RESOLVING || next == READY || next == FAILED ||
            next == IDLE || next == CLOSED
        READY -> next == READY || next == RESOLVING || next == FAILED ||
            next == IDLE || next == CLOSED
        FAILED -> next == FAILED || next == RESOLVING || next == IDLE ||
            next == CLOSED
        CLOSED -> next == CLOSED || next == IDLE || next == RESOLVING
    }
}

/**
 * State machine nhỏ cho vòng đời phiên phát.
 *
 * Chỉ [transitionTo] được phép đổi pha; chuyển đổi không hợp lệ bị bỏ qua và
 * trả `false` (không ném lỗi) để một sự kiện player đến trễ không làm sập
 * coordinator.
 */
class PlaybackSessionMachine(initial: SessionPhase = SessionPhase.IDLE) {

    private val _phase = MutableStateFlow(initial)
    val phase: StateFlow<SessionPhase> = _phase.asStateFlow()

    val current: SessionPhase get() = _phase.value

    fun transitionTo(next: SessionPhase): Boolean {
        val current = _phase.value
        if (!current.canTransitionTo(next)) return false
        _phase.value = next
        return true
    }
}
