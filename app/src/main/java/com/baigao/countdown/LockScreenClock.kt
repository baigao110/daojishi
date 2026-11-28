package com.baigao.countdown

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.RemoteViews

/**
 * 锁屏 / 通知栏倒计时。
 *
 * 开启「锁屏通知显示」后，凡是**在列表里「展开」过（isVisible）**的倒计时，
 * 都会在系统通知栏（锁屏界面上）各占一条**常驻通知**，每秒跟着跳秒，
 * 标题、倒计时数字、模式、备注、主题配色一律与悬浮窗 / 主界面里的一致 ——
 * 同一条数据、同一套渲染，只是把"悬浮在别的 App 上面"换成了"躺在锁屏通知栏里"。
 *
 * 实现要点：
 * - 折叠态（锁屏上真正看到的）用 contentTitle = 倒计时标题、contentText = 剩余时间文本，
 *   所以锁屏不用展开也能一眼读到还差多久；展开态用自定义玻璃卡片（与悬浮窗同款配色）。
 * - 通知是常驻（Ongoing）+ 只在首次 alert、之后静默刷新（OnlyAlertOnce），
 *   避免每秒响一下、也不填「显示时间」（ShowWhen）让秒数在锁屏上跳得干净。
 * - 渠道用 IMPORTANCE_LOW（静默但锁屏可见；IMPORTANCE_MIN 在锁屏上是隐藏的），
 *   锁屏可见度设为 VISIBILITY_PUBLIC，并标记闹钟类型。
 * - 只给 isVisible 的倒计时发通知：谁在悬浮窗里显示，锁屏通知里就有谁，两边永远一致。
 * - 被隐藏 / 删除的倒计时，通知会被取消，不会留一条"永远停在 0 秒"的僵尸通知。
 */
object LockScreenClock {

    private const val PREF = "app_settings"
    /** 锁屏通知总开关（默认开启：装好就能在锁屏上看到展开过的倒计时）。 */
    private const val KEY_ON = "lockscreen_notify"

    // 换渠道号（v65 → v67）：渠道重要性是一次性的，只有换新 ID 重建渠道才能让
    // 「锁屏必显」权重重新生效——国产 ROM / 用户一旦把旧渠道调成静默 / 关闭，
    // 光靠旧 ID 是改不回来的，必须换 ID 重建。配合 ensureChannel 的「被降级即重建」自愈。
    private const val CHANNEL_ID = "countdown_lockscreen_v67"
    /** 历史渠道号：升级时主动删掉，避免旧渠道继续占着「静默 / 关闭」的锁屏隐藏设置。 */
    private val LEGACY_CHANNEL_IDS = arrayOf(
        "countdown_lockscreen", "countdown_lockscreen_v64", "countdown_lockscreen_v65"
    )
    private const val CHANNEL_NAME = "锁屏倒计时"
    private const val CHANNEL_DESC = "把悬浮窗里的倒计时以常驻通知显示在通知栏与锁屏上"
    /** 通知 id 基数：与其它通知（归零提醒 / 更新 / 吃药）错开，别互相顶掉。 */
    private const val ID_BASE = 5300

    /** 已经发出通知的倒计时 id → 通知 id（用于把被隐藏 / 删除的那一条收掉）。 */
    private val posted = HashMap<String, Int>()

    // ---------------- 开关 ----------------

