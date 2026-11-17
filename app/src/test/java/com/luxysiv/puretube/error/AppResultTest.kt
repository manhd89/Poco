package com.luxysiv.puretube.error

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Kiểm tra ánh xạ kết quả/lỗi dùng chung của data layer. */
class AppResultTest {

    @Test
    fun `success exposes value and survives map`() {
        val result = AppResult.Success(21)

        assertTrue(result.isSuccess)
        assertEquals(21, result.getOrNull())
        assertNull(result.errorOrNull())
        assertEquals(42, result.map { it * 2 }.getOrNull())
        assertEquals(AppResult.Success(42), result.map { it * 2 })
    }

    @Test
    fun `failure keeps original error through map and flatMap`() {
        val error = AppError.Unavailable()
        val result: AppResult<Int> = AppResult.Failure(error)

        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
        assertSame(error, result.errorOrNull())
        assertEquals(result, result.map { it * 2 })
        assertEquals(result, result.flatMap { AppResult.Success(it * 2) })
    }

    @Test
    fun `getOrElse returns fallback only on failure`() {
        val failure: AppResult<Int> = AppResult.Failure(AppError.Network())

        assertEquals(7, AppResult.Success(7).getOrElse(0))
        assertEquals(0, failure.getOrElse(0))
    }

    @Test
    fun `fold dispatches to the matching branch`() {
        assertEquals(
            "ok:5",
            AppResult.Success(5).fold(onSuccess = { "ok:$it" }, onFailure = { "error" })
        )
        assertEquals(
            "error",
            AppResult.Failure(AppError.Timeout()).fold(
                onSuccess = { "ok:$it" },
                onFailure = { "error" }
            )
        )
    }

    @Test
    fun `exceptions are classified`() {
        assertTrue(AppError.from(UnknownHostException()) is AppError.Network)
        assertTrue(AppError.from(IOException()) is AppError.Network)
        assertTrue(AppError.from(SocketTimeoutException()) is AppError.Timeout)
        assertTrue(AppError.from(IllegalStateException()) is AppError.Unknown)
    }

    @Test
    fun `blocking helper maps exceptions to results`() {
        assertEquals(
            AppResult.Success("done"),
            runAppCatchingBlocking { "done" }
        )

        val failure = runAppCatchingBlocking<Int> { throw UnknownHostException() }
        assertTrue(failure.errorOrNull() is AppError.Network)
    }

    @Test
    fun `suspend helper maps exceptions to results`() = runBlocking {
        assertEquals(
            AppResult.Success(3),
            runAppCatching { 3 }
        )

        val failure = runAppCatching<Int> { throw SocketTimeoutException() }
        assertTrue(failure.errorOrNull() is AppError.Timeout)
    }

    @Test
    fun `blocking helper propagates cancellation`() {
        try {
            runAppCatchingBlocking<Unit> { throw CancellationException("stop") }
            fail("CancellationException phải được ném lại")
        } catch (expected: CancellationException) {
            assertEquals("stop", expected.message)
        }
    }

    @Test
    fun `suspend helper propagates cancellation`() = runBlocking {
        try {
            runAppCatching<Unit> { throw CancellationException("stop") }
            fail("CancellationException phải được ném lại")
        } catch (expected: CancellationException) {
            assertEquals("stop", expected.message)
        }
    }
}
