package com.baigao.countdown

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 吃药提醒守护服务（前台服务，纯框架实现，零第三方依赖）。
 *
 * ---------- 为什么以前「后台退出后就不提醒」 ----------
 * 旧版的三路闹钟、15 分钟巡检、开机重排，全都挂在**同一个应用包**里。用户从最近任务
 * 划掉卡片时，国产 ROM 会 forceStopPackage：系统会清掉本包的全部 AlarmManager 闹钟、
 * 取消全部 JobScheduler 任务，还把包置为 stopped —— 连开机广播都收不到。
 * 于是没有任何组件能把提醒重新挂回来，只能等用户下次手动打开 App（catchUp 才补挂）。
 * 系统闹钟 App 不受影响，是因为它常驻前台服务、且被系统列入白名单。
 *
 * ---------- 这一版补上的四道保险 ----------
 * ① 前台服务常驻：进程优先级提到「可见」，常规的一键清理不会先动它；
 * ② START_STICKY：进程被系统回收后由系统自动重建服务，重建时立刻重挂闹钟；
 * ③ onTaskRemoved()：用户划掉卡片的**那一瞬间**是最后还能执行代码的时机 ——
 *    立刻重挂全部闹钟，并排一个 1.5 秒后的系统闹钟把服务拉回来；
 * ④ onDestroy()：服务被销毁时同样留一次重挂机会。
 *
 * 所有跨版本 API 都包了 try/catch：Android 12+ 的「后台禁止启动前台服务」不会让应用
 * 崩溃，拉不起来时也会退化为纯闹钟兜底（闹钟已在 onGuardRevive() 里重挂过）。
 */
class MedicineGuardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createGuardChannel()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "channel: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须第一件事就进前台：startForegroundService() 要求 5 秒内调用 startForeground()
        try {
            startForeground(GUARD_NOTIF_ID, buildNotification())
        } catch (e: Throwable) {
            // 前台通知挂不上就别赖着：否则系统会以「前台服务未及时启动」杀进程并报崩溃。
            // 闹钟仍照常重挂，退化成纯 AlarmManager 兜底。
            android.util.Log.w(TAG, "startForeground: ${e.message}")
            try {
                MedicineReminder.schedule(this)
            } catch (e2: Throwable) {
                // 忽略
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            try {
                @Suppress("DEPRECATION")
                stopForeground(true)
            } catch (e: Throwable) {
                // 忽略
            }
            stopSelf()
            return START_NOT_STICKY
        }
        // 首次启动 / START_STICKY 重建（intent == null）/ 复活广播拉起，
        // 都把闹钟与巡检重新挂一遍 —— 这就是「被清掉后能自愈」的核心。
        // 注意这里只调 schedule()（纯闹钟），不调 start()，否则会自己把自己拉成死循环。
        try {
            MedicineReminder.schedule(this)
            MedicineReminder.syncMedicineNotification(this)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "reschedule: ${e.message}")
        }
        return START_STICKY
    }

    /** 从最近任务划掉卡片：趁进程还在，重挂闹钟 + 排复活闹钟。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            MedicineReminder.onGuardRevive(this)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "onTaskRemoved: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    /** 服务被销毁（系统回收 / 一键清理）：同样留一次重挂机会。 */
    override fun onDestroy() {
        try {
            if (MedicineReminder.isEnabled(this)) MedicineReminder.onGuardRevive(this)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "onDestroy: ${e.message}")
        }
        super.onDestroy()
    }

    private fun piFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

    /**
     * 守护通知走独立的低重要性渠道：静默、不响铃、不弹横幅，只是前台服务必须挂一条。
     * 与「每日吃药提醒 · 已开启」状态通知、到点的「该吃药啦」闹钟通知用不同 id，互不顶替。
     */
    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, REQ_GUARD_OPEN,
            Intent(this, MedicineCalendarActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            piFlags()
        )
        return Notification.Builder(this, GUARD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("吃药提醒守护中")
            .setContentText("划掉应用卡片后仍会按时提醒 · 点开看吃药日历")
            .setContentIntent(openPi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(Notification.PRIORITY_MIN)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createGuardChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(GUARD_CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            GUARD_CHANNEL_ID, "吃药提醒后台守护", NotificationManager.IMPORTANCE_LOW
        )
        ch.description = "保持吃药提醒在退出应用后仍能按时送达（静默、不响铃）"
        ch.enableVibration(false)
        ch.setShowBadge(false)
        try {
            ch.setSound(null, null)
        } catch (e: Throwable) {
            // 部分 ROM 不允许置空铃声，忽略即可
        }
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "MedicineGuard"
        const val ACTION_START = "com.baigao.countdown.action.MEDICINE_GUARD_START"
        const val ACTION_STOP = "com.baigao.countdown.action.MEDICINE_GUARD_STOP"
        const val GUARD_CHANNEL_ID = "medicine_guard_v64"
        private const val GUARD_NOTIF_ID = 20333
        private const val REQ_GUARD_OPEN = 4122

        /** 开启提醒 / 打开 App / 开机 / 闹钟到点 / 复活广播都调它，重复调用是安全的。 */
        fun start(ctx: Context) {
            if (!MedicineReminder.isEnabled(ctx)) return
            val i = Intent(ctx, MedicineGuardService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (e: Throwable) {
                // Android 12+ 后台禁止启动前台服务：退化成普通启动，能跑多少算多少。
                // 拉不起来也没关系，闹钟已经在 onGuardRevive() / schedule() 里挂好了。
                try {
                    ctx.startService(i)
                } catch (e2: Throwable) {
                    android.util.Log.w(TAG, "start: ${e2.message}")
                }
            }
        }

        fun stop(ctx: Context) {
            // 不用 isRunning() 做前置判断：部分 ROM 的 getRunningServices 会漏报，
            // 一旦误判成「没在跑」，关掉提醒后守护通知就会一直挂在通知栏里。
            // 无条件投递 ACTION_STOP：服务没运行时也只是短暂创建再立刻收工，无副作用。
            // 先让服务自己 stopForeground(true) + stopSelf()：守护通知会一起撤掉，
            // 且返回 START_NOT_STICKY 不会被系统重建；失败再退化成直接 stopService。
            try {
                ctx.startService(
                    Intent(ctx, MedicineGuardService::class.java).setAction(ACTION_STOP)
                )
            } catch (e: Throwable) {
                try {
                    ctx.stopService(Intent(ctx, MedicineGuardService::class.java))
                } catch (e2: Throwable) {
                    android.util.Log.w(TAG, "stop: ${e2.message}")
                }
            }
        }

        /** 自检页用：守护服务当前是否在运行。 */
        fun isRunning(ctx: Context): Boolean = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val list = am.getRunningServices(Int.MAX_VALUE)
            list.any { it.service.className == MedicineGuardService::class.java.name }
        } catch (e: Throwable) {
            false
        }
    }
}
