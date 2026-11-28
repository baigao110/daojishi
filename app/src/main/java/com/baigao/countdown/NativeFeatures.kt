package com.baigao.countdown

import android.app.Activity
import android.content.Intent
import android.net.Uri

/**
 * v1.0.0.70：原生功能能力表。
 *
 * 把 APK 内真实存在的原生功能登记成稳定 key，供 GitHub 配置（ui_config.json 的 nativeActions）
 * 按 key 增删「入口」——打开工具即同步增删，不用重装 APK。
 *
 * 边界说明：这里登记的是「APK 里本来就有的功能」（能力是 APK 自带的，改不了）；
 * 真正写死的是「哪些功能显示成按钮」，现在这部分交给 GitHub 配置决定 —— 这就是「原生功能同步入口」。
 * 若要在 GitHub 上新增 APK 里根本没有的全新功能代码，仍走 DynamicFeature 的远程 dex 模块。
 */
object NativeFeatures {

    data class Entry(val key: String, val label: String)

    /** 原生能力表：key 保持稳定，配置里只能引用这些 key（写进 _readme 供查阅）。 */
    private val REGISTRY = listOf(
        Entry("about", "关于 / 设置"),
        Entry("medicine", "吃药提醒"),
        Entry("calendar", "吃药日历"),
        Entry("add", "添加倒计时"),
        Entry("config", "界面配置编辑器"),
        Entry("settings", "应用系统设置")
    )

    /** 全部可用原生功能 key（配置编辑器 / 说明用）。 */
    fun keys(): List<String> = REGISTRY.map { it.key }

    /** 全部能力（key + 默认名），供配置参考。 */
    fun all(): List<Entry> = REGISTRY

    /** 默认显示名；未登记的 key 返回 null。 */
    fun labelOf(key: String): String? =
        REGISTRY.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }?.label

    private fun isKnown(key: String): Boolean = labelOf(key) != null

    /**
     * 执行一个原生功能入口。返回给用户看的文本（空串表示已自行跳转/无需提示）。
     * 只做 APK 内已有功能的跳转，失败一律吞异常，绝不影响主流程。
     */
    fun invoke(activity: Activity, key: String): String {
        val k = key.trim().lowercase()
        if (!isKnown(k)) return "没有这个原生功能：$key"
        return try {
            val cls = when (k) {
                "about" -> AboutActivity::class.java
                "medicine" -> MedicineReminderActivity::class.java
                "calendar" -> MedicineCalendarActivity::class.java
                "add" -> AddEditActivity::class.java
                "config" -> ConfigEditorActivity::class.java
                else -> null
            }
            if (cls != null) {
                activity.startActivity(Intent(activity, cls))
                ""
            } else if (k == "settings") {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:" + activity.packageName)
                activity.startActivity(intent)
                ""
            } else {
                "这个原生功能暂不可用：$key"
            }
        } catch (t: Throwable) {
            "打不开这个功能：${t.javaClass.simpleName}"
        }
    }
}
