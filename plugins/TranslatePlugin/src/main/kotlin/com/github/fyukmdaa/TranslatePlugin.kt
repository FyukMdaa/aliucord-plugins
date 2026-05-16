package com.fyukmdaa.translateplugin

import android.content.Context
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.SettingsAPI
import com.aliucord.entities.MessageEmbeds
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.DimenUtils
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.lytefast.flexinput.R

@AliucordPlugin
class TranslatePlugin : Plugin() {

    init {
        // 設定画面を登録
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(settings)
    }

    // チャンネルIDごとの「全体翻訳ON」状態を保持
    private val autoChannels = mutableSetOf<Long>()

    override fun start(ctx: Context) {
        val targetLang get() = settings.getString("targetLang", "ja")

        // ── 1. メッセージ長押しメニューに「翻訳」を追加 ──────────────────
        patcher.patch(
            WidgetChatListActions::class.java.getDeclaredMethod("onViewCreated",
                android.view.View::class.java, android.os.Bundle::class.java),
            Hook { cf ->
                val actions = cf.thisObject as WidgetChatListActions
                val message: Message = WidgetChatListActions::class.java
                    .getDeclaredField("message").apply { isAccessible = true }
                    .get(actions) as? Message ?: return@Hook

                val layout = actions.requireView() as? android.widget.LinearLayout ?: return@Hook

                // 「翻訳」ボタン
                addActionButton(layout, actions.requireContext(), "翻訳") {
                    val content = message.content ?: return@addActionButton
                    translateAndShow(actions.requireContext(), content, targetLang)
                }

                // 「全体翻訳 ON/OFF」ボタン
                val channelId = message.channelId
                val autoLabel = if (channelId in autoChannels) "全体翻訳 OFF" else "全体翻訳 ON"
                addActionButton(layout, actions.requireContext(), autoLabel) {
                    if (channelId in autoChannels) {
                        autoChannels.remove(channelId)
                        showToast(actions.requireContext(), "全体翻訳をOFFにしました")
                    } else {
                        autoChannels.add(channelId)
                        showToast(actions.requireContext(), "全体翻訳をONにしました")
                    }
                    actions.dismiss()
                }
            }
        )

        // ── 2. メッセージ表示時に全体翻訳モードなら原文+訳文に書き換え ────
        // ChatListAdapter の bindMessage あたりをフック
        patcher.patch(
            com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage::class.java
                .getDeclaredMethod("onConfigure", Int::class.java, Any::class.java),
            Hook { cf ->
                val channelId = StoreStream.getChannelsSelected().selectedChannelId
                if (channelId !in autoChannels) return@Hook

                val item = cf.args[1] as? com.discord.widgets.chat.list.entries.MessageEntry
                    ?: return@Hook
                val message = item.message
                val original = message.content ?: return@Hook
                if (original.isBlank()) return@Hook

                // 既に翻訳済み（"---"区切り）ならスキップ
                if (original.contains("\n---\n")) return@Hook

                Thread {
                    try {
                        val translated = Translator.translate(original, targetLang)
                        if (translated == original) return@Thread
                        // UIスレッドで表示を更新
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            // Viewのテキストを直接書き換え（表示のみ、送信内容は変わらない）
                            val textView = (cf.thisObject as? android.view.View)
                                ?.findViewById<android.widget.TextView>(
                                    com.discord.R.id.chat_list_item_text
                                ) ?: return@post
                            textView.text = "$original\n---\n$translated"
                        }
                    } catch (_: Exception) {}
                }.start()
            }
        )
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()

    // ── ヘルパー ────────────────────────────────────────────────────────

    private fun translateAndShow(ctx: Context, text: String, lang: String) {
        Thread {
            try {
                val translated = Translator.translate(text, lang)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("翻訳")
                        .setMessage("$text\n\n---\n\n$translated")
                        .setPositiveButton("閉じる", null)
                        .show()
                }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    showToast(ctx, "翻訳エラー: ${e.message}")
                }
            }
        }.start()
    }

    private fun addActionButton(
        layout: android.view.ViewGroup,
        ctx: Context,
        label: String,
        onClick: () -> Unit
    ) {
        layout.addView(android.widget.TextView(ctx).apply {
            text = label
            textSize = 16f
            setPadding(
                DimenUtils.dpToPx(16), DimenUtils.dpToPx(16),
                DimenUtils.dpToPx(16), DimenUtils.dpToPx(16)
            )
            setOnClickListener { onClick() }
        })
    }

    private fun showToast(ctx: Context, msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
