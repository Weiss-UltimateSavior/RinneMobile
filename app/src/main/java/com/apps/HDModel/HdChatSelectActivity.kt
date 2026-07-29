package com.apps.HDModel

import android.content.Intent
import com.apps.chat.LauncherAiChatActivity
import com.apps.chat.LauncherChatSelectActivity

/** HD 聊天入口：把公共聊天和 AI 聊天继续交给个人页右侧容器。 */
class HdChatSelectActivity : LauncherChatSelectActivity() {
    override fun openChatDestination(intent: Intent) {
        val host = parent as? HdModeActivity
        val destinationId = if (
            intent.component?.className == LauncherAiChatActivity::class.java.name
        ) {
            "hd_ai_chat_${intent.getStringExtra(LauncherAiChatActivity.EXTRA_PERSONA).orEmpty()}"
        } else {
            "hd_public_chat"
        }
        if (host?.showProfileDetail(destinationId, intent) == true) return
        super.openChatDestination(intent)
    }
}
