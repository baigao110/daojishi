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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「11月22日零点倒计时」专属的「每 X 小时 / 每 X 分钟提醒一次」。
 *
 * v1.0.0.63（versionCode 199）首版：一条纯 AlarmManager 链式闹钟 + 普通通知。
 * 实测「开启后收不到通知」，三个根因：
 *  ① 没有任何前台服务 / 自愈入口。划掉卡片或进程被 ROM 清掉时，本包全部闹钟被撤销，
 *     而 sync() 只在进主界面 / 开机 / 保存编辑页时被调用 —— 闹钟一丢就永远断链；
 *  ② 通知是 IMPORTANCE_HIGH / PRIORITY_HIGH / CATEGORY_REMINDER 的普通通知，
 *     后台发出时容易被国产 ROM 收进「无声通知」甚至直接吞掉；「吃药提醒守护中」之所以
 *     稳，是因为它是前台服务通知（IMPORTANCE_LOW / PRIORITY_MIN / CATEGORY_SERVICE）；
 *  ③ 开启后要等整整一个间隔（默认 12 小时）才有第一条，闹钟中途丢了还再顺延一整个间隔。
 *
 * v200 修复（本次）：
 *  · 通知**级别与属性完全对齐吃药守护通知**：独立但同配置的渠道（IMPORTANCE_LOW、
 *    静默、无角标、锁屏公开）、PRIORITY_MIN / CATEGORY_SERVICE / VISIBILITY_PUBLIC、
 *    可划掉、带 deleteIntent 与「关闭提醒」按钮；并由 PickupGuardService 以前台服务
 *    身份持有，进程优先级与「吃药提醒守护中」同级；
 *  · 接入吃药那条已经验证过的整套自愈链路（守护服务、亮屏/解锁/充电/改时间、
 *    JobScheduler 巡检、划卡片复活闹钟、开机），每次都把周期闹钟重新挂回来；
 *  · 开启 / 改间隔后**立刻先送一条**，闹钟丢失后再次被叫醒时补发一条，不再让用户
 *    干等一整个间隔；showIntent 改成 Activity（与吃药闹钟同款），避免个别 ROM 拒绝。
 *
 * 三种情况一律停发并撤掉闹钟：
 *  - 用户在编辑页把「每 X 提醒一次」关掉；
 *  - 11月22日零点倒计时被删掉 / 改成别的类型；
 *  - 到 2026-11-22 00:00 这条倒计时归零（reapCompletedMissions 会自动删除它）。
 * 划掉通知 / 点「关闭提醒」：与吃药守护通知一致，只静音当天、撤掉前台服务与闹钟，
 * 次日有需求时自动恢复。到点的「该吃药了」、吃药守护、倒计时归零等通知各用各的
 * id / 渠道，互不干扰。
 */
object PickupReminder {

    const val ACTION_TICK = "com.baigao.countdown.action.PICKUP_TICK"
    const val ACTION_REVIVE = "com.baigao.countdown.action.PICKUP_REVIVE"
    const val ACTION_DISMISS = "com.baigao.countdown.action.PICKUP_DISMISS"

    /** 沿用 v199 的偏好文件名：覆盖安装后用户已选的开关 / 间隔不丢。 */
    private const val PREFS = "pickup_reminder_v199"
    private const val KEY_ON = "enabled"
    private const val KEY_MINUTES = "interval_minutes"
    private const val KEY_NEXT = "next_at"
    private const val KEY_MUTED_DATE = "guard_muted_date"
    private const val REQ_ALARM = 4201
    private const val REQ_REVIVE = 4202
    private const val REQ_OPEN = 4203
    private const val REQ_DISMISS = 4204
    private const val REQ_SHOW = 4205

    /** 前台服务通知与每趟提醒共用这个 id：前台服务持有它，到点时原地刷新，通知栏不堆积。 */
    const val NOTIF_ID = 20401
    /**
     * 独立渠道，但重要性 / 静默 / 角标 / 锁屏可见性等属性与
     * [MedicineGuardService] 的「吃药提醒后台守护」渠道逐项一致 —— 同级别、互不牵连：
     * 用户单独关掉吃药守护渠道不会影响拿药提醒，反之亦然。
     */
    const val CHANNEL_ID = "pickup_guard_v200"
    /** v199 那条 IMPORTANCE_HIGH 的旧渠道，升级后删掉，避免残留两套级别。 */
    private const val OLD_CHANNEL_ID = "pickup_reminder_v199"

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

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

    private fun intervalLabel(ctx: Context): String {
        val m = intervalMinutes(ctx).coerceAtLeast(1)
        return INTERVALS.firstOrNull { it.first == m }?.second ?: "每 $m 分钟"
    }

