package com.baigao.countdown

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * v1.0.0.66：App 内界面 / 功能配置编辑器。
 *
 * 直接编辑一份 JSON（结构见仓库根目录 ui_config.json），保存后本地立即生效，
 * 不用重装 APK。要同步到所有设备：点「复制配置」把 JSON 粘到 GitHub 仓库的
 * ui_config.json，其它设备打开 App 即自动拉取生效。App 内不放 GitHub 凭据。
 */
class ConfigEditorActivity : Activity() {

    private lateinit var edit: EditText
    private lateinit var statusTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1020.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            text = "界面 / 功能配置"
            textSize = 20f
            setTextColor(0xFF00D8FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(title)

        statusTv = TextView(this).apply {
            text = "最近同步：${RemoteSkin.lastSyncText(this@ConfigEditorActivity)}"
            textSize = 12f
            setTextColor(0xFFA8B3CC.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(statusTv)

        edit = EditText(this).apply {
            setText(RemoteSkin.currentJson())
            textSize = 12f
            setTextColor(0xFFE4EEFF.toInt())
            setBackgroundColor(0xFF141A33.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            gravity = Gravity.TOP
            minLines = 12
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val scroll = ScrollView(this).apply { addView(edit) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val tip = TextView(this).apply {
            text = "改完点「保存生效」立即生效。要同步其它设备，点「复制配置」后粘贴到 " +
                    "GitHub 仓库的 ui_config.json；其它设备打开 App 即自动同步。\n" +
                    "清空覆盖则回到 GitHub 上的配置。结构写错会提示，不会损坏原有设置。"
            textSize = 12f
            setTextColor(0xFF7C86A3.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(tip)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        fun mkBtn(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { action() }
        }
        btnRow.addView(mkBtn("保存生效") { save() })
        btnRow.addView(mkBtn("复制配置") { copy() })
        btnRow.addView(mkBtn("粘贴导入") { paste() })
        root.addView(btnRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val btnRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow2.addView(mkBtn("立即同步") { syncNow() })
        btnRow2.addView(mkBtn("载入远程") { loadRemote() })
        btnRow2.addView(mkBtn("清空覆盖") { clearLocal() })
        root.addView(btnRow2, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(mkBtn("返回") { finish() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        setContentView(root)
    }

    private fun currentText(): String = edit.text.toString()

    private fun save() {
        val raw = currentText()
        try {
            JSONObject(raw) // 结构校验
        } catch (t: Throwable) {
            Toast.makeText(this, "JSON 格式不对：${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            return
        }
        RemoteSkin.saveLocal(this, raw)
        RemoteSkin.refresh(this, null)
        DynamicFeature.loadAll(this, null)
        Toast.makeText(this, "已保存并立即生效（不用重装 APK）", Toast.LENGTH_SHORT).show()
        statusTv.text = "本地覆盖已生效 · ${RemoteSkin.lastSyncText(this)}"
    }

    private fun copy() {
        val raw = currentText()
        try { JSONObject(raw) } catch (t: Throwable) {
            Toast.makeText(this, "JSON 格式不对，先修好再复制", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ui_config", raw))
        Toast.makeText(this, "已复制，粘贴到 GitHub 仓库的 ui_config.json 即可同步", Toast.LENGTH_LONG).show()
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "剪贴板里没有配置文本", Toast.LENGTH_SHORT).show()
            return
        }
        edit.setText(text)
        Toast.makeText(this, "已粘贴，检查后点「保存生效」", Toast.LENGTH_SHORT).show()
    }

    private fun syncNow() {
        Toast.makeText(this, "正在从 GitHub 同步…", Toast.LENGTH_SHORT).show()
        RemoteSkin.refresh(this) { ok, msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            if (ok && !RemoteSkin.hasLocal(this)) edit.setText(RemoteSkin.currentJson())
            statusTv.text = "最近同步：${RemoteSkin.lastSyncText(this)}"
        }
    }

    private fun loadRemote() {
        val r = RemoteSkin.remoteJson(this)
        if (r.isBlank()) {
            Toast.makeText(this, "还没有远程缓存，先点「立即同步」", Toast.LENGTH_SHORT).show()
            return
        }
        edit.setText(r)
        Toast.makeText(this, "已载入 GitHub 上的配置（未保存，仅预览）", Toast.LENGTH_SHORT).show()
    }

    private fun clearLocal() {
        RemoteSkin.saveLocal(this, "")
        edit.setText(RemoteSkin.currentJson())
        Toast.makeText(this, "已清除本地覆盖，回到 GitHub 配置", Toast.LENGTH_SHORT).show()
        statusTv.text = "最近同步：${RemoteSkin.lastSyncText(this)}"
    }
}
