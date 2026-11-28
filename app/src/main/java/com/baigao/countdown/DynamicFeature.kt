package com.baigao.countdown

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v1.0.0.66：动态功能模块加载器。
 *
 * 让「新增功能代码」不用重装 APK 即可生效：远程 dex 下载到应用私有目录，
 * 用 DexClassLoader 加载，按约定类名反射实例化，调用 [DynamicModule] 契约。
 * 模块拿不到任何特权，只能用传入的 Context / Activity 做事，失败一律静默降级。
 *
 * 另提供「配置化动作」执行器：不写 dex 也能新增按钮行为（打开网页 / 提示 / 对话框 /
 * 拉起本 App 页面 / 调用已加载模块）。
 */

/** 远程模块必须实现的契约（模块编译时把本接口一份同签名声明带上即可，包名必须一致）。 */
interface DynamicModule {
    /** 模块标题，显示在按钮上。 */
    val label: String
    /** 模块被点击时调用；返回一段给用户看的文本。 */
    fun onInvoke(context: Context): String
}

object DynamicFeature {

    private val main = Handler(Looper.getMainLooper())
    private val loading = AtomicBoolean(false)

    /** 已加载的模块：模块名 -> 实例。进程内存活，重开 App 会按缓存 dex 重新加载。 */
    private val modules = HashMap<String, DynamicModule>()

    /** 进程启动时调用：按当前配置异步加载所有启用的远程模块（有本地 dex 缓存则离线也能加载）。 */
    fun init(ctx: Context) {
        loadAll(ctx, null)
    }

    fun loadAll(ctx: Context, callback: ((Int) -> Unit)?) {
        if (!loading.compareAndSet(false, true)) {
            callback?.invoke(modules.size)
            return
        }
        Thread {
            var n = 0
            try {
                for (m in RemoteSkin.modules()) {
                    if (!m.enabled) continue
                    val inst = loadModule(ctx, m)
                    if (inst != null) {
                        synchronized(modules) { modules[m.name] = inst }
                        n++
                    }
                }
            } catch (t: Throwable) {
                // 动态模块是增强项，绝不影响主流程
            } finally {
                loading.set(false)
                val count = n
                main.post {
                    // v1.0.0.66 试跑修复：dex 是异步加载的，加载完必须主动刷新界面，
                    // 否则主界面 onCreate 时模块还没就位、动态栏按钮永远出不来。
                    // applyToCurrent 内部会重建动态栏（refreshDynamicBar），并顺带重新换肤。
                    if (count > 0) CountdownApp.applyToCurrent()
                    callback?.invoke(count)
                }
            }
        }.apply { isDaemon = true; name = "dynamic-feature-load" }.start()
    }

    private fun loadModule(ctx: Context, m: RemoteSkin.Module): DynamicModule? {
        return try {
            val dir = File(ctx.applicationContext.filesDir, "dyn_modules")
            if (!dir.exists()) dir.mkdirs()
            val dex = File(dir, safeName(m.name) + ".dex")
            // 每次都尝试拉最新版本；失败则用缓存
            if (!downloadToFile(m.dexUrl, dex) && !dex.exists()) return null
            if (!dex.exists() || dex.length() < 64L) return null
            val out = File(ctx.applicationContext.filesDir, "dyn_odex")
            if (!out.exists()) out.mkdirs()
            val loader = DexClassLoader(
                dex.absolutePath, out.absolutePath, null,
                ctx.applicationContext.classLoader
            )
            val cls = loader.loadClass(m.className)
            if (!DynamicModule::class.java.isAssignableFrom(cls)) return null
            val obj = cls.getDeclaredConstructor().newInstance()
            obj as DynamicModule
        } catch (t: Throwable) {
            null
        }
    }

    private fun downloadToFile(url: String, dest: File): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) { conn.disconnect(); return false }
            conn.inputStream.use { ins ->
                FileOutputStream(dest).use { out -> ins.copyTo(out) }
            }
            conn.disconnect()
            true
        } catch (t: Throwable) { false }
    }

    private fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun loadedModules(): Map<String, DynamicModule> =
        synchronized(modules) { HashMap(modules) }

    fun invokeModule(ctx: Context, name: String): String {
        val m = synchronized(modules) { modules[name] }
        return if (m == null) "模块「$name」还没加载好" else
            try { m.onInvoke(ctx) } catch (t: Throwable) { "模块运行出错：${t.javaClass.simpleName}" }
    }

    // ---------------- 配置化动作 ----------------
    /** 执行一条配置化动作；返回给用户看的文本（部分动作返回空串表示已自行展示 UI）。 */
    fun runAction(activity: Activity, a: RemoteSkin.Action) {
        when (a.type.lowercase()) {
            "url" -> {
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.value)))
                } catch (t: Throwable) {
                    Toast.makeText(activity, "打不开这个链接", Toast.LENGTH_SHORT).show()
                }
            }
            "toast" -> Toast.makeText(activity, a.value, Toast.LENGTH_SHORT).show()
            "dialog" -> {
                if (activity.isFinishing) return
                try {
                    AlertDialog.Builder(activity)
                        .setTitle("提示")
                        .setMessage(a.value)
                        .setPositiveButton("知道了", null)
                        .show()
                } catch (t: Throwable) { }
            }
            "intent" -> {
                try {
                    // value 支持简名：about / medicine / calendar / add，或完整类名
                    val cls = when (a.value.lowercase()) {
                        "about" -> AboutActivity::class.java
                        "medicine" -> MedicineReminderActivity::class.java
                        "calendar" -> MedicineCalendarActivity::class.java
                        "add" -> AddEditActivity::class.java
                        "config" -> ConfigEditorActivity::class.java
                        else -> Class.forName(a.value)
                    }
                    activity.startActivity(Intent(activity, cls))
                } catch (t: Throwable) {
                    Toast.makeText(activity, "打不开这个页面", Toast.LENGTH_SHORT).show()
                }
            }
            "module" -> {
                val text = invokeModule(activity, a.value)
                if (text.isNotBlank()) Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
            }
            else -> Toast.makeText(activity, "未知动作：${a.type}", Toast.LENGTH_SHORT).show()
        }
    }
}
