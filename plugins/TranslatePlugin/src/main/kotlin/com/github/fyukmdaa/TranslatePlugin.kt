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

        // ── onViewCreated に一本化 ────────────────────────────────────
        patcher.patch(
            messageContextMenu,
            "onViewCreated",
            arrayOf(View::class.java, Bundle::class.java),
            Hook { cf ->
                val menu = cf.thisObject as WidgetChatListActions
                val linearLayout = (cf.args[0] as NestedScrollView).getChildAt(0) as LinearLayout
                val ctx2 = linearLayout.context

                val messageId = WidgetChatListActions.`access$getMessageId$p`(menu)
                val channelId = try {
                    WidgetChatListActions.`access$getChannelId$p`(menu)
                } catch (_: Throwable) { 0L }

                // モデルやメッセージコンテントを取得する代替アプローチ
                // コンテキストメニューのターゲットメッセージを保持するプロパティ経由（型や実装に依存）
                // もしくは既存の binding から message.content を引く形が取れない場合の安全策
                val messageModel = try {
                    val getModelMethod = messageContextMenu.getDeclaredMethod("getModel").apply { isAccessible = true }
                    getModelMethod.invoke(menu) as? WidgetChatListActions.Model
                } catch (_: Throwable) { null }

                val content = messageModel?.message?.content ?: ""

                // 翻訳ボタンの追加とリスナー設定
                val entry = translatedMessages[messageId]
                linearLayout.addView(TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    id = buttonId
                    text = when {
                        entry == null -> "Translate message"
                        entry.showingTranslation -> "Show Original"
                        else -> "Show Translation"
                    }
                    setOnClickListener {
                        val currentEntry = translatedMessages[messageId]
                        if (currentEntry == null) {
                            if (content.isBlank()) return@setOnClickListener
                            Utils.threadPool.execute {
                                val result = Translator.translate(content, targetLang())
                                if (result.isBlank()) return@execute
                                translatedMessages[messageId] = TranslatedEntry(
                                    original = content,
                                    translated = result
                                )
                                Utils.mainThread.post {
                                    showTranslation(menu.requireContext(), content, result)
                                    menu.dismiss()
                                }
                            }
                        } else {
                            currentEntry.showingTranslation = !currentEntry.showingTranslation
                            showTranslation(menu.requireContext(), currentEntry.original, currentEntry.translated)
                            menu.dismiss()
                        }
                    }
                })

                // 全体翻訳ボタンの追加とリスナー設定
                linearLayout.addView(TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    id = autoButtonId
                    text = if (channelId in autoChannels) "Disable Full Translate" else "Enable Full Translate"
                    setOnClickListener {
                        if (channelId in autoChannels) {
                            autoChannels.remove(channelId)
                            Utils.showToast("Disabled Full Translate")
                        } else {
                            autoChannels.add(channelId)
                            Utils.showToast("Enabled Full Translate")
                        }
                        menu.dismiss()
                    }
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
