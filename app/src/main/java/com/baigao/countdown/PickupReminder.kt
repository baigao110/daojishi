package com.baigao.countdown

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 「11月22日零点倒计时」专属的「每 X 小时 / 每 X 分钟提醒一次」。
 *
 * v1.0.0.63（versionCode 199）：这条内置倒计时多了一个可关掉的周期提醒选项。
 * 开关与间隔存在独立偏好里（不污染 Countdown 数据模型），闹钟用 setAlarmClock
 * （与系统闹钟同路、能穿透 Doze、不需要精确闹钟授权）一趟一趟链式续挂：
 * 到点弹一条可划掉的通知，然后按间隔挂上下一趟。
 *
 * 三种情况一律停发并撤掉闹钟：
 *  - 用户在编辑页把「每 X 提醒一次」关掉；
 *  - 11月22日零点倒计时被删掉 / 改成别的类型；
 *  - 到 2026-11-22 00:00 这条倒计时归零（reapCompletedMissions 会自动删除它）。
 * 到点的「该吃药了」、吃药守护、倒计时归零提醒等其它通知都用各自的 id / 渠道，互不干扰。
 */
object PickupReminder {

    const val ACTION_TICK = "com.baigao.countdown.action.PICKUP_TICK"

    private const val PREFS = "pickup_reminder_v199"
    private const val KEY_ON = "enabled"
    private const val KEY_MINUTES = "interval_minutes"
    private const val KEY_NEXT = "next_at"
    private const val REQ_ALARM = 4201
    private const val NOTIF_ID = 20401
    private const val CHANNEL_ID = "pickup_reminder_v199"

    /** 默认开启，默认每 12 小时提醒一次（不会太打扰）；随时可在编辑页改 / 关。 */
    const val DEFAULT_MINUTES = 720

    /** 间隔档位（分钟）→ 下拉框文案：分钟档与小时档都给到。 */
    val INTERVALS: List<Pair<Int, String>> = listOf(
        1 to "每 1 分钟",
        5 to "每 5 分钟",
        10 to "每 10 分钟",
        15 to "每 15 分钟",
        30 to "每 30 分钟",
        60 to "每 1 小时",
        120 to "每 2 小时",
        180 to "每 3 小时",
        240 to "每 4 小时",
        360 to "每 6 小时",
        720 to "每 12 小时",
        1440 to "每 24 小时"
    )

    /** 提醒内容：用户指定的固定文案。 */
    const val TEXT = "别忘了2026年11月22日下午去九江院区拿药"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, true)

    fun intervalMinutes(ctx: Context): Int = prefs(ctx).getInt(KEY_MINUTES, DEFAULT_MINUTES)

    /** 编辑页保存：写下开关 / 间隔，并强制重排闹钟（间隔变了就得按新的来）。 */
    fun setConfig(ctx: Context, on: Boolean, minutes: Int) {
        prefs(ctx).edit()
            .putBoolean(KEY_ON, on)
            .putInt(KEY_MINUTES, minutes)
            .remove(KEY_NEXT)
            .apply()
        sync(ctx, force = true)
    }

    private fun alarmPi(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(
            ctx, REQ_ALARM,
            Intent(ctx, PickupReminderReceiver::class.java).setAction(ACTION_TICK),
            flags
        )
    }

    private fun alarmManager(ctx: Context) =
        ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun cancelAlarm(ctx: Context) {
        try {
            alarmManager(ctx).cancel(alarmPi(ctx))
        } catch (e: Throwable) {
            // 忽略
        }
    }

    private fun schedule(ctx: Context, at: Long) {
        val am = alarmManager(ctx)
        val pi = alarmPi(ctx)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi)
        } catch (e: Throwable) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (e2: Throwable) {
                try {
                    am.set(AlarmManager.RTC_WAKEUP, at, pi)
                } catch (e3: Throwable) {
                    android.util.Log.w("PickupReminder", "schedule: ${e3.message}")
                }
            }
        }
        prefs(ctx).edit().putLong(KEY_NEXT, at).apply()
    }

    /** 该不该继续提醒：开关开着、零点档还在列表里、且还没到 2026-11-22 00:00。 */
    private fun shouldRun(ctx: Context): Boolean {
        if (!isEnabled(ctx)) return false
        val target = BuiltIn.fixedTargetMillis(BuiltIn.NOV22) ?: return false
        if (System.currentTimeMillis() >= target) return false
        return try {
            CountdownStore.load(ctx).any { it.builtIn == BuiltIn.NOV22 }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 把闹钟对齐到当前配置。
     * force=true：无条件重排（改过配置 / 开机 / 归零删除后）。
     * force=false：已经有一条落在合理区间内的未来排期就不动它，免得每次回到前台
     *   都把下一次提醒往后推一整个间隔。
     */
    fun sync(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!shouldRun(ctx)) {
            cancelAlarm(ctx)
            prefs(ctx).edit().remove(KEY_NEXT).apply()
            return
        }
        val interval = intervalMinutes(ctx).coerceAtLeast(1) * 60_000L
        val next = prefs(ctx).getLong(KEY_NEXT, 0L)
        if (!force && next > now && next <= now + interval * 2) return
        schedule(ctx, now + interval)
    }

    /** 闹钟到点：弹一条可划掉的提醒通知，再挂上下一趟；状态不成立时只做收尾。 */
    fun onTick(ctx: Context) {
        if (!shouldRun(ctx)) {
            sync(ctx, force = true)
            return
        }
        try {
            notify(ctx)
        } catch (e: Throwable) {
            android.util.Log.w("PickupReminder", "notify: ${e.message}")
        }
        val interval = intervalMinutes(ctx).coerceAtLeast(1) * 60_000L
        schedule(ctx, System.currentTimeMillis() + interval)
    }

    private fun notify(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            val ch = NotificationChannel(
                CHANNEL_ID, "拿药周期提醒", NotificationManager.IMPORTANCE_HIGH
            )
            ch.description = "11月22日零点倒计时的每 X 提醒一次"
            ch.setShowBadge(true)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val openPi = PendingIntent.getActivity(
            ctx, REQ_ALARM + 1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        // 固定 id：每次提醒顶掉上一条，通知栏不会越积越多；可手动划掉，绝非常驻。
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("拿药提醒")
            .setContentText(TEXT)
            .setStyle(Notification.BigTextStyle().bigText(TEXT))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        nm.notify(NOTIF_ID, n)
    }
}

/** 静态接收「每 X 提醒一次」闹钟：后台到点也能弹通知并自动续挂下一趟。 */
class PickupReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != PickupReminder.ACTION_TICK) return
        try {
            PickupReminder.onTick(context)
        } catch (e: Throwable) {
            android.util.Log.w("PickupReminder", "onReceive: ${e.message}")
        }
    }
}
