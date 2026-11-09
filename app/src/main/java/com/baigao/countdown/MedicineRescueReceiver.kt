package com.baigao.countdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 吃药提醒的「系统事件自愈」接收器（纯框架实现，零第三方依赖）。
 *
 * ---------- 为什么还需要它 ----------
 * 从最近任务划掉卡片时，国产 ROM 往往等同 forceStopPackage：系统会一次性清掉本包的
 * 全部 AlarmManager 闹钟与 JobScheduler 任务，并把包置为 stopped。此时所有挂在自己
 * 包里的兜底（主路、备路、补响、巡检、守护服务）会**同时**失效，没有任何组件能再把
 * 提醒挂回来。系统闹钟 App 不受影响，是因为它是特权系统应用、被列入白名单。
 *
 * 于是把「亮屏 / 解锁 / 充电断电 / 改时间 / 跨天 / 改时区语言 / 应用更新」这些日常
 * 一定会发生的系统事件都接上：每收到一次就重挂全链路、补发漏掉的剂次、刷新状态通知。
 * 全部动作幂等，maybeNotify() 按「日期#剂次」去重，绝不会因为频繁亮屏而重复打扰。
 * 守护服务还会在运行时动态注册一份（SCREEN_ON 等广播 Android 8+ 不允许静态注册），
 * 两条路互为补充。
 *
 * ---------- 如实说明的边界 ----------
 * Android 默认对 stopped 状态的包投递广播时带 FLAG_EXCLUDE_STOPPED_PACKAGES，
 * 是否放行取决于系统 / ROM。所以这一步是「尽量自愈」，仍必须请用户把本应用加入
 * 电池优化白名单、允许自启动与后台运行；第三方应用无法做到与特权系统闹钟 100% 等价。
 * 这里刻意不注册 BOOT_COMPLETED / LOCKED_BOOT_COMPLETED：未解锁阶段读写偏好设置会崩，
 * 那两路由带 userUnlocked 保护的 BootReceiver 负责。
 */
class MedicineRescueReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            // 含一次「尝试拉起守护前台服务」：Android 12+ 从普通广播启动前台服务会被拒，
            // MedicineGuardService.start() 内部已经 try/catch 降级，闹钟本身照常重挂。
            MedicineReminder.onPeriodicCheck(context)
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "rescue: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MedicineRescue"
    }
}
