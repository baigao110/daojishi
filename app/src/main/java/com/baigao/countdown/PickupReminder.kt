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
 * v199：纯 AlarmManager 链式闹钟 + 一条高重要性普通通知 → 断链后无人重挂、后台被 ROM 吞。
 * v200：通知级别/属性对齐「吃药提醒守护中」并接入前台服务 + 整套自愈链路；但把
 *       「到点提醒」和「前台守护通知」挤在同一个 id（20401）上，到点只是原地刷新那条
 *       常驻静默通知，用户完全感觉不到「收到一条提醒」；更糟的是用户划掉这条常驻通知
 *       会走 onDismiss() → 静音当天 + 撤掉整条闹钟链 + 停服务，于是「开启后收不到」。
 *
 * v201（本次）把两件事彻底分开，且两者**渠道与属性仍与吃药守护通知逐项一致**：
 *  · 守护通知（20401）：前台服务持有，低重要性 / 静默 / PRIORITY_MIN / CATEGORY_SERVICE /
 *    VISIBILITY_PUBLIC / 无角标 / 锁屏公开。划掉它只静音「守护条」当天，不再撤闹钟、不停提醒；
 *  · 到点提醒通知（20410~20415 轮换）：每趟独立发一条、发之前收掉上一条（不堆积），
 *    同渠道同属性、带本次时间，可划掉。划掉它只收起这一条，下一趟照常来；
 *    通知上「关闭周期提醒」按钮才真正把开关关掉。
 *  · 开启 / 改间隔 / 从编辑页保存零点档：当场先送一条，不用干等一个间隔。
 *  · 闹钟链只由「开关 + 零点档还在 + 未到 2026-11-22 00:00」决定，与守护条是否被划掉无关。
 *
 * 状态（开关 / 间隔 / 下次提醒时间）由 statusText() 统一提供，主界面列表卡片、悬浮窗、
 * 编辑页三处动态显示，文案只在这一处生成。
 *
 * 三种情况一律停发并撤掉闹钟：用户在编辑页关掉开关、零点档被删/改类型、到 2026-11-22
 * 00:00 归零（reapCompletedMissions 自动删除）。到点的「该吃药了」、吃药守护、倒计时归零
 * 等通知各用各的 id / 渠道，互不干扰。
 */
object PickupReminder {

    const val ACTION_TICK = "com.baigao.countdown.action.PICKUP_TICK"
    const val ACTION_REVIVE = "com.baigao.countdown.action.PICKUP_REVIVE"
    const val ACTION_DISMISS = "com.baigao.countdown.action.PICKUP_DISMISS"
    const val ACTION_REMIND_DISMISSED = "com.baigao.countdown.action.PICKUP_REMIND_DISMISSED"
    const val ACTION_TURN_OFF = "com.baigao.countdown.action.PICKUP_TURN_OFF"

    /** 沿用 v199 的偏好文件名：覆盖安装后用户已选的开关 / 间隔不丢。 */
    private const val PREFS = "pickup_reminder_v199"
    private const val KEY_ON = "enabled"
    private const val KEY_MINUTES = "interval_minutes"
    private const val KEY_NEXT = "next_at"
    private const val KEY_LAST = "last_at"
    private const val KEY_MUTED_DATE = "guard_muted_date"
    private const val KEY_LAST_NOTIF_ID = "last_notif_id"
    private const val KEY_NOTIF_SEQ = "notif_seq"
    private const val REQ_ALARM = 4201
    private const val REQ_REVIVE = 4202
    private const val REQ_OPEN = 4203
    private const val REQ_DISMISS = 4204
    private const val REQ_SHOW = 4205
    private const val REQ_REMIND_DISMISS = 4206
    private const val REQ_TURN_OFF = 4207

    /** 前台守护通知：级别/属性与「吃药提醒守护中」一致，由 PickupGuardService 持有。 */
    const val NOTIF_ID = 20401
    /** 每趟提醒用独立 id 轮换：发新的之前收掉上一条，通知栏只留最新一条、不堆积。 */
    private const val REMIND_ID_BASE = 20410
    private const val REMIND_ID_SPAN = 6

