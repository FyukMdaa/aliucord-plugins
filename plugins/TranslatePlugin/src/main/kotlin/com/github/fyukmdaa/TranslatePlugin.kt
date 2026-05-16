package com.github.fyukmdaa

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.databinding.WidgetChatListActionsBinding
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import java.lang.reflect.Field

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
    // 翻訳中のIDを管理（重複リクエスト防止）
    private val translatingIds = mutableSetOf<Long>()

    private var chatList: WidgetChatList? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun targetLang() = settings.getString("targetLang", "ja")
    private fun showOriginal() = settings.getBool("showOriginal", true)

    override fun start(ctx: Context) {
        val buttonId = View.generateViewId()
        val autoButtonId = View.generateViewId()
        val messageContextMenu = WidgetChatListActions::class.java
        val getBinding = messageContextMenu
            .getDeclaredMethod("getBinding")
            .apply { isAccessible = true }

        // ── 0. chatList 取得 ──────────────────────────────────────────
        patcher.patch(WidgetChatList::class.java.getDeclaredConstructor(), Hook {
            chatList = it.thisObject as WidgetChatList
        })

        // ── 1. processMessageText をフック → 翻訳テキストを即時反映 ──
        // 参考コードと同じ方式: DraweeSpanStringBuilder を直接書き換える
        val mDraweeStringBuilder: Field = SimpleDraweeSpanTextView::class.java
            .getDeclaredField("mDraweeStringBuilder")
            .apply { isAccessible = true }

        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "processMessageText",
            arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java),
            Hook { cf ->
                val messageEntry = cf.args[1] as MessageEntry
                val message = messageEntry.message ?: return@Hook
                val entry = translatedMessages[message.id] ?: run {
                    // 全体翻訳ONのチャンネルで未翻訳なら非同期で翻訳開始
                    if (message.channelId in autoChannels
                        && message.id !in translatingIds
                        && !message.content.isNullOrBlank()
                    ) {
                        translateAsync(message.id, message.content!!, targetLang())
                    }
                    return@Hook
                }
                if (!entry.showingTranslation) return@Hook

                val textView = cf.args[0] as SimpleDraweeSpanTextView
                val builder = mDraweeStringBuilder[textView] as? DraweeSpanStringBuilder
                    ?: return@Hook

                // 原文 + --- + 訳文 に置き換え
                val display = if (showOriginal()) {
                    "${entry.original}\n---\n${entry.translated}"
                } else {
                    entry.translated
                }
                // builderの内容をdisplayで置き換え
                builder.replace(0, builder.length, display)
                textView.setDraweeSpanStringBuilder(builder)
            }
        )

        // ── 2. configureUI → ボタンクリック処理 ──────────────────────
        patcher.patch(
            messageContextMenu.getDeclaredMethod(
                "configureUI", WidgetChatListActions.Model::class.java
            ),
            Hook { cf ->
                val menu = cf.thisObject as WidgetChatListActions
                val binding = getBinding.invoke(menu) as? WidgetChatListActionsBinding
                    ?: return@Hook
                val message = (cf.args[0] as WidgetChatListActions.Model).message
                val context = try { menu.requireContext() } catch (e: Exception) { return@Hook }

                // 翻訳ボタン
                binding.a.findViewById<TextView>(buttonId)?.setOnClickListener {
                    val entry = translatedMessages[message.id]
                    if (entry == null) {
                        val content = message.content ?: return@setOnClickListener
                        if (content.isBlank()) return@setOnClickListener
                        translateAsync(message.id, content, targetLang()) {
                            mainHandler.post {
                                Toast.makeText(context, "Translated!", Toast.LENGTH_SHORT).show()
                            }
                        }
                        menu.dismiss()
                    } else {
                        entry.showingTranslation = !entry.showingTranslation
                        rerenderMessage(message.id)
                        menu.dismiss()
                    }
                }

                // 全体翻訳ボタン
                binding.a.findViewById<TextView>(autoButtonId)?.setOnClickListener {
                    if (message.channelId in autoChannels) {
                        autoChannels.remove(message.channelId)
                        Toast.makeText(context, "Auto-Translate OFF", Toast.LENGTH_SHORT).show()
                    } else {
                        autoChannels.add(message.channelId)
                        Toast.makeText(context, "Auto-Translate ON", Toast.LENGTH_SHORT).show()
                    }
                    menu.dismiss()
                }
            }
        )

        // ── 3. onViewCreated → ボタンをメニューに追加 ────────────────
        patcher.patch(
            messageContextMenu,
            "onViewCreated",
            arrayOf(View::class.java, Bundle::class.java),
            Hook { cf ->
                val linearLayout = (cf.args[0] as? NestedScrollView)
                    ?.getChildAt(0) as? LinearLayout ?: return@Hook
                val ctx2 = linearLayout.context
                val messageId = WidgetChatListActions
                    .`access$getMessageId$p`(cf.thisObject as WidgetChatListActions)
                val channelId = try {
                    WidgetChatListActions
                        .`access$getChannelId$p`(cf.thisObject as WidgetChatListActions)
                } catch (_: Throwable) { 0L }

                val entry = translatedMessages[messageId]
                linearLayout.addView(
                    TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        id = buttonId
                        text = when {
                            entry == null -> "Translate Message"
                            entry.showingTranslation -> "Show Original"
                            else -> "Show Translation"
                        }
                    }
                )
                linearLayout.addView(
                    TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        id = autoButtonId
                        text = if (channelId in autoChannels) "Auto-Translate OFF" else "Auto-Translate ON"
                    }
                )
            }
        )
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()

    // ── ヘルパー ──────────────────────────────────────────────────────

    private fun translateAsync(
        messageId: Long,
        content: String,
        lang: String,
        onComplete: (() -> Unit)? = null
    ) {
        if (messageId in translatingIds) return
        translatingIds.add(messageId)
        Thread {
            try {
                val result = Translator.translate(content, lang)
                if (result.isNotBlank()) {
                    translatedMessages[messageId] = TranslatedEntry(content, result)
                    onComplete?.invoke()
                    rerenderMessage(messageId)
                }
            } catch (_: Exception) {
            } finally {
                translatingIds.remove(messageId)
            }
        }.start()
    }

    private fun rerenderMessage(id: Long) {
        val list = chatList ?: return
        mainHandler.post {
            try {
                list.rerenderMessage(id)
            } catch (_: Exception) {}
        }
    }
}