    @Synchronized
    fun isOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ON, true)

    @Synchronized
    fun setOn(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ON, on).apply()
        if (!on) clear(ctx)
    }

    // ---------------- 刷新 / 收起 ----------------

    /**
     * 把「该显示的通知」刷成最新：只发 isVisible 的倒计时，其余（隐藏 / 删除 / 关开关）一律收掉。
     * 由悬浮窗服务每秒调用（与主界面悬浮窗同一时刻跳秒），也可在设置里点开关时立刻调一次。
     */
    @Synchronized
    fun updateAll(ctx: Context, list: List<Countdown>, now: Long = AlignedClock.now()) {
        if (!isOn(ctx)) {
            clear(ctx)
            return
        }
        val live = HashSet<String>()
        for (c in list) {
            if (!c.isVisible) continue
            live.add(c.id)
            notifyOne(ctx, c, c.remainingText(now), c.remarkText(TimeFormatPref.is24Hour(ctx)))
        }
        // 这一轮没出现的（被隐藏或删掉了）→ 把通知收掉
        val stale = posted.keys.filter { it !in live }
        for (id in stale) {
            posted.remove(id)?.let { safeCancel(ctx, it) }
        }
    }

    /** 当前发出去的锁屏通知有几条（给「关于」页显示实时状态用）。 */
    @Synchronized
    fun activeCount(): Int = posted.size

    /**
     * 提前把「锁屏必显」渠道准备好 / 自愈。
     * 服务启动、进入关于页、开机时都调一次：哪怕当前一条可见倒计时都没有，
     * 被降级的渠道也能在用户下次展开倒计时之前就修好。
     */
    fun ensureReady(ctx: Context) {
        try { ensureChannel(ctx) } catch (_: Throwable) {}
    }

    /**
     * 锁屏渠道是否处于「锁屏必显」状态（高重要性 + 锁屏公开）。
     * 给通知体检用：被降级时明确告知用户是哪一项挡住了锁屏倒计时。
     */
    fun channelHealthy(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val ch = try { nm(ctx).getNotificationChannel(CHANNEL_ID) } catch (_: Throwable) { null }
            // 渠道还没建过：发通知时会自动建成「锁屏必显」，不算异常
            ?: return true
        return ch.importance >= NotificationManager.IMPORTANCE_HIGH &&
                ch.lockscreenVisibility == Notification.VISIBILITY_PUBLIC
    }

    /**
     * 立刻发一条「锁屏通知」给用户在锁屏上验货：
     * 优先用第一条展开过的倒计时（真实数据、真实配色），一条都没有就用一条示例倒计时，
     * 这样无论有没有倒计时在跑，用户都能确认「锁屏通知到底显不显示」。
     */
    @Synchronized
    fun testOne(ctx: Context): Boolean {
        if (!isOn(ctx)) return false
        val list = try { CountdownStore.load(ctx) } catch (e: Throwable) { emptyList() }
        val c = list.firstOrNull { it.isVisible }
        if (c == null) {
            val demo = Countdown().apply {
                title = "锁屏通知测试"
                targetTime = System.currentTimeMillis() + 5 * 60 * 1000
                isVisible = true
            }
            notifyOne(ctx, demo, demo.remainingText(), "这是一条用来验货的临时通知")
        } else {
            notifyOne(ctx, c, c.remainingText(), c.remarkText(TimeFormatPref.is24Hour(ctx)))
        }
        return true
    }

    /** 全部收掉（关开关、服务停掉、App 退出时调用）。 */
    @Synchronized
    fun clear(ctx: Context) {
        val ids = ArrayList(posted.values)
        posted.clear()
        for (id in ids) safeCancel(ctx, id)
    }

    private fun notifyOne(ctx: Context, c: Countdown, text: String, remark: String) {
        try {
            val id = notifyIdOf(c)
            nm(ctx).notify(id, buildNotification(ctx, c, text, remark))
            posted[c.id] = id
        } catch (e: Throwable) {
            Log.w(TAG, "notify: ${e.message}")
        }
    }

    private fun safeCancel(ctx: Context, id: Int) {
        try {
            nm(ctx).cancel(id)
        } catch (e: Throwable) {
            Log.w(TAG, "cancel: ${e.message}")
        }
    }

    /** 每个倒计时的通知 id 由 id 稳定算出：同一条永远同一条通知，重启后仍能接着用。 */
    private fun notifyIdOf(c: Countdown): Int =
        ID_BASE + ((c.id.hashCode() and 0x7FFFFFFF) % 500)

    private fun nm(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun buildNotification(ctx: Context, c: Countdown, text: String, remark: String): Notification {
        ensureChannel(ctx)
        val color = c.customColorArgb
        // 点通知直接回主界面
        val pi = PendingIntent.getActivity(
            ctx, notifyIdOf(c),
            Intent(ctx, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        // 展开态：与悬浮窗同一套玻璃卡（标题色 / 大号时间 / 模式 / 备注）
        val rv = RemoteViews(ctx.packageName, R.layout.notification_lock_clock)
        rv.setTextViewText(R.id.lockTitle, c.title)
        rv.setTextColor(R.id.lockTitle, color)
        rv.setTextViewText(R.id.lockTime, text)
        rv.setTextViewText(R.id.lockMode, CountdownFormatter.modeName(c.displayMode, c.builtIn) +
                " | " + AnimStyle.name(c.animStyle))
        rv.setTextViewText(
            R.id.lockRemark,
            if (remark.isBlank()) "" else "备注: " + remark.replace("\n", " ")
        )

        val b = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(color)
            // 折叠态（锁屏上看到的这两行）：标题 + 剩余时间
            .setContentTitle(c.title)
            .setContentText(text)
            // 展开态：自定义玻璃卡片
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomBigContentView(rv)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        // 老版本（API < 26）没有渠道，靠这条优先级保证锁屏显示
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_HIGH)
        }
        return b.build()
    }

    /**
     * 锁屏通知专用渠道：**锁屏必显**。
     *
     * 为什么不用 IMPORTANCE_LOW：LOW 属于「静默通知」，国产 ROM（MIUI / ColorOS /
     * HarmonyOS / OriginOS…）默认只把「重要通知」摊在锁屏上，静默通知要么被折叠进
     * 「其它通知」、要么直接不显示 —— 这正是「锁屏上啥都没有」的头号原因。
     * 所以这里用 IMPORTANCE_HIGH 保证锁屏一定显示；
     * 「不会每秒响一声」由 setOnlyAlertOnce(true) 负责（同一条通知只提醒第一次），
     * 也顺带把系统里这条渠道显示成高优先级，用户一眼就能在锁屏看到。
     */
    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = nm(ctx)
        // 清掉历史渠道：它们多半被系统 / 用户记成了静默或关闭，留着只会继续在锁屏上隐藏倒计时。
        for (old in LEGACY_CHANNEL_IDS) {
            try { nm.deleteNotificationChannel(old) } catch (_: Throwable) {}
        }
        val cur = try { nm.getNotificationChannel(CHANNEL_ID) } catch (_: Throwable) { null }
        // 关键自愈：渠道一旦存在就再也不检查，是「锁屏突然收不到」的头号根因——
        // 用户或国产 ROM 把重要性调成「静默 / 关闭」后，锁屏就再也不显示，而旧代码直接 return。
        // 这里每次都校验，发现被降级就删掉重建为「锁屏必显」的高重要性渠道。
        if (cur != null && cur.importance >= NotificationManager.IMPORTANCE_HIGH &&
            cur.lockscreenVisibility == Notification.VISIBILITY_PUBLIC) return
        if (cur != null) {
            try { nm.deleteNotificationChannel(CHANNEL_ID) } catch (_: Throwable) {}
        }
        val ch = NotificationChannel(
            CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
        )
        ch.description = CHANNEL_DESC
        ch.setShowBadge(false)
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        // 高重要性渠道首次提醒会响一声：这里不设声音 / 震动，配合通知的 OnlyAlertOnce，
        // 保证「锁屏必显」的同时不会每秒叮当响。
        ch.setSound(null, null)
        ch.enableVibration(false)
        nm.createNotificationChannel(ch)
    }

    private const val TAG = "LockScreenClock"
}
