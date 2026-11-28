package com.baigao.countdown

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * v1.0.0.66：应用入口。
 *
 * 统一做三件动态化初始化：
 * 1. 读缓存并异步拉取 GitHub 上的 ui_config.json（远程界面 / 功能配置）；
 * 2. 按配置异步加载远程 dex 功能模块；
 * 3. 注册 Activity 生命周期钩子，每个界面 resume 后自动换肤 —— 做到「退出重进即同步改变」。
 *
 * 初始化全程吞异常、不阻塞启动；没配远程配置时与旧版行为完全一致。
 */
class CountdownApp : Application() {

    companion object {
        /** 当前处于前台的 Activity，供配置拉取成功后即时刷新界面。 */
        @Volatile
        var current: Activity? = null

        /** 远程配置变更后立即对当前界面重新换肤 / 重建动态按钮。 */
        fun applyToCurrent() {
            val a = current ?: return
            main.post {
                try { RemoteSkin.apply(a) } catch (_: Throwable) { }
                try { (a as? MainActivity)?.refreshDynamicBar() } catch (_: Throwable) { }
            }
        }

        private val main = android.os.Handler(android.os.Looper.getMainLooper())
    }

    override fun onCreate() {
        super.onCreate()
        try { RemoteSkin.init(this) } catch (t: Throwable) { }
        try { DynamicFeature.init(this) } catch (t: Throwable) { }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, s: Bundle?) { }
            override fun onActivityStarted(a: Activity) { }
            override fun onActivityResumed(a: Activity) {
                current = a
                try { RemoteSkin.apply(a) } catch (t: Throwable) { }
            }
            override fun onActivityPaused(a: Activity) {
                if (current === a) current = null
            }
            override fun onActivityStopped(a: Activity) { }
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) { }
            override fun onActivityDestroyed(a: Activity) { }
        })
    }
}