    /**
     * 编辑页保存：写下开关 / 间隔，并按新配置重排闹钟。
     * 「开启 / 改了间隔」当场先送一条 —— 用户不必再等一整个间隔才知道它生效了。
     * 编辑页每次保存都会调这里（哪怕改的是别的倒计时），所以只在配置真的变了才补发，
     * 并且不重置已经排好的下一趟，免得保存别的东西把提醒往后推。
     */
    fun setConfig(ctx: Context, on: Boolean, minutes: Int) {
        val p = prefs(ctx)
        val oldOn = p.getBoolean(KEY_ON, true)
        val oldMinutes = p.getInt(KEY_MINUTES, DEFAULT_MINUTES)
        val changed = oldOn != on || oldMinutes != minutes
        val e = p.edit()
            .putBoolean(KEY_ON, on)
            .putInt(KEY_MINUTES, minutes)
            .remove(KEY_MUTED_DATE)   // 重新配置 = 取消当天的静音
        if (changed) e.remove(KEY_NEXT)
        e.apply()
        if (on && changed && shouldShow(ctx)) {
            try {
                notify(ctx)
            } catch (t: Throwable) {
                android.util.Log.w("PickupReminder", "first notify: ${t.message}")
            }
        }
        sync(ctx, force = changed || !on)
    }

