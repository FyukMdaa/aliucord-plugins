package com.fyukmdaa.translateplugin

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

    // messageId -> 翻訳済みデータ
    private data class TranslatedEntry(
        val original: String,
        val translated: String,
        var showingTranslation: Boolean = true
    )
    private val translatedMessages = mutableMapOf<Long, TranslatedEntry>()

    // チャンネルIDごとの全体翻訳ON状態
    private val autoChannels = mutableSetOf<Long>()

    private fun targetLang() = settings.getString("targetLang", "ja")

    override fun start(ctx: Context) {
        val buttonId = View.generateViewId()
        val autoButtonId = View.generateViewId()

        val messageContextMenu = WidgetChatListActions::class.java
        val getBinding = messageContextMenu
            .getDeclaredMethod("getBinding")
            .apply { isAccessible = true }

        // ── 1. ボタンのクリックリスナーを設定 ──────────────────────────
        patcher.patch(
            messageContextMenu.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            Hook { cf ->
                val menu = cf.thisObject as WidgetChatListActions
                val binding = getBinding.invoke(menu) as WidgetChatListActionsBinding
                val message = (cf.args[0] as WidgetChatListActions.Model).message

                // 翻訳ボタン
                binding.a.findViewById<TextView>(buttonId)?.setOnClickListener {
                    val entry = translatedMessages[message.id]
                    if (entry == null) {
                        // 未翻訳 → 翻訳してrerenderは今回はダイアログ表示
                        Utils.threadPool.execute {
                            val result = Translator.translate(message.content ?: return@execute, targetLang())
                            if (result.isBlank()) return@execute
                            translatedMessages[message.id] = TranslatedEntry(
                                original = message.content ?: "",
                                translated = result
                            )
                            Utils.mainThread.post {
                                showTranslation(menu.requireContext(), message.content ?: "", result)
                                menu.dismiss()
                            }
                        }
                    } else {
                        // 翻訳済み → 原文/訳文を切り替えてダイアログ表示
                        entry.showingTranslation = !entry.showingTranslation
                        val display = if (entry.showingTranslation)
                            "${entry.original}\n\n---\n\n${entry.translated}"
                        else entry.original
                        showTranslation(menu.requireContext(), entry.original, entry.translated)
                        menu.dismiss()
                    }
                }

                // 全体翻訳ボタン
                val channelId = message.channelId
                binding.a.findViewById<TextView>(autoButtonId)?.setOnClickListener {
                    if (channelId in autoChannels) {
                        autoChannels.remove(channelId)
                        Utils.showToast("Disabled Full Translate")
                    } else {
                        autoChannels.add(channelId)
                        Utils.showToast("Enabled Full Translate")
                    }
                    menu.dismiss()
                }
            }
        )

        // ── 2. ビューにボタンを追加 ────────────────────────────────────
        patcher.patch(
            messageContextMenu,
            "onViewCreated",
            arrayOf(View::class.java, Bundle::class.java),
            Hook { cf ->
                val linearLayout = (cf.args[0] as NestedScrollView).getChildAt(0) as LinearLayout
                val ctx2 = linearLayout.context
                val messageId = WidgetChatListActions.`access$getMessageId$p`(
                    cf.thisObject as WidgetChatListActions
                )
                val channelId = try {
                    WidgetChatListActions.`access$getChannelId$p`(
                        cf.thisObject as WidgetChatListActions
                    )
                } catch (_: Throwable) { 0L }

                // 翻訳ボタン
                val entry = translatedMessages[messageId]
                linearLayout.addView(TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    id = buttonId
                    text = when {
                        entry == null -> "Translate message"
                        entry.showingTranslation -> "Show Original"
                        else -> "Show Translation"
                    }
                })

                // 全体翻訳ボタン
                linearLayout.addView(TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    id = autoButtonId
                    text = if (channelId in autoChannels) "Disable Full Translate" else "Enable Full Translate"
                })
            }
        )
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()

    private fun showTranslation(ctx: Context, original: String, translated: String) {
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Translate")
            .setMessage("$original\n\n---\n\n$translated")
            .setPositiveButton("close", null)
            .show()
    }
}