    /**
     * 独立渠道，但重要性 / 静默 / 角标 / 锁屏可见性等属性与
     * [MedicineGuardService] 的「吃药提醒后台守护」渠道逐项一致 —— 同级别、互不牵连。
     * 守护通知与到点提醒共用这一条渠道，保证两者的级别和属性完全相同。
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

    private fun hhmm(t: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

    fun intervalLabel(ctx: Context): String {
        val m = intervalMinutes(ctx).coerceAtLeast(1)
        return INTERVALS.firstOrNull { it.first == m }?.second ?: "每 $m 分钟"
    }

    fun nextAt(ctx: Context): Long = prefs(ctx).getLong(KEY_NEXT, 0L)

    fun lastAt(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST, 0L)

    /** 该不该继续提醒：开关开着、零点档还在列表里、且还没到 2026-11-22 00:00。 */
    fun shouldRun(ctx: Context): Boolean {
        if (!isEnabled(ctx)) return false
        val target = BuiltIn.fixedTargetMillis(BuiltIn.NOV22) ?: return false
        if (System.currentTimeMillis() >= target) return false
        return try {
            CountdownStore.load(ctx).any { it.builtIn == BuiltIn.NOV22 }
        } catch (t: Throwable) {
            false
        }
    }

    private fun guardMutedToday(ctx: Context): Boolean =
        prefs(ctx).getString(KEY_MUTED_DATE, "") == todayKey()

    /** 守护条只受「用户当天划掉过」影响；划掉它不再影响闹钟链与到点提醒。 */
    fun shouldShowGuard(ctx: Context): Boolean = shouldRun(ctx) && !guardMutedToday(ctx)

    /** 列表卡片 / 悬浮窗 / 编辑页共用的状态文案，改格式只改这一处。 */
    fun statusText(ctx: Context, compact: Boolean = false): String {
        if (!shouldRun(ctx)) {
            return if (!isEnabled(ctx)) {
                if (compact) "提醒 已关闭" else "周期提醒：已关闭（在编辑页开启）"
            } else {
                if (compact) "提醒 已结束" else "周期提醒：已结束（倒计时已归零或已删除）"
            }
        }
        val label = intervalLabel(ctx)
        val n = nextAt(ctx)
        val tail = if (n > System.currentTimeMillis()) hhmm(n) else "即将"
        return if (compact) "提醒 ${label.replace(" ", "")} · $tail"
        else "周期提醒：$label · 下次 $tail"
    }