    private fun piFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun alarmPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_ALARM,
        Intent(ctx, PickupReminderReceiver::class.java).setAction(ACTION_TICK),
        piFlags()
    )

    private fun revivePi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_REVIVE,
        Intent(ctx, PickupReminderReceiver::class.java).setAction(ACTION_REVIVE),
        piFlags()
    )

    /** setAlarmClock 的 showIntent 必须是 Activity：v199 传广播 PI，个别 ROM 会拒绝这条闹钟。 */
    private fun showPi(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, REQ_SHOW,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        piFlags()
    )

    private fun dismissPi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_DISMISS,
        Intent(ctx, PickupReminderReceiver::class.java).setAction(ACTION_DISMISS),
        piFlags()
    )

    private fun alarmManager(ctx: Context) =
        ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun cancelAlarm(ctx: Context) {
        try {
            alarmManager(ctx).cancel(alarmPi(ctx))
        } catch (t: Throwable) {
            // 忽略
        }
    }

    private fun schedule(ctx: Context, at: Long) {
        val am = alarmManager(ctx)
        val pi = alarmPi(ctx)
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi(ctx)), pi)
        } catch (t: Throwable) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (t2: Throwable) {
                try {
                    am.set(AlarmManager.RTC_WAKEUP, at, pi)
                } catch (t3: Throwable) {
                    android.util.Log.w("PickupReminder", "schedule: ${t3.message}")
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
        } catch (t: Throwable) {
            false
        }
    }

    /** 再叠加「今天有没有被用户划掉」：与吃药守护通知一致，划掉当天不再贴回来。 */
    fun shouldShow(ctx: Context): Boolean =
        shouldRun(ctx) && prefs(ctx).getString(KEY_MUTED_DATE, "") != todayKey()

    /**
     * 只对齐闹钟链，不碰前台服务 —— 供前台服务自己的 onStartCommand / 动态广播调用，
     * 避免「sync → start → onStartCommand → sync」自激。
     * force=true：无条件重排（改过配置 / 开机 / 归零删除后）。
     * force=false：已有落在合理区间内的未来排期就不动它；排期已过期说明闹钟被系统
     *   清掉或推迟过，立刻补发一条，而不是又把用户晾一整个间隔。
     */
    fun align(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!shouldShow(ctx)) {
            cancelAlarm(ctx)
            prefs(ctx).edit().remove(KEY_NEXT).apply()
            return
        }
        val interval = intervalMinutes(ctx).coerceAtLeast(1) * 60_000L
        val next = prefs(ctx).getLong(KEY_NEXT, 0L)
        if (!force && next > now && next <= now + interval * 2) return
        if (!force && next > 0L && next < now) {
            try {
                notify(ctx)
            } catch (t: Throwable) {
                android.util.Log.w("PickupReminder", "catch up notify: ${t.message}")
            }
        }
        schedule(ctx, now + interval)
    }

    /** 对外总入口：对齐闹钟 + 把前台守护拉到与配置一致的状态。重复调用安全。 */
    fun sync(ctx: Context, force: Boolean = false) {
        try {
            align(ctx, force)
        } catch (t: Throwable) {
            android.util.Log.w("PickupReminder", "align: ${t.message}")
        }
        try {
            if (shouldShow(ctx)) PickupGuardService.start(ctx)
            else PickupGuardService.stopQuiet(ctx)
        } catch (t: Throwable) {
            android.util.Log.w("PickupReminder", "guard: ${t.message}")
        }
    }

    /** 闹钟到点：刷新那条前台级提醒通知，再挂上下一趟；状态不成立时只做收尾。 */
    fun onTick(ctx: Context) {
        if (!shouldShow(ctx)) {
            sync(ctx, force = true)
            return
        }
        try {
            notify(ctx)
        } catch (t: Throwable) {
            android.util.Log.w("PickupReminder", "notify: ${t.message}")
        }
        val interval = intervalMinutes(ctx).coerceAtLeast(1) * 60_000L
        schedule(ctx, System.currentTimeMillis() + interval)
        // 前台服务万一被系统回收，借这次精确闹钟把它拉回来（后台启动被拒时 try 兜住）。
        try {
            PickupGuardService.start(ctx)
        } catch (t: Throwable) {
            android.util.Log.w("PickupReminder", "guard revive: ${t.message}")
        }
    }

    /** 划掉应用卡片 / 服务被销毁：立刻重挂闹钟，并排一个 1.5 秒后的复活闹钟。 */
    fun onGuardRevive(ctx: Context) {
        if (!shouldShow(ctx)) return
        try {
            align(ctx)
        } catch (t: Throwable) {
            // 忽略
        }
        try {
            val at = System.currentTimeMillis() + 1500L
            alarmManager(ctx).setAlarmClock(
                AlarmManager.AlarmClockInfo(at, showPi(ctx)), revivePi(ctx)
            )
        } catch (t: Throwable) {
            try {
                alarmManager(ctx).set(
                    AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1500L, revivePi(ctx)
                )
            } catch (t2: Throwable) {
                // 忽略
            }
        }
    }

    /** 复活闹钟响了：重挂闹钟 + 把前台守护拉回来。 */
    fun onRevive(ctx: Context) {
        try {
            sync(ctx)
        } catch (t: Throwable) {
            android.util.Log.w("PickupReminder", "revive: ${t.message}")
        }
    }

    /** 划掉通知 / 点「关闭提醒」：与吃药守护通知一致，只静音当天。 */
    fun onDismiss(ctx: Context) {
        prefs(ctx).edit()
            .putString(KEY_MUTED_DATE, todayKey())
            .remove(KEY_NEXT)
            .apply()
        cancelAlarm(ctx)
        cancelNotification(ctx)
        try {
            PickupGuardService.stopQuiet(ctx)
        } catch (t: Throwable) {
            // 忽略
        }
    }

    /** 建立与「吃药提醒后台守护」逐项同级的渠道；顺手删掉 v199 那条高重要性旧渠道。 */
    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            if (nm.getNotificationChannel(OLD_CHANNEL_ID) != null) {
                nm.deleteNotificationChannel(OLD_CHANNEL_ID)
            }
        } catch (t: Throwable) {
            // 部分 ROM 不允许删渠道，忽略
        }
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID, "拿药提醒后台守护", NotificationManager.IMPORTANCE_LOW
        )
        ch.description = "与吃药提醒守护同级别：静默、不响铃、不弹横幅，前台服务持有保证送达"
        ch.enableVibration(false)
        ch.setShowBadge(false)
        try {
            ch.setSound(null, null)
        } catch (t: Throwable) {
            // 部分 ROM 不允许置空铃声，忽略即可
        }
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    /**
     * 与 [MedicineGuardService] 的守护通知同级别同属性：
     * 低重要性渠道、PRIORITY_MIN、CATEGORY_SERVICE、VISIBILITY_PUBLIC、可划掉、
     * deleteIntent + 操作按钮。区别只在文案与 id —— 内容固定是那句拿药提醒。
     */
    fun buildNotification(ctx: Context): Notification {
        ensureChannel(ctx)
        val openPi = PendingIntent.getActivity(
            ctx, REQ_OPEN,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            piFlags()
        )
        val stamp = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val big = TEXT + "\n" + intervalLabel(ctx) + " · 最近提醒 " + stamp
        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("拿药提醒守护中")
            .setContentText(TEXT)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setContentIntent(openPi)
            .setDeleteIntent(dismissPi(ctx))
            .addAction(R.drawable.ic_stat, "关闭提醒", dismissPi(ctx))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_MIN)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setWhen(System.currentTimeMillis())
            .build()
    }

    fun notify(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(ctx))
    }

    fun cancelNotification(ctx: Context) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        } catch (t: Throwable) {
            // 忽略
        }
    }
}

/**
 * 静态接收「每 X 提醒一次」的周期闹钟 / 复活闹钟 / 通知被划掉。
 * 三个 action 必须显式分支，绝不能落到 else 里当成普通闹钟处理。
 */
class PickupReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            PickupReminder.ACTION_TICK -> {
                try {
                    PickupReminder.onTick(context)
                } catch (t: Throwable) {
                    android.util.Log.w("PickupReminder", "onTick: ${t.message}")
                }
            }
            PickupReminder.ACTION_REVIVE -> {
                try {
                    PickupReminder.onRevive(context)
                } catch (t: Throwable) {
                    android.util.Log.w("PickupReminder", "onRevive: ${t.message}")
                }
            }
            PickupReminder.ACTION_DISMISS -> {
                try {
                    PickupReminder.onDismiss(context)
                } catch (t: Throwable) {
                    android.util.Log.w("PickupReminder", "onDismiss: ${t.message}")
                }
            }
        }
    }
}
