package com.baigao.countdown

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 吃药提醒的闹钟接收器（纯框架实现）。
 *
 * 三个动作：
 * - ACTION_ALARM：到点了 —— 交给 MedicineReminder.onAlarm()：先把下一次闹钟排上（多路冗余），
 *   再按需发通知（同一天同一剂只会发一次，重复闹钟 / 巡检 / 每小时守护都不会重复打扰）；
 * - ACTION_TAKEN：通知上的「已吃药」—— 记下今天并收起通知；
 * - ACTION_REVIVE：划掉卡片 / 服务被销毁时排下的「复活闹钟」响了 —— 重挂全部闹钟，
 *   并尝试把守护前台服务重新拉起来，让提醒链路在退出 App 后仍能自己接上。
 */
class MedicineAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MedicineReminder.ACTION_TAKEN -> {
                MedicineReminder.setTaken(context, true)
                MedicineReminder.cancelNotification(context)
                try {
                    Toast.makeText(context, "已经记下今天吃药啦", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    // 部分 ROM 不允许后台弹 Toast，忽略
                }
            }
            MedicineReminder.ACTION_REVIVE -> {
                try {
                    MedicineReminder.onRevive(context)
                } catch (e: Throwable) {
                    android.util.Log.w("MedicineAlarm", "revive: ${e.message}")
                }
            }
            else -> {
                try {
                    MedicineReminder.onAlarm(context)
                } catch (e: Throwable) {
                    android.util.Log.w("MedicineAlarm", "alarm: ${e.message}")
                }
            }
        }
    }
}
