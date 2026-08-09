package com.apps.game

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.core.util.RxMainQueue

/**
 * 游戏会话的本地有效时长计时器。
 *
 * 使用单调时钟累计，并在熄屏后暂停；宿主通过回调同步暂停或恢复在线会话。
 */
class GameSessionTimer(
    private val appContext: Context,
    private val mainQueue: RxMainQueue,
    private val onScreenPaused: () -> Unit,
    private val onScreenResumed: () -> Unit,
) {
    private var destroyed = false
    private var elapsedStartedAt = -1L
    private var accumulatedDuration = 0L
    private var pausedForScreenOff = false
    private var receiverRegistered = false

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mainQueue.post {
                if (destroyed) return@post
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> pause()
                    Intent.ACTION_SCREEN_ON -> if (!isDeviceLocked()) resume()
                    Intent.ACTION_USER_PRESENT -> resume()
                }
            }
        }
    }

    init {
        registerScreenStateReceiver()
    }

    fun start() {
        accumulatedDuration = 0L
        elapsedStartedAt = SystemClock.elapsedRealtime()
        pausedForScreenOff = false
    }

    fun isPausedForScreenOff(): Boolean = pausedForScreenOff

    fun finish(): Long {
        if (elapsedStartedAt >= 0L) {
            accumulatedDuration += (SystemClock.elapsedRealtime() - elapsedStartedAt).coerceAtLeast(0L)
        }
        val duration = accumulatedDuration.coerceAtLeast(0L)
        elapsedStartedAt = -1L
        accumulatedDuration = 0L
        pausedForScreenOff = false
        return duration
    }

    fun cleanup() {
        destroyed = true
        if (receiverRegistered) {
            appContext.unregisterReceiver(screenStateReceiver)
            receiverRegistered = false
        }
    }

    private fun pause() {
        if (elapsedStartedAt < 0L || pausedForScreenOff) return
        accumulatedDuration += (SystemClock.elapsedRealtime() - elapsedStartedAt).coerceAtLeast(0L)
        elapsedStartedAt = -1L
        pausedForScreenOff = true
        onScreenPaused()
    }

    private fun resume() {
        if (!pausedForScreenOff) return
        elapsedStartedAt = SystemClock.elapsedRealtime()
        pausedForScreenOff = false
        onScreenResumed()
    }

    private fun registerScreenStateReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                appContext,
                screenStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        } catch (_: SecurityException) {
            // 无法订阅屏幕状态时仍使用单调时钟，至少避免系统校时造成的异常放大。
        }
    }

    private fun isDeviceLocked(): Boolean = try {
        (appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
    } catch (_: SecurityException) {
        // 个别受限设备可能拒绝查询锁屏状态；按未锁屏处理，避免屏幕点亮后永久暂停。
        false
    }
}
