package com.baigao.countdown

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.0.0.66：远程界面 / 功能配置引擎。
 *
 * 目标：不用重装 APK，改一份放在 GitHub 仓库的 ui_config.json，打开工具（或退出重进任意界面）
 * 即同步改变 —— 覆盖主题色、文案、版本号显示、功能开关、动态功能按钮、动态 dex 模块。
 *
 * 设计铁律：**零配置时行为与旧版完全一致**。所有访问器都带默认值，任何字段缺失 / 拉取失败
 * 都回退到写死的旧值；只有配置里显式给出的键才会改变界面。
 *
 * 配置来源优先级：本地编辑器覆盖(local) > 远程缓存(remote) > 内置默认。
 * 拉取失败时静默用缓存，不影响 App 任何功能。
 */
object RemoteSkin {

    // ---------------- 配置模型 ----------------
    data class Theme(
        val accent: String? = null,       // 主强调色 #AARRGGBB
        val accentDeep: String? = null,   // 深一档强调色
        val bgTop: String? = null,        // 背景渐变上端
        val bgCenter: String? = null,
        val bgBottom: String? = null,
        val text: String? = null          // 主文字色
    )

    data class Action(
        val label: String,
        val type: String,     // url / toast / dialog / intent / module
        val value: String
    )

    data class Module(
        val name: String,
        val dexUrl: String,
        val className: String,
        val enabled: Boolean = true
    )

    /** v207：按 view id/tag 精确设置单个控件样式。 */
    data class ViewStyle(
        val textColor: String? = null,
        val background: String? = null
    )

    data class State(
        val enabled: Boolean = false,
        val theme: Theme = Theme(),
        val texts: Map<String, String> = emptyMap(),
        val versionName: String? = null,
        val features: Map<String, Boolean> = emptyMap(),
        val actions: List<Action> = emptyList(),
        val modules: List<Module> = emptyList(),
        val updatedAt: String? = null,
        val visibility: Map<String, Boolean> = emptyMap(),   // v207：view id/tag -> true显示/false隐藏（功能删除即隐藏）
        val viewStyles: Map<String, ViewStyle> = emptyMap()  // v207：view id/tag -> 精确颜色
    )

    /** 内置默认强调色（与历史玻璃主题一致）。 */
    const val DEFAULT_ACCENT = 0xFF00D8FF.toInt()
    const val DEFAULT_ACCENT_DEEP = 0xFF00C2D9.toInt()

    /** 远程配置文件地址（仓库默认分支 main）。 */
    const val CONFIG_URL =
        "https://raw.githubusercontent.com/baigao110/daojishi/main/ui_config.json"

    private const val PREF = "remote_skin_v1"
    private const val K_REMOTE = "remote_json"
    private const val K_LOCAL = "local_json"
    private const val K_REMOTE_AT = "remote_at"

    private val main = Handler(Looper.getMainLooper())
    private val fetching = AtomicBoolean(false)
    /** v207：记住 TextView 的原始文字，避免按原文匹配时「替换一次后就失忆」。 */
    private val originalTexts = java.util.WeakHashMap<TextView, String>()

    /** 当前生效的完整配置 JSON（local 优先，否则 remote）。 */
    @Volatile
    private var json: JSONObject = JSONObject()
    @Volatile
    private var state: State = State()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------- 初始化 ----------------
    /** 进程启动时调用：先读缓存立即生效，再异步拉最新。 */
    fun init(ctx: Context) {
        val p = prefs(ctx)
        val raw = p.getString(K_LOCAL, null)?.takeIf { it.isNotBlank() }
            ?: p.getString(K_REMOTE, null)?.takeIf { it.isNotBlank() }
        if (raw != null) applyJson(raw)
        refresh(ctx, null)
    }

