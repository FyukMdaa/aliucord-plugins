package com.github.fyukmdaa

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.databinding.WidgetChatListActionsBinding
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.lytefast.flexinput.R

@AliucordPlugin
class TranslatePlugin : Plugin() {

    init {
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(settings)
    }

    private data class TranslatedEntry(
        val original: String,
        val translated: String,
        var showingTranslation: Boolean = true
    )
    private val translatedMessages = mutableMapOf<Long, TranslatedEntry>()
    private val autoChannels = mutableSetOf<Long>()

    private fun targetLang() = settings.getString("targetLang", "ja")

    override fun start(ctx: Context) {
        val buttonId = View.generateViewId()
        val autoButtonId = View.generateViewId()

        val messageContextMenu = WidgetChatListActions::class.java
        val getBinding = messageContextMenu
            .getDeclaredMethod("getBinding")
            .apply { isAccessible = true }

        // ── 1. configureUI: ボタンのクリックリスナーを設定 ──────────────────
        // このパッチは、モデルデータが確定した後に呼ばれるため、message.id などが安全に取得できる
        patcher.patch(
            messageContextMenu.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            Hook { cf ->
                val menu = cf.thisObject as WidgetChatListActions
                val binding = getBinding.invoke(menu) as WidgetChatListActionsBinding
                val message = (cf.args[0] as WidgetChatListActions.Model).message

                // 🔽 翻訳ボタン: リスナー設定
                binding.a.findViewById<TextView>(buttonId)?.setOnClickListener {
                    val entry = translatedMessages[message.id]
                    if (entry == null) {
                        Utils.threadPool.execute {
                            try {
                                val content = message.content ?: return@execute
                                val result = Translator.translate(content, targetLang())
                                if (result.isBlank()) return@execute
                                translatedMessages[message.id] = TranslatedEntry(
                                    original = content,
                                    translated = result
                                )
                                Utils.mainThread.post {
                                    showTranslation(menu.requireContext(), content, result)
                                    menu.dismiss()
                                }
                            } catch (e: Exception) {
                                Utils.mainThread.post {
                                    Utils.showToast("Translate error: ${e.message}")
                                }
                            }
                        }
                    } else {
                        entry.showingTranslation = !entry.showingTranslation
                        showTranslation(menu.requireContext(), entry.original, entry.translated)
                        menu.dismiss()
                    }
                }

                // 🔽 全体翻訳ボタン: リスナー設定
                binding.a.findViewById<TextView>(autoButtonId)?.setOnClickListener {
                    if (message.channelId in autoChannels) {
                        autoChannels.remove(message.channelId)
                        Utils.showToast("全体翻訳をOFFにしました")
                    } else {
                        autoChannels.add(message.channelId)
                        Utils.showToast("全体翻訳をONにしました")
                    }
                    menu.dismiss()
                }
            }
        )

        // ── 2. onViewCreated: 実際のビューにボタンを追加 ───────────────────
        patcher.patch(
            messageContextMenu,
            "onViewCreated",
            arrayOf(View::class.java, Bundle::class.java),
            Hook { cf ->
                val linearLayout = (cf.args[0] as? NestedScrollView)?.getChildAt(0) as? LinearLayout
                    ?: return@Hook
                val ctx2 = linearLayout.context

                // 🔽 messageId 取得（Kotlin 合成アクセサ）
                val messageId = try {
                    WidgetChatListActions.`access$getMessageId$p`(cf.thisObject as WidgetChatListActions)
                } catch (_: Throwable) { return@Hook }

                // 🔽 channelId 取得（合成アクセサ、失敗時は 0L にフォールバック）
                val channelId = try {
                    WidgetChatListActions.`access$getChannelId$p`(cf.thisObject as WidgetChatListActions)
                } catch (_: Throwable) { 0L }

                // 🔽 翻訳ボタン: 重複追加防止 + テキスト更新
                val translateBtn = linearLayout.findViewById<TextView>(buttonId) ?: TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    id = buttonId
                    linearLayout.addView(this)
                }
                val entry = translatedMessages[messageId]
                translateBtn.text = when {
                    entry == null -> "🌐 翻訳"
                    entry.showingTranslation -> "🌐 原文を表示"
                    else -> "🌐 訳文を表示"
                }

                // 🔽 全体翻訳ボタン: 重複追加防止 + テキスト更新
                val autoBtn = linearLayout.findViewById<TextView>(autoButtonId) ?: TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    id = autoButtonId
                    linearLayout.addView(this)
                }
                autoBtn.text = if (channelId in autoChannels) "🌐 全体翻訳 OFF" else "🌐 全体翻訳 ON"
            }
        )
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()

    private fun showTranslation(ctx: Context, original: String, translated: String) {
        android.app.AlertDialog.Builder(ctx)
            .setTitle("翻訳")
            .setMessage("$original\n\n---\n\n$translated")
            .setPositiveButton("閉じる", null)
            .show()
    }
}
