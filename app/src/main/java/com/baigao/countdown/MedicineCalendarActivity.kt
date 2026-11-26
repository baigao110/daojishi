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
import android.text.style.ForegroundColorSpan

/**
 * 吃药日历：按月显示哪天吃了药、哪天没吃。
 *
 * - 已吃药（正常记录）：青色实心圆；补记：琥珀色实心圆 + 右上角「补」角标；
 *   未吃药（今天之前的日子）：淡红底；今天未吃：青色描边；未来日期：淡灰、不可点。
 * - 点过去 / 今天的格子先弹同款玻璃确认框：补记选「是」才记、撤销选「是」才撤，
 *   选「否」一律什么都不改；补记时间为当前时间，并登记「补记」标志。
 * - 顶部 ‹ › 切换月份，统计行显示本月已吃 / 未吃，以及补记 / 正常记录各多少天。
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
            if (taken) {
                // v204：撤销记录也要先确认，选「否」就原样保留
                confirmUndo("今天", MedicineReminder.todayKey())
            } else {
                MedicineReminder.setTaken(this, true)
                val at = MedicineReminder.takenTimeText(this)
                val msg = if (at.isNotEmpty()) "已经记下今天吃药啦（$at）"
                    else "已经记下今天吃药啦"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                render()
            }
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
        var makeup = 0
        val mk = MedicineReminder.makeupKeys(this)
        for (d in 1..daysInMonth) {
            val c = shown.clone() as Calendar
            c.set(Calendar.DAY_OF_MONTH, d)
            val key = fmt.format(c.time)
            val isFuture = c.get(Calendar.YEAR) > today.get(Calendar.YEAR) ||
                    (c.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                            c.get(Calendar.DAY_OF_YEAR) > today.get(Calendar.DAY_OF_YEAR))
            if (isFuture) continue
            if (taken.contains(key)) {
                took++
                if (mk.contains(key)) makeup++
            } else missed++
        }
        val normal = took - makeup
        val medN = MedicineReminder.timesPerDay(this)
        val statBase = SpannableStringBuilder(
            "本月吃过 ${took} 天 · 还没吃 ${missed} 天" +
                    if (missed > 0) "（没吃药的日子可以在日历里补记哦）" else ""
        )
        // v204：本月补记 / 正常记录各多少天，跟着格子实时刷新
        statBase.append("\n本月补记 ${makeup} 天 · 正常记录 ${normal} 天")
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
     * v204：每条末尾带「（补记）」/「（正常）」标志，补记标琥珀色，一眼分得清。
     */
    private fun renderRecords(fmt: java.text.SimpleDateFormat, taken: Set<String>) {
        val mk = MedicineReminder.makeupKeys(this)
        val sb = SpannableStringBuilder("本月吃药记录 · 已吃药的具体时间")
        val lines = ArrayList<Pair<String, Boolean>>()
        for (d in 1..shown.getActualMaximum(Calendar.DAY_OF_MONTH)) {
            val c = shown.clone() as Calendar
            c.set(Calendar.DAY_OF_MONTH, d)
            val key = fmt.format(c.time)
            if (!taken.contains(key)) continue
            val isMakeup = mk.contains(key)
            val at = MedicineReminder.takenTimeText(this, key)
            if (at.isNotEmpty()) {
                lines.add(at to isMakeup)
            } else {
                val p = key.split("-")
                val day = if (p.size == 3) "${p[0]}年${p[1]}月${p[2]}日" else key
                // v203：旧记录虽然没记下时刻，日期后面依旧跟着那天的星期，整列格式统一
                val week = if (p.size == 3) {
                    val y = p[0].toIntOrNull(); val mo = p[1].toIntOrNull(); val dy = p[2].toIntOrNull()
                    if (y != null && mo != null && dy != null) TargetTimeText.weekdayOfDate(y, mo - 1, dy) else ""
                } else ""
                val body = if (week.isEmpty()) "$day（旧记录只记到日期）"
                    else "$day $week（旧记录只记到日期）"
                lines.add(body to isMakeup)
            }
        }
        if (lines.isEmpty()) {
            sb.append("\n本月还没有吃药记录哦")
        } else {
            for ((body, isMakeup) in lines.asReversed()) {
                sb.append("\n").append(body)
                val start = sb.length
                sb.append(if (isMakeup) "（补记）" else "（正常）")
                sb.setSpan(
                    ForegroundColorSpan(if (isMakeup) 0xFFFFC24D.toInt() else 0xFF7CF6C8.toInt()),
                    start, sb.length, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        recordTv.text = sb
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
        val isMakeup = isTaken && MedicineReminder.isMakeup(this, key)

        val tv = TextView(this)
        tv.text = day.toString()
        tv.gravity = Gravity.CENTER
        tv.textSize = 14f
        tv.setShadowLayer(2f, 0f, 1f, 0xCC000000.toInt())

        when {
            isMakeup -> {
                // v204：补记用琥珀实心圆，和正常记录的青色实心圆一眼分得开
                tv.background = bubble(0xFFFFC24D.toInt(), 0)
                tv.setTextColor(0xFF3A2000.toInt())
            }
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

        // v204：右上角「补」角标，落在圆形右上角外侧，不压住日期数字
        if (isMakeup) {
            val badge = TextView(this)
            badge.text = "补"
            badge.gravity = Gravity.CENTER
            badge.textSize = 8f
            badge.setTextColor(0xFF3A2000.toInt())
            badge.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFF3D0.toInt())
                setStroke(dp(1), 0xFFE08A00.toInt())
            }
            val bp = FrameLayout.LayoutParams(dp(14), dp(14))
            bp.gravity = Gravity.TOP or Gravity.END
            box.addView(badge, bp)
        }

        // 过去与今天的格子可点：先弹同款玻璃确认框，选「是」才补记 / 撤销
        if (!isFuture) {
            box.setOnClickListener {
                if (MedicineReminder.isTaken(this, key)) confirmUndo(monthLabel(day), key)
                else confirmMakeup(monthLabel(day), key)
            }
        }
        return box
    }

    private fun monthLabel(day: Int): String =
        "${shown.get(Calendar.MONTH) + 1}月${day}日"

    /** 两个确认框共用的正文样式：与「关于」页、更新提示等玻璃提示框一致。 */
    private fun dialogText(s: String): TextView = TextView(this).apply {
        text = s
        setTextColor(0xFFE4EEFF.toInt())
        textSize = 14f
        setLineSpacing(0f, 1.45f)
        setShadowLayer(2f, 0f, 1f, 0xCC000000.toInt())
        setPadding(dp(4), dp(6), dp(4), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    /**
     * v204 补记确认框：选「是」才补记，选「否」什么都不改。
     * 补记的时间就是当前时间（setTaken 写 System.currentTimeMillis()），
     * 并登记补记标志，日历格子与本月记录都会标上「补记」。
     */
    private fun confirmMakeup(label: String, key: String) {
        if (isFinishing) return
        val now = MedicineReminder.currentTimeText()
        UpdateManager.showStyledDialog(
            activity = this,
            title = "要补记这一天的吃药记录吗",
            positiveText = "是",
            negativeText = "否",
            cancelable = true,
            onPositive = {
                MedicineReminder.setTaken(this, true, key, makeup = true)
                val at = MedicineReminder.takenTimeText(this, key)
                val msg = if (at.isNotEmpty()) "已经补记 $label 啦（$at · 补记）"
                    else "已经补记 $label 啦（补记）"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                render()
            }
        ) { host ->
            host.addView(
                dialogText(
                    "要把 $label 记为「已吃药」吗？\n\n" +
                            "选「是」就补记，时间记为当前时间：\n$now\n" +
                            "日历格子和本月记录里都会标上「补记」，和正常记录区分开；\n" +
                            "选「否」就不补记，什么都不改。"
                )
            )
        }
    }

    /** v204 撤销确认框：选「是」才撤销记录，选「否」原样保留。 */
    private fun confirmUndo(label: String, key: String) {
        if (isFinishing) return
        UpdateManager.showStyledDialog(
            activity = this,
            title = "要撤销这一天的吃药记录吗",
            positiveText = "是",
            negativeText = "否",
            cancelable = true,
            onPositive = {
                MedicineReminder.setTaken(this, false, key)
                Toast.makeText(this, "已经撤销 $label 的记录啦", Toast.LENGTH_SHORT).show()
                render()
            }
        ) { host ->
            host.addView(
                dialogText(
                    "要把 $label 的吃药记录撤销吗？\n\n" +
                            "选「是」就撤销，这一天会重新变成「还没吃」，" +
                            "记录的具体时间和补记标志也一并清掉；\n" +
                            "选「否」就不撤销，记录原样保留。"
                )
            )
        }
    }

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
