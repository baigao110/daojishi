package com.baigao.countdown

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.Locale
import android.text.SpannableStringBuilder

/**
 * 吃药日历：按月显示哪天吃了药、哪天没吃。
 *
 * - 已吃药：青色实心圆；未吃药（今天之前的日子）：淡红底；今天未吃：青色描边；
 *   未来日期：淡灰、不可点。
 * - 点过去 / 今天的格子可以补记或撤销；底部按钮一键记录今天。
 * - 顶部 ‹ › 切换月份，统计行显示本月已吃 / 未吃天数。
 *
 * 风格沿用液态玻璃：glass_bg 底 + GlassBackdrop 光斑 + circle_btn 青色按钮。
 */
class MedicineCalendarActivity : Activity() {

    private lateinit var monthTv: TextView
    private lateinit var grid: LinearLayout
    private lateinit var statTv: TextView
    /** v1.0.0.65：本月每条「已吃药」记录的具体时间。 */
    private lateinit var recordTv: TextView
    private lateinit var todayBtn: Button

    /** 当前显示的月份（day 恒为 1）。 */
    private val shown = Calendar.getInstance()
    private val today = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medicine)
        // 点开通知本体进到吃药日历：常驻提醒的使命完成，清掉它（点「已吃药」也会清）
        MedicineReminder.cancelNotification(this)

        monthTv = findViewById(R.id.monthTv)
        grid = findViewById(R.id.gridContainer)
        statTv = findViewById(R.id.statTv)
        recordTv = findViewById(R.id.recordTv)
        todayBtn = findViewById(R.id.todayBtn)

        shown.set(Calendar.DAY_OF_MONTH, 1)
        shown.firstDayOfWeek = Calendar.MONDAY

        findViewById<TextView>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.prevBtn).setOnClickListener {
            shown.add(Calendar.MONTH, -1)
            render()
        }
        findViewById<Button>(R.id.nextBtn).setOnClickListener {
            shown.add(Calendar.MONTH, 1)
            render()
        }
        todayBtn.setOnClickListener {
            val taken = MedicineReminder.isTaken(this)
            MedicineReminder.setTaken(this, !taken)
            val at = MedicineReminder.takenTimeText(this)
            val msg = if (taken) "已经撤销今天的记录啦"
                else if (at.isNotEmpty()) "已经记下今天吃药啦（$at）"
                else "已经记下今天吃药啦"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            render()
        }
        findViewById<Button>(R.id.timesBtn).setOnClickListener { showTimesPicker() }
        findViewById<Button>(R.id.doseEditBtn).setOnClickListener {
            DoseEditor.showDialog(this) { render() }
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        // 屏幕常亮开关：开启时本应用在前台保持屏幕常亮不锁屏
        ScreenKeepOn.apply(this)
        today.timeInMillis = System.currentTimeMillis()
        render()
    }

    override fun onPause() {
        super.onPause()
        // 离开前台就清掉常亮 Flag，避免关掉开关后 Flag 残留在已暂停的窗口上
        ScreenKeepOn.onPause(this)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 圆形背景：已吃药（青实心）/ 未吃药（淡红）/ 今天（青描边）/ 未来（淡灰）。 */
    private fun bubble(fill: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        if (stroke != 0) setStroke(dp(2), stroke)
    }

    private fun render() {
        val fmt = MedicineReminder.keyFormat()
        val taken = MedicineReminder.takenKeys(this)

        monthTv.text = String.format(
            Locale.getDefault(), "%d年%d月",
            shown.get(Calendar.YEAR), shown.get(Calendar.MONTH) + 1
        )

        // ---------- 日期格子 ----------
        val first = shown.clone() as Calendar
        first.set(Calendar.DAY_OF_MONTH, 1)
        first.firstDayOfWeek = Calendar.MONDAY
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        // 周一为第一列：Calendar 的 DAY_OF_WEEK 以周日=1 起算，(dow+5)%7 即周一起的偏移
        val lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7

        val cells = ArrayList<Int>()
        repeat(lead) { cells.add(0) }
        for (d in 1..daysInMonth) cells.add(d)
        while (cells.size % 7 != 0) cells.add(0)

        grid.removeAllViews()
        var i = 0
        while (i < cells.size) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
            )
            for (k in 0 until 7) {
                row.addView(cellView(cells[i + k], fmt, taken))
            }
            grid.addView(row)
            i += 7
        }

        // ---------- 统计 ----------
        var took = 0
        var missed = 0
        for (d in 1..daysInMonth) {
            val c = shown.clone() as Calendar
            c.set(Calendar.DAY_OF_MONTH, d)
            val key = fmt.format(c.time)
            val isFuture = c.get(Calendar.YEAR) > today.get(Calendar.YEAR) ||
                    (c.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                            c.get(Calendar.DAY_OF_YEAR) > today.get(Calendar.DAY_OF_YEAR))
            if (isFuture) continue
            if (taken.contains(key)) took++ else missed++
        }
        val medN = MedicineReminder.timesPerDay(this)
        val statBase = SpannableStringBuilder(
            "本月吃过 ${took} 天 · 还没吃 ${missed} 天" +
                    if (missed > 0) "（没吃药的日子可以在日历里补记哦）" else ""
        )
        if (medN > 1) {
            statBase.append("\n每天会提醒 ${medN} 次哦：")
                .append(MedicineReminder.doseScheduleSpannable(this))
        }
        statBase.append("\n服药时机：${MedicineReminder.DOSE_CONTEXTS.joinToString(" / ")}")
        statTv.text = statBase

        // ---------- 今日按钮 ----------
        todayBtn.text = if (MedicineReminder.isTaken(this)) "撤销今天记录" else "今天已吃药"

        // ---------- v1.0.0.65：逐条列出本月「已吃药」的具体时间 ----------
        renderRecords(fmt, taken)
    }

    /**
     * v1.0.0.65：本月吃药记录逐条显示「已吃药」的具体时间，
     * 统一格式 yyyy年MM月dd日 星期x HH时:mm分:ss秒（如 2026年11月22日 星期日 15时:56分:22秒）；
     * 升级前只记到日期的旧记录明确标注。
     */
    private fun renderRecords(fmt: java.text.SimpleDateFormat, taken: Set<String>) {
        val sb = StringBuilder("本月吃药记录 · 已吃药的具体时间")
        val lines = ArrayList<String>()
        for (d in 1..shown.getActualMaximum(Calendar.DAY_OF_MONTH)) {
            val c = shown.clone() as Calendar
            c.set(Calendar.DAY_OF_MONTH, d)
            val key = fmt.format(c.time)
            if (!taken.contains(key)) continue
            val at = MedicineReminder.takenTimeText(this, key)
            if (at.isNotEmpty()) {
                lines.add(at)
            } else {
                val p = key.split("-")
                val day = if (p.size == 3) "${p[0]}年${p[1]}月${p[2]}日" else key
                // v203：旧记录虽然没记下时刻，日期后面依旧跟着那天的星期，整列格式统一
                val week = if (p.size == 3) {
                    val y = p[0].toIntOrNull(); val mo = p[1].toIntOrNull(); val dy = p[2].toIntOrNull()
                    if (y != null && mo != null && dy != null) TargetTimeText.weekdayOfDate(y, mo - 1, dy) else ""
                } else ""
                lines.add(if (week.isEmpty()) "$day（旧记录只记到日期）" else "$day $week（旧记录只记到日期）")
            }
        }
        if (lines.isEmpty()) {
            sb.append("\n本月还没有吃药记录哦")
        } else {
            for (s in lines.asReversed()) sb.append("\n").append(s)
        }
        recordTv.text = sb.toString()
    }

    private fun cellView(day: Int, fmt: java.text.SimpleDateFormat, taken: Set<String>): FrameLayout {
        val box = FrameLayout(this)
        box.layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
        if (day <= 0) return box

        val c = shown.clone() as Calendar
        c.set(Calendar.DAY_OF_MONTH, day)
        val key = fmt.format(c.time)

        val sameYear = c.get(Calendar.YEAR) == today.get(Calendar.YEAR)
        val isToday = sameYear && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val isFuture = (c.get(Calendar.YEAR) > today.get(Calendar.YEAR)) ||
                (sameYear && c.get(Calendar.DAY_OF_YEAR) > today.get(Calendar.DAY_OF_YEAR))
        val isTaken = taken.contains(key)

        val tv = TextView(this)
        tv.text = day.toString()
        tv.gravity = Gravity.CENTER
        tv.textSize = 14f
        tv.setShadowLayer(2f, 0f, 1f, 0xCC000000.toInt())

        when {
            isTaken -> {
                tv.background = bubble(0xFF00FFFF.toInt(), 0)
                tv.setTextColor(0xFF001018.toInt())
            }
            isFuture -> {
                tv.background = bubble(0x1AFFFFFF, 0)
                tv.setTextColor(0xFF7C86A3.toInt())
            }
            isToday -> {
                tv.background = bubble(0x00000000, 0xFF4DFBFF.toInt())
                tv.setTextColor(0xFF4DFBFF.toInt())
            }
            else -> {
                // 已经过去却没记录：淡红底，一眼看出漏了
                tv.background = bubble(0x3CFF5A6E.toInt(), 0)
                tv.setTextColor(0xFFFFD8DE.toInt())
            }
        }
        val lp = FrameLayout.LayoutParams(dp(34), dp(34))
        lp.gravity = Gravity.CENTER
        box.addView(tv, lp)

        // 过去与今天的格子可点：补记 / 撤销
        if (!isFuture) {
            box.setOnClickListener {
                val nowTaken = MedicineReminder.isTaken(this, key)
                MedicineReminder.setTaken(this, !nowTaken, key)
                val at = MedicineReminder.takenTimeText(this, key)
                val msg = if (nowTaken) "已经撤销 ${monthLabel(day)} 的记录啦"
                    else if (at.isNotEmpty()) "已经补记 ${monthLabel(day)} 啦（$at）"
                    else "已经补记 ${monthLabel(day)} 啦"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                render()
            }
        }
        return box
    }

    private fun monthLabel(day: Int): String =
        "${shown.get(Calendar.MONTH) + 1}月${day}日"

    /** 「每天次数」选择：1/2/3/4 次（与「关于」页同一逻辑）。 */
    private fun showTimesPicker() {
        if (isFinishing) return
        val opts = arrayOf("1 次 / 天", "2 次 / 天", "3 次 / 天", "4 次 / 天")
        val cur = MedicineReminder.timesPerDay(this) - 1
        AlertDialog.Builder(this)
            .setTitle("每天想提醒几次呀")
            .setSingleChoiceItems(opts, cur) { d, which ->
                MedicineReminder.setTimesPerDay(this, which + 1)
                render()
                d.dismiss()
                Toast.makeText(this, "已经设为每天 ${which + 1} 次啦", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("先不了", null)
            .show()
    }
}