    /** 异步拉取远程配置；成功写缓存并即时生效。callback 在主线程回调 (ok, msg)。 */
    fun refresh(ctx: Context, callback: ((Boolean, String) -> Unit)?) {
        if (!fetching.compareAndSet(false, true)) {
            callback?.invoke(true, "正在拉取，请稍候")
            return
        }
        Thread {
            var ok = false
            var msg = ""
            try {
                val conn = URL(CONFIG_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "countdown-skin/1.0")
                val code = conn.responseCode
                if (code == 200) {
                    val text = conn.inputStream.use { ins ->
                        BufferedReader(InputStreamReader(ins, "UTF-8")).use { r ->
                            val sb = StringBuilder()
                            var line: String?
                            while (r.readLine().also { line = it } != null) sb.append(line).append('\n')
                            sb.toString()
                        }
                    }
                    // 先解析校验，坏了就别覆盖缓存
                    parse(JSONObject(text))
                    prefs(ctx).edit()
                        .putString(K_REMOTE, text)
                        .putLong(K_REMOTE_AT, System.currentTimeMillis())
                        .apply()
                    // 本地没覆盖时才把远程结果切到内存
                    if (prefs(ctx).getString(K_LOCAL, null).isNullOrBlank()) {
                        applyJson(text)
                    }
                    ok = true
                    msg = "已从 GitHub 同步最新界面配置"
                } else {
                    msg = "同步失败：HTTP $code（继续用缓存）"
                }
                conn.disconnect()
            } catch (t: Throwable) {
                msg = "同步失败：${t.javaClass.simpleName}（继续用缓存）"
            } finally {
                fetching.set(false)
                val o = ok; val m = msg
                main.post {
                    if (o) CountdownApp.applyToCurrent()
                    callback?.invoke(o, m)
                }
            }
        }.apply { isDaemon = true; name = "remote-skin-fetch" }.start()
    }

    /** 把一份 JSON 文本作为本地覆盖写入，立即生效。传空串表示清除本地覆盖、回到远程。 */
    fun saveLocal(ctx: Context, text: String) {
        val e = prefs(ctx).edit()
        if (text.isBlank()) e.remove(K_LOCAL) else e.putString(K_LOCAL, text)
        e.apply()
        val raw = text.takeIf { it.isNotBlank() }
            ?: prefs(ctx).getString(K_REMOTE, null)?.takeIf { it.isNotBlank() }
        if (raw != null) applyJson(raw) else {
            json = JSONObject(); state = State()
        }
    }

    fun hasLocal(ctx: Context): Boolean =
        !prefs(ctx).getString(K_LOCAL, null).isNullOrBlank()

    /** 当前生效的完整 JSON（编辑器载入用）。 */
    fun currentJson(): String = json.toString(2)

    fun remoteJson(ctx: Context): String =
        prefs(ctx).getString(K_REMOTE, null) ?: ""

