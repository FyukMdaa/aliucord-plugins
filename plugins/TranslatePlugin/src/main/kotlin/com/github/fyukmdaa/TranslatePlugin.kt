package com.fyukmdaa.translateplugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.DimenUtils.dp
import com.discord.models.message.Message
import com.discord.widgets.chat.list.actions.WidgetChatListActions

@AliucordPlugin
class TranslatePlugin : Plugin() {

    init {
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(settings)
    }

    // チャンネルIDごとの全体翻訳ON状態
    private val autoChannels = mutableSetOf<Long>()

    private fun targetLang() = settings.getString("targetLang", "ja")

    override fun start(ctx: Context) {

        // WidgetChatListActions が表示されたときにボタンを追加
        patcher.patch(
            WidgetChatListActions::class.java,
            "onViewCreated",
            arrayOf(View::class.java, android.os.Bundle::class.java),
            Hook { cf ->
                val actions = cf.thisObject as WidgetChatListActions

                // messageフィールドをリフレクションで取得（型で検索）
                val message = WidgetChatListActions::class.java.declaredFields
                    .firstOrNull { it.type == Message::class.java }
                    ?.also { it.isAccessible = true }
                    ?.get(actions) as? Message ?: return@Hook

                val content = message.content
                if (content.isNullOrBlank()) return@Hook

                val channelId = message.channelId

                // ボタンを追加するコンテナを取得
                // WidgetChatListActions のビューは ScrollView > LinearLayout 構造
                val rootView = actions.requireView() as? ViewGroup ?: return@Hook
                val container = findLinearLayout(rootView) ?: rootView

                // 「翻訳」ボタン追加
                container.addView(makeButton(ctx, "Translate message") {
                    actions.dismiss()
                    translateAndShow(ctx, content, targetLang())
                })

                // 「全体翻訳 ON/OFF」ボタン追加
                val isAuto = channelId in autoChannels
                container.addView(makeButton(ctx, if (isAuto) "Disable Full Translate" else "Enable Full Translate") {
                    if (channelId in autoChannels) {
                        autoChannels.remove(channelId)
                        Utils.showToast("Disabled Full Translate")
                    } else {
                        autoChannels.add(channelId)
                        Utils.showToast("Enabled Full Translate")
                    }
                    actions.dismiss()
                })
            }
        )
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()

    // ── ヘルパー ──────────────────────────────────────────────────────

    private fun translateAndShow(ctx: Context, text: String, lang: String) {
        Thread {
            try {
                val translated = Translator.translate(text, lang)
                Handler(Looper.getMainLooper()).post {
                    AlertDialog.Builder(ctx)
                        .setTitle("Translate")
                        .setMessage("$text\n\n---\n\n$translated")
                        .setPositiveButton("close", null)
                        .show()
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Utils.showToast("Translate error: ${e.message}")
                }
            }
        }.start()
    }

    private fun makeButton(ctx: Context, label: String, onClick: () -> Unit): TextView {
        return TextView(ctx, null, 0, com.google.android.material.R.style.Widget_MaterialComponents_Button_TextButton).apply {
            text = label
            val p = 16.dp
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }
    }

    /** ViewGroupを再帰的に探索して最初のLinearLayoutを返す */
    private fun findLinearLayout(view: ViewGroup): LinearLayout? {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            if (child is LinearLayout) return child
            if (child is ViewGroup) findLinearLayout(child)?.let { return it }
        }
        return null
    }
}