    /** 编辑页的实时状态预览（开关 / 间隔还没保存时按界面上的选择显示）。 */
    fun detailText(ctx: Context, on: Boolean, minutes: Int): String {
        if (!on) return "当前：已关闭 · 不会按间隔提醒"
        val label = INTERVALS.firstOrNull { it.first == minutes }?.second ?: "每 $minutes 分钟"
        val last = lastAt(ctx)
        val tail = if (last > 0L) " · 最近提醒 ${hhmm(last)}" else ""
        return "当前：已开启 · $label$tail\n开启后保存会马上先提醒一次"
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

    private fun openPi(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, REQ_OPEN,
        Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        piFlags()
    )

    private fun actionPi(ctx: Context, req: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, req,
            Intent(ctx, PickupReminderReceiver::class.java).setAction(action),
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

    /**
     * 编辑页保存：写下开关 / 间隔，并按新配置重排闹钟。
     * immediate=true 表示这一页确实摆着零点档的提醒选项 —— 开启时当场先送一条，
     * 用户不必等一整个间隔才知道它生效了；编辑别的倒计时时传 false，不打扰。
     */
    fun setConfig(ctx: Context, on: Boolean, minutes: Int, immediate: Boolean = true) {
        val p = prefs(ctx)
        val oldOn = p.getBoolean(KEY_ON, true)
        val oldMinutes = p.getInt(KEY_MINUTES, DEFAULT_MINUTES)
        val changed = oldOn != on || oldMinutes != minutes
        val e = p.edit()
            .putBoolean(KEY_ON, on)
            .putInt(KEY_MINUTES, minutes)
            .remove(KEY_MUTED_DATE)   // 重新配置 = 取消当天守护条的静音
        if (changed) e.remove(KEY_NEXT)
        e.apply()

        if (!on) {
            turnOff(ctx)
            return
        }
        if ((immediate || (changed && !oldOn)) && shouldRun(ctx)) {
            try {
                notifyReminder(ctx)
            } catch (t: Throwable) {
                android.util.Log.w("PickupReminder", "first notify: ${t.message}")
            }
        }
        sync(ctx, force = changed)
    }

    /**
     * 只对齐闹钟链，不碰前台服务 —— 供前台服务自己的 onStartCommand / 动态广播调用，
     * 避免「sync → start → onStartCommand → sync」自激。
     * force=true：无条件重排（改过配置 / 开机 / 归零删除后）。
     * force=false：已有落在合理区间内的未来排期就不动它；排期已过期说明闹钟被系统
     *   清掉或推迟过，立刻补发一条，而不是又把用户晾一整个间隔。
     */
    fun align(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!shouldRun(ctx)) {
            cancelAlarm(ctx)
            prefs(ctx).edit().remove(KEY_NEXT).apply()
            return
        }
        val interval = intervalMinutes(ctx).coerceAtLeast(1) * 60_000L
        val next = nextAt(ctx)
        if (!force && next > now && next <= now + interval * 2) return
        if (!force && next > 0L && next < now) {
            try {
                notifyReminder(ctx)
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
            if (shouldShowGuard(ctx)) PickupGuardService.start(ctx)
            else PickupGuardService.stopQuiet(ctx)
        } catch (t: Throwable) {
            android.util.Log.w("PickupReminder", "guard: ${t.message}")
        }
    }

    /** 闹钟到点：发一条独立的提醒通知，再挂上下一趟；状态不成立时只做收尾。 */
    fun onTick(ctx: Context) {
        if (!shouldRun(ctx)) {
            sync(ctx, force = true)
            return
        }
        try {
            notifyReminder(ctx)
        } catch (t: Throwable) {
            android.util.Log.w("PickupReminder", "notify: ${t.message}")
        }
        val interval = intervalMinutes(ctx).coerceAtLeast(1) * 60_000L
        schedule(ctx, System.currentTimeMillis() + interval)
        // 守护条没被划掉时借这次精确闹钟把前台服务拉回来（后台启动被拒时 try 兜住）。
        try {
            if (shouldShowGuard(ctx)) PickupGuardService.start(ctx)
        } catch (t: Throwable) {
            android.util.Log.w("PickupReminder", "guard revive: ${t.message}")
        }
    }

    /** 划掉应用卡片 / 服务被销毁：立刻重挂闹钟，并排一个 1.5 秒后的复活闹钟。 */
    fun onGuardRevive(ctx: Context) {
        if (!shouldRun(ctx)) return
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

    /**
     * 划掉守护通知 / 点守护通知上的「关闭提醒」：只静音「守护条」当天，
     * **保留闹钟链与到点提醒**（v200 在这里撤闹钟停服务，正是「开启后收不到」的元凶）。
     */
    fun onDismiss(ctx: Context) {
        prefs(ctx).edit().putString(KEY_MUTED_DATE, todayKey()).apply()
        cancelNotification(ctx)
        try {
            PickupGuardService.stopQuiet(ctx)
        } catch (t: Throwable) {
            // 忽略
        }
        try {
            align(ctx)
        } catch (t: Throwable) {
            // 忽略
        }
    }

    /** 划掉某一趟提醒通知：只收起这一条，下一趟照常来，不静音、不撤闹钟。 */
    fun onRemindDismissed(ctx: Context) {
        cancelReminderNotification(ctx)
    }

    /** 通知上的「关闭周期提醒」：真正关掉开关，撤闹钟、收掉两条通知、停服务。 */
    fun onTurnOff(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ON, false).apply()
        turnOff(ctx)
    }

    private fun turnOff(ctx: Context) {
        cancelAlarm(ctx)
        prefs(ctx).edit().remove(KEY_NEXT).apply()
        cancelNotification(ctx)
        cancelReminderNotification(ctx)
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

    /** 守护通知：与「吃药提醒守护中」同级别同属性，划掉只静音当天。 */
    fun buildNotification(ctx: Context): Notification {
        ensureChannel(ctx)
        val big = TEXT + "\n" + intervalLabel(ctx) + " · " +
            (if (lastAt(ctx) > 0L) "最近提醒 " + hhmm(lastAt(ctx)) else "等待下一趟提醒")
        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("拿药提醒守护中")
            .setContentText(statusText(ctx))
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setContentIntent(openPi(ctx))
            .setDeleteIntent(actionPi(ctx, REQ_DISMISS, ACTION_DISMISS))
            .addAction(R.drawable.ic_stat, "收起守护条", actionPi(ctx, REQ_DISMISS, ACTION_DISMISS))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_MIN)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setWhen(System.currentTimeMillis())
            .build()
    }

    /**
     * 到点提醒通知：与守护条**同一渠道、同级别、同属性**
     * （IMPORTANCE_LOW / PRIORITY_MIN / CATEGORY_SERVICE / VISIBILITY_PUBLIC / 静默 / 无角标），
     * 但用独立 id 每趟新发一条，用户下拉通知栏能明确看到「这一趟提醒到了」。
     */
    fun buildReminderNotification(ctx: Context): Notification {
        ensureChannel(ctx)
        val stamp = hhmm(System.currentTimeMillis())
        val big = TEXT + "\n" + intervalLabel(ctx) + " · 本次 " + stamp
        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("拿药提醒")
            .setContentText(TEXT)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setContentIntent(openPi(ctx))
            .setDeleteIntent(actionPi(ctx, REQ_REMIND_DISMISS, ACTION_REMIND_DISMISSED))
            .addAction(R.drawable.ic_stat, "关闭周期提醒", actionPi(ctx, REQ_TURN_OFF, ACTION_TURN_OFF))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_MIN)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(false)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .build()
    }

    fun notify(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(ctx))
    }

