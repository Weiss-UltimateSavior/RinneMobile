package com.apps.HDModel

import android.app.Activity
import android.app.LocalActivityManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity

/**
 * HD 嵌入 Activity 宿主（重构计划 9.9 阶段 105，§8:327 准备性收敛）。
 *
 * 收拢 6 个 HD Fragment 的 LocalActivityManager 创建/生命周期转发/嵌入销毁，
 * 使废弃 API 使用收敛到单点，为后续迁移子 Fragment 提供唯一改动入口。
 * 采用组合而非继承：HdHome/HdAccount/HdProfile/HdManage 继承不同 Launcher* 基类，
 * 无法插入中间基类（阶段 99 评估）。调用方仍负责 HdPageMotion 进出场动画编排，
 * 本类只承载 LocalActivityManager 状态与生命周期转发。
 */
@Suppress("DEPRECATION")
internal class HdEmbeddedActivityHost(private val activity: FragmentActivity) {

    private var manager: LocalActivityManager? = null
    private var embeddedActivityId: String? = null

    /** Fragment onViewCreated 时初始化，转发 onCreate(savedInstanceState)。 */
    fun onCreate(savedInstanceState: Bundle?) {
        manager = LocalActivityManager(activity, false).apply {
            dispatchCreate(savedInstanceState)
        }
    }

    fun onResume() {
        manager?.dispatchResume()
    }

    fun onPause() {
        manager?.dispatchPause(activity.isFinishing)
    }

    fun onStop() {
        manager?.dispatchStop()
    }

    /** Fragment onDestroyView 时调用，销毁嵌入 Activity 并释放引用。 */
    fun onDestroyView() {
        manager?.dispatchDestroy(activity.isFinishing)
        manager = null
        embeddedActivityId = null
    }

    /**
     * 启动并嵌入目标 Activity，返回其根视图。
     * 宿主未就绪或启动失败返回 null（调用方回退默认行为）。
     */
    fun start(id: String, intent: Intent): View? {
        val m = manager ?: return null
        embeddedActivityId = id
        return m.startActivity(id, intent)?.decorView
    }

    /**
     * 校验并开始关闭嵌入 Activity。
     * 通过守卫后清空当前 id 并返回待销毁的 id；宿主未就绪 / id 缺失 /
     * child 与当前嵌入不一致时返回 null（调用方不执行关闭）。
     */
    fun beginClose(child: Activity?): String? {
        val m = manager ?: return null
        val id = embeddedActivityId ?: return null
        val current = m.currentActivity
        if (child != null && current != null && current !== child) return null
        embeddedActivityId = null
        return id
    }

    /** 销毁指定 id 的嵌入 Activity（动画结束后调用）。 */
    fun destroy(id: String) {
        if (id == embeddedActivityId) embeddedActivityId = null
        manager?.destroyActivity(id, true)
    }
}