    fun lastSyncText(ctx: Context): String {
        val t = prefs(ctx).getLong(K_REMOTE_AT, 0L)
        return if (t <= 0L) "尚未同步过" else
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(t))
    }

    // ---------------- 解析 ----------------
    private fun applyJson(raw: String) {
        try {
            val o = JSONObject(raw)
            json = o
            state = parse(o)
        } catch (t: Throwable) {
            // 配置损坏：保持上一次的 state，绝不炸界面
        }
    }

    private fun parse(o: JSONObject): State {
        val enabled = o.optBoolean("enabled", false)
        val th = o.optJSONObject("theme")
        val theme = Theme(
            accent = th?.optString("accent")?.takeIf { it.isNotBlank() && it.startsWith("#") },
            accentDeep = th?.optString("accentDeep")?.takeIf { it.isNotBlank() && it.startsWith("#") },
            bgTop = th?.optString("bgTop")?.takeIf { it.isNotBlank() && it.startsWith("#") },
            bgCenter = th?.optString("bgCenter")?.takeIf { it.isNotBlank() && it.startsWith("#") },
            bgBottom = th?.optString("bgBottom")?.takeIf { it.isNotBlank() && it.startsWith("#") },
            text = th?.optString("text")?.takeIf { it.isNotBlank() && it.startsWith("#") }
        )
        val texts = HashMap<String, String>()
        o.optJSONObject("texts")?.let { jo ->
            val it = jo.keys()
            while (it.hasNext()) {
                val k = it.next()
                texts[k] = jo.optString(k)
            }
        }
        val features = HashMap<String, Boolean>()
        o.optJSONObject("features")?.let { jo ->
            val it = jo.keys()
            while (it.hasNext()) {
                val k = it.next()
                features[k] = jo.optBoolean(k)
            }
        }
        val actions = ArrayList<Action>()
        o.optJSONArray("actions")?.let { ja ->
            for (i in 0 until ja.length()) {
                val a = ja.optJSONObject(i) ?: continue
                val label = a.optString("label")
                val type = a.optString("type")
                val value = a.optString("value")
                if (label.isNotBlank() && type.isNotBlank()) actions.add(Action(label, type, value))
            }
        }
        val modules = ArrayList<Module>()
        o.optJSONArray("modules")?.let { ja ->
            for (i in 0 until ja.length()) {
                val m = ja.optJSONObject(i) ?: continue
                val name = m.optString("name")
                val dex = m.optString("dexUrl")
                val cls = m.optString("className")
                if (name.isNotBlank() && dex.isNotBlank() && cls.isNotBlank())
                    modules.add(Module(name, dex, cls, m.optBoolean("enabled", true)))
            }
        }
        val visibility = HashMap<String, Boolean>()
        o.optJSONObject("visibility")?.let { jo ->
            val it = jo.keys()
            while (it.hasNext()) {
                val k = it.next()
                visibility[k] = jo.optBoolean(k)
            }
        }
        val viewStyles = HashMap<String, ViewStyle>()
        o.optJSONObject("viewStyles")?.let { jo ->
            val it = jo.keys()
            while (it.hasNext()) {
                val k = it.next()
                val s = jo.optJSONObject(k) ?: continue
                viewStyles[k] = ViewStyle(
                    textColor = s.optString("textColor").takeIf { it.isNotBlank() && it.startsWith("#") },
                    background = s.optString("background").takeIf { it.isNotBlank() && it.startsWith("#") }
                )
            }
        }
        return State(
            enabled = enabled,
            theme = theme,
            texts = texts,
            versionName = o.optString("versionName").takeIf { it.isNotBlank() },
            features = features,
            actions = actions,
            modules = modules,
            updatedAt = o.optString("updatedAt").takeIf { it.isNotBlank() },
            visibility = visibility,
            viewStyles = viewStyles
        )
    }

    // ---------------- 访问器（全部带默认值） ----------------
    fun enabled(): Boolean = state.enabled

    fun accent(): Int = parseColor(state.theme.accent, DEFAULT_ACCENT)

    fun accentDeep(): Int = parseColor(state.theme.accentDeep, DEFAULT_ACCENT_DEEP)

    fun bgTop(): Int? = state.theme.bgTop?.let { parseColor(it, 0) }

    fun bgCenter(): Int? = state.theme.bgCenter?.let { parseColor(it, 0) }

    fun bgBottom(): Int? = state.theme.bgBottom?.let { parseColor(it, 0) }

    fun textColor(): Int? = state.theme.text?.let { parseColor(it, 0) }

    fun text(key: String, default: String): String =
        if (!state.enabled) default else state.texts[key] ?: default

    fun versionName(real: String): String =
        if (state.enabled) state.versionName ?: real else real

    fun feature(key: String, default: Boolean): Boolean =
        if (!state.enabled) default else state.features[key] ?: default

    fun actions(): List<Action> = if (state.enabled) state.actions else emptyList()

    fun modules(): List<Module> = if (state.enabled) state.modules else emptyList()

    /** v207：某控件是否显示（配置里没写就返回 null 表示保持原样）。 */
    fun visibilityOf(key: String): Boolean? =
        if (state.enabled) state.visibility[key] else null

    /** v207：某控件的精确样式覆盖。 */
    fun viewStyleOf(key: String): ViewStyle? =
        if (state.enabled) state.viewStyles[key] else null

    /** 原文匹配：配置 texts 里也可用「控件当前文字」作 key，覆盖没有 id/tag 的标题。 */
    fun textByOriginal(original: String): String? =
        if (state.enabled) state.texts[original] else null

    private var lastFetchAt = 0L
    /** v207：界面 resume 时调用，距上次拉取超过 10 秒才真拉，做到「打开即同步」又不刷屏。 */
    fun refreshIfStale(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastFetchAt < 10_000L) return
        lastFetchAt = now
        refresh(ctx, null)
    }

    private fun parseColor(s: String?, fallback: Int): Int = try {
        if (s.isNullOrBlank()) fallback else Color.parseColor(s)
    } catch (t: Throwable) { fallback }

    // ---------------- 全局换肤 ----------------
    /**
     * 遍历 Activity 的整个 View 树应用主题色与文案覆盖。
     * 只改「当前就是青色系」的文字 / 背景，补记琥珀、正常青绿等其它颜色一律不碰，
     * 从而做到零配置 / 缺字段时界面完全不变。
     */
    fun apply(activity: Activity) {
        if (!state.enabled) return
        activity.window?.decorView?.post {
            val root = activity.window?.decorView as? ViewGroup ?: return@post
            if (activity.isFinishing) return@post
            val accent = accent()
            applyView(root, accent)
        }
    }

    /** 控件的配置 key：优先 tag，其次资源 id 名。 */
    private fun keyOf(v: View): String? =
        v.tag as? String ?: runCatching {
            if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else null
        }.getOrNull()

    private fun applyView(v: View, accent: Int) {
        val key = keyOf(v)
        // v207：功能显隐 —— 配置里按 id/tag 把某控件设为 false 即「删除」该功能
        if (key != null) {
            visibilityOf(key)?.let { show ->
                v.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
        if (v is TextView) {
            // 文案覆盖：id/tag 命中优先；否则用当前文字原文匹配（覆盖无 id 的标题）
            val replaced = state.texts[key]?.let { if (it.isNotBlank()) { v.text = it; true } else false } ?: false
            if (!replaced) {
                val original = originalTexts.getOrPut(v) { v.text?.toString().orEmpty() }
                if (original.isNotBlank()) textByOriginal(original)?.let {
                    if (it.isNotBlank()) v.text = it
                }
            }
            // v207：按控件精确设文字色（优先）> 青色系跟随主题
            val style = key?.let { viewStyleOf(it) }
            val exact = style?.textColor?.let { parseColor(it, 0) }
            if (exact != null) {
                v.setTextColor(exact)
            } else {
                val c = v.currentTextColor
                if (isCyanFamily(c)) v.setTextColor(accent)
            }
        }
        // 背景：v207 按控件精确设色（优先）> 青色系跟随主题
        val style = key?.let { viewStyleOf(it) }
        val exactBg = style?.background?.let { parseColor(it, 0) }
        if (exactBg != null && v !is GlassBackdrop) {
            v.setBackgroundColor(exactBg)
        } else {
            (v.background as? GradientDrawable)?.let { gd ->
                val c = gd.color?.defaultColor
                if (c != null && isCyanFamily(c)) gd.setColor(accent)
            }
        }
        if (v is GlassBackdrop) v.applyTheme(accent)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) applyView(v.getChildAt(i), accent)
        }
    }

    /** 青色系判定：低红、高绿高蓝（覆盖 #00FFFF / #4DFBFF / #00D8FF 等历史青色）。 */
    private fun isCyanFamily(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return r < 130 && g > 150 && b > 150
    }

    // ---------------- v207：所有界面通用的动态功能栏 ----------------
    /**
     * 把远程 actions + 已加载 dex 模块渲染成一行按钮，注入到任意 Activity 的滚动区上方。
     * 先清旧栏再建新栏 —— 所以 GitHub 上删掉一个 action/module 后，界面重进即不再出现。
     * 没有 ScrollView 的界面（如编辑器 / 更新页）自动跳过，不干扰它们。
     */
    fun injectDynamicBar(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        decor.post {
            if (activity.isFinishing) return@post
            clearBars(decor)
            if (!state.enabled) return@post
            val acts = actions()
            val mods = DynamicFeature.loadedModules()
            if (acts.isEmpty() && mods.isEmpty()) return@post
            val content = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return@post
            val hostInfo = findHost(content) ?: return@post
            val host = hostInfo.first
            val anchor = hostInfo.second
            val density = activity.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val bar = LinearLayout(activity).apply {
                tag = "dynbar"
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            fun addChip(label: String, click: () -> Unit) {
                val cb = Button(activity).apply {
                    text = label
                    textSize = 12f
                    isAllCaps = false
                    setTextColor(0xFF001018.toInt())
                    setBackgroundResource(R.drawable.fab_accent)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(0, 0, dp(4), 0)
                    }
                }
                cb.setOnClickListener { click() }
                bar.addView(cb)
            }
            for (a in acts) addChip(a.label) { DynamicFeature.runAction(activity, a) }
            for ((name, m) in mods) addChip(m.label) {
                val t = DynamicFeature.invokeModule(activity, name)
                if (t.isNotBlank()) Toast.makeText(activity, t, Toast.LENGTH_LONG).show()
            }
            val idx = if (host.indexOfChild(anchor) >= 0) host.indexOfChild(anchor) else 0
            host.addView(bar, idx.coerceAtMost(host.childCount))
        }
    }

    private fun clearBars(v: View) {
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                val ch = v.getChildAt(i)
                if (ch.tag as? String == "dynbar") v.removeViewAt(i) else clearBars(ch)
            }
        }
    }

    /** 找到第一个 ScrollView 的父容器作为动态栏宿主，返回 (父容器, ScrollView 锚点)。 */
    private fun findHost(root: ViewGroup): Pair<ViewGroup, View>? {
        for (i in 0 until root.childCount) {
            val ch = root.getChildAt(i)
            if (ch is ScrollView && ch.parent is ViewGroup) return Pair(ch.parent as ViewGroup, ch)
        }
        for (i in 0 until root.childCount) {
            val ch = root.getChildAt(i)
            if (ch is ViewGroup) {
                val r = findHost(ch)
                if (r != null) return r
            }
        }
        return null
    }
}
