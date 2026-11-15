package com.baigao.countdown

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder

/**
 * 「每 X 提醒一次」的前台守护服务。
 *
 * v199 的周期提醒只挂了一条链式闹钟，划掉卡片 / 进程被 ROM 清掉后本包闹钟被整体撤销，
 * 又没有组件把它挂回来 —— 这正是「开启后收不到通知」的根因。这里照搬
 * [MedicineGuardService] 那套已经验证过的保活骨架：前台服务常驻（进程优先级与
 * 「吃药提醒守护中」同级）、START_STICKY、onTaskRemoved / onDestroy 留复活闹钟、
 * 运行时动态注册亮屏 / 解锁 / 充电 / 改时间等高频系统事件随时重挂闹钟。
 *
 * 它持有的那条前台通知就是提醒本身（固定文案见 [PickupReminder.TEXT]），
 * 级别与属性与吃药守护通知逐项一致；每到一个间隔原地刷新一次，通知栏不堆积。
 * 用户划掉它 / 点「关闭提醒」→ 只静音当天并停掉服务，次日自动恢复。
 */
class PickupGuardService : Service() {

    private var rescueReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            PickupReminder.ensureChannel(this)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "channel: ${t.message}")
        }
        registerRescueReceiver()
    }

    /** 亮屏 / 解锁 / 充电 / 改时间：闹钟被系统清掉时，这些事件是还能被叫醒的入口。 */
    private fun registerRescueReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                try {
                    PickupReminder.align(c)
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "rescue: ${t.message}")
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(r, f)
            }
            rescueReceiver = r
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "registerRescue: ${t.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须第一件事就进前台：startForegroundService() 要求 5 秒内 startForeground()。
        try {
            startForeground(PickupReminder.NOTIF_ID, PickupReminder.buildNotification(this))
        } catch (t: Throwable) {
            // 前台通知挂不上就别赖着，退化成纯 AlarmManager 兜底（闹钟仍照常重挂）。
            android.util.Log.w(TAG, "startForeground: ${t.message}")
            try {
                PickupReminder.align(this)
            } catch (t2: Throwable) {
                // 忽略
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP || !PickupReminder.shouldShow(this)) {
            try {
                @Suppress("DEPRECATION")
                stopForeground(true)
            } catch (t: Throwable) {
                // 忽略
            }
            PickupReminder.cancelNotification(this)
            stopSelf()
            return START_NOT_STICKY
        }
        // 只对齐闹钟，不调 sync()：后者会再 start 一次本服务，形成自激。
        try {
            PickupReminder.align(this)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "reschedule: ${t.message}")
        }
        return START_STICKY
    }

    /** 从最近任务划掉卡片：趁进程还在，重挂闹钟 + 排复活闹钟。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            PickupReminder.onGuardRevive(this)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "onTaskRemoved: ${t.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    /** 服务被销毁（系统回收 / 一键清理）：同样留一次重挂机会。 */
    override fun onDestroy() {
        try {
            rescueReceiver?.let { unregisterReceiver(it) }
        } catch (t: Throwable) {
            // 忽略
        }
        rescueReceiver = null
        try {
            if (PickupReminder.shouldShow(this)) PickupReminder.onGuardRevive(this)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "onDestroy: ${t.message}")
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PickupGuard"
        const val ACTION_START = "com.baigao.countdown.action.PICKUP_GUARD_START"
        const val ACTION_STOP = "com.baigao.countdown.action.PICKUP_GUARD_STOP"

        /** 开启提醒 / 打开 App / 开机 / 闹钟到点 / 系统事件自愈都调它，重复调用安全。 */
        fun start(ctx: Context) {
            if (!PickupReminder.shouldShow(ctx)) {
                stopQuiet(ctx)
                return
            }
            val i = Intent(ctx, PickupGuardService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (t: Throwable) {
                // Android 12+ 从普通广播启动前台服务会被拒：AlarmManager 闹钟已由 align() 挂好，
                // 到点由静态接收器直接发通知，退化为纯闹钟兜底，不影响送达。
                try {
                    ctx.startService(i)
                } catch (t2: Throwable) {
                    android.util.Log.w(TAG, "start: ${t2.message}")
                }
            }
        }

        /** 静默收掉提醒通知并停掉服务（不创建服务，不会闪一条新通知）。 */
        fun stopQuiet(ctx: Context) {
            PickupReminder.cancelNotification(ctx)
            try {
                ctx.stopService(Intent(ctx, PickupGuardService::class.java))
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "stopQuiet: ${t.message}")
            }
        }
    }
}