    /** 每趟提醒：先收掉上一条，再以轮换 id 发新的一条，通知栏只留最新一条。 */
    fun notifyReminder(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val p = prefs(ctx)
        val prev = p.getInt(KEY_LAST_NOTIF_ID, -1)
        if (prev > 0) nm.cancel(prev)
        val seq = p.getInt(KEY_NOTIF_SEQ, 0)
        val id = REMIND_ID_BASE + (seq % REMIND_ID_SPAN)
        nm.notify(id, buildReminderNotification(ctx))
        p.edit()
            .putInt(KEY_LAST_NOTIF_ID, id)
            .putInt(KEY_NOTIF_SEQ, seq + 1)
            .putLong(KEY_LAST, System.currentTimeMillis())
            .apply()
    }

    fun cancelNotification(ctx: Context) {
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        } catch (t: Throwable) {
            // 忽略
        }
    }

    fun cancelReminderNotification(ctx: Context) {
        try {
            val id = prefs(ctx).getInt(KEY_LAST_NOTIF_ID, -1)
            if (id > 0) {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(id)
            }
        } catch (t: Throwable) {
            // 忽略
        }
    }
}

/**
 * 静态接收「每 X 提醒一次」的周期闹钟 / 复活闹钟 / 通知被划掉 / 关闭开关。
 * 所有 action 必须显式分支，绝不能落到 else 里当成普通闹钟处理。
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
            PickupReminder.ACTION_REMIND_DISMISSED -> {
                try {
                    PickupReminder.onRemindDismissed(context)
                } catch (t: Throwable) {
                    android.util.Log.w("PickupReminder", "remind dismissed: ${t.message}")
                }
            }
            PickupReminder.ACTION_TURN_OFF -> {
                try {
                    PickupReminder.onTurnOff(context)
                } catch (t: Throwable) {
                    android.util.Log.w("PickupReminder", "turn off: ${t.message}")
                }
            }
        }
    }
}
