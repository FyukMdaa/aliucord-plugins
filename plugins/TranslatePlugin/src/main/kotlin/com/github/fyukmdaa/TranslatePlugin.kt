package com.fyukmdaa.translateplugin

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.DimenUtils
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.widgets.chat.list.actions.WidgetChatListActions

@AliucordPlugin
class TranslatePlugin : Plugin() {

    init {
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(settings)
    }

    private val autoChannels = mutableSetOf<Long>()

    private fun getTargetLang(): String = settings.getString("targetLang", "ja")

    override fun start(ctx: Context) {

        // ── 1. メッセージ長押しメニューに「翻訳」「全体翻訳」を追加 ──
        val onViewCreated = WidgetChatListActions::class.java.getDeclaredMethod(
            "onViewCreated", View::class.java, Bundle::class.java
        )
        patcher.patch(onViewCreated, Hook { cf ->
            val actions = cf.thisObject as WidgetChatListActions

            val messageField = WidgetChatListActions::class.java
                .declaredFields
                .firstOrNull { it.type == Message::class.java }
                ?: return@Hook
            messageField.isAccessible = true
            val message = messageField.get(actions) as? Message ?: return@Hook

            val layout = actions.requireView().findViewById<LinearLayout>(
                com.discord.R.id.dialog_chat_actions_root
            ) ?: (actions.requireView() as? ViewGroup) ?: return@Hook

            val channelId = message.channelId

            // 「翻訳」ボタン
            addActionButton(layout, actions.requireContext(), "🌐 翻訳") {
                val content = message.content
                if (content.isNullOrBlank()) return@addActionButton
                translateAndShow(actions.requireContext(), content, getTargetLang())
                actions.dismiss()
            }

            // 「全体翻訳 ON/OFF」ボタン
            val autoLabel = if (channelId in autoChannels) "🌐 全体翻訳 OFF" else "🌐 全体翻訳 ON"
            addActionButton(layout, actions.requireContext(), autoLabel) {
                if (channelId in autoChannels) {
                    autoChannels.remove(channelId)
                    Toast.makeText(actions.requireContext(), "全体翻訳をOFFにしました", Toast.LENGTH_SHORT).show()
                } else {
                    autoChannels.add(channelId)
                    Toast.makeText(actions.requireContext(), "全体翻訳をONにしました", Toast.LENGTH_SHORT).show()
                }
                actions.dismiss()
            }
        })

        // ── 2. 全体翻訳モード: メッセージのテキストViewに訳文を追加 ──
        val bindMethod = com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage::class.java
            .declaredMethods
            .firstOrNull { it.name == "onConfigure" }
            ?: return

        patcher.patch(bindMethod, Hook { cf ->
            val currentChannelId = try {
                StoreStream.getChannelsSelected().id
            } catch (_: Exception) {
                return@Hook
            }
            if (currentChannelId !in autoChannels) return@Hook

            // エントリからMessageを取得
            val entry = cf.args.getOrNull(1) ?: return@Hook
            val messageField = entry.javaClass.declaredFields
                .firstOrNull { it.type == Message::class.java }
                ?: return@Hook
            messageField.isAccessible = true
            val message = messageField.get(entry) as? Message ?: return@Hook

            val original = message.content
            if (original.isNullOrBlank()) return@Hook
            if (original.contains("\n---\n")) return@Hook  // 翻訳済みスキップ

            val itemView = cf.thisObject as? View ?: return@Hook

            Thread {
                try {
                    val translated = Translator.translate(original, getTargetLang())
                    if (translated.isBlank() || translated == original) return@Thread
                    Handler(Looper.getMainLooper()).post {
                        // テキストViewを探して書き換え
                        val textView = findTextView(itemView, original) ?: return@post
                        textView.text = "$original\n---\n$translated"
                    }
                } catch (_: Exception) {}
            }.start()
        })
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()

    // ── ヘルパー ──────────────────────────────────────────────────────

    private fun translateAndShow(ctx: Context, text: String, lang: String) {
        Thread {
            try {
                val translated = Translator.translate(text, lang)
                Handler(Looper.getMainLooper()).post {
                    AlertDialog.Builder(ctx)
                        .setTitle("翻訳")
                        .setMessage("$text\n\n---\n\n$translated")
                        .setPositiveButton("閉じる", null)
                        .show()
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(ctx, "翻訳エラー: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun addActionButton(
        layout: ViewGroup,
        ctx: Context,
        label: String,
        onClick: () -> Unit
    ) {
        layout.addView(TextView(ctx).apply {
            text = label
            textSize = 16f
            val p = DimenUtils.dpToPx(16)
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        })
    }

    /** ViewGroupを再帰的に探索し、指定テキストを持つTextViewを返す */
    private fun findTextView(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTextView(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }
}
