package com.baigao.countdown

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 目标时间的统一文案：日期时间 + 星期几。
 *
 * v1.0.0.63（versionCode 197）：所有内置倒计时与普通倒计时的「目标时间」后面
 * 都自动补上那一天是星期几，例如 2026-11-19 08:00 是星期四，界面就显示
 * 「2026-11-19 08:00 星期四」。内置项的目标时刻会跨整点 / 跨天滚动，
 * 星期由目标时刻实时推算，跨到次日自动变成「星期五」，不会停在旧值。
 */
object TargetTimeText {

    /** Calendar.DAY_OF_WEEK 从 SUNDAY=1 起，按中文习惯排成 日..六。 */
    private val WEEK_CN = arrayOf("日", "一", "二", "三", "四", "五", "六")

    /** 每次现造一份，避免多处并发刷新时共享 SimpleDateFormat 出错。 */
    private fun dateTimeFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private fun weekName(cal: Calendar): String {
        val i = cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        return if (i in WEEK_CN.indices) "星期" + WEEK_CN[i] else ""
    }

    /** 「星期四」；epoch 毫秒无效时返回空串。 */
    fun weekdayOf(timeMillis: Long): String {
        if (timeMillis <= 0L) return ""
        return weekName(Calendar.getInstance().apply { timeInMillis = timeMillis })
    }

    /** 按年月日取星期文案，供 DatePicker 使用（month 为 0..11）。 */
    fun weekdayOfDate(year: Int, monthZeroBased: Int, day: Int): String {
        val cal = Calendar.getInstance().apply {
            clear()
            set(year, monthZeroBased, day)
        }
        return weekName(cal)
    }

    /** 完整目标时间文案：yyyy-MM-dd HH:mm 星期X。 */
    fun full(timeMillis: Long): String {
        val base = dateTimeFormat().format(Date(timeMillis))
        val w = weekdayOf(timeMillis)
        return if (w.isEmpty()) base else "$base $w"
    }
}
