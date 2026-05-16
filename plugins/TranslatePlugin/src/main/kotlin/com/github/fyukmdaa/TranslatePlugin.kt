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
import com.aliucord.Logger  // 🔽 Logger をインポート

@AliucordPlugin
class TranslatePlugin : Plugin() {
    
    // 🔽 Logger の追加
    private val logger = Logger("TranslatePlugin")

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
        logger.info("▶️ start() called")
        
        val buttonId = View.generateViewId()
        val autoButtonId = View.generateViewId()
        logger.debug("Generated buttonId=$buttonId, autoButtonId=$autoButtonId")

        val messageContextMenu = WidgetChatListActions::class.java
        val getBinding = try {
            messageContextMenu.getDeclaredMethod("getBinding").apply { isAccessible = true }
        } catch (e: Exception) {
            logger.error("❌ getBinding method not found", e)
            return
        }

        // ── 1. configureUI: ボタンのクリックリスナーを設定 ──────────────────
        logger.info("🔧 Patching configureUI method")
        try {
            patcher.patch(
                messageContextMenu.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
                Hook { cf ->
                    logger.debug("⚡ configureUI hook triggered")
                    
                    val menu = cf.thisObject as WidgetChatListActions
                    val binding = try {
                        getBinding.invoke(menu) as WidgetChatListActionsBinding
                    } catch (e: Exception) {
                        logger.error("❌ Failed to get binding", e)
                        return@Hook
                    }
                    val model = cf.args[0] as? WidgetChatListActions.Model ?: return@Hook
                    val message = model.message
                    
                    logger.debug("📨 Message: id=${message.id}, channelId=${message.channelId}, content=${message.content?.take(50)}")

                    // 🔽 翻訳ボタン: リスナー設定
                    val translateBtn = binding.a.findViewById<TextView>(buttonId)
                    if (translateBtn == null) {
                        logger.warn("⚠️ Translate button (id=$buttonId) not found in binding.a")
                    } else {
                        logger.debug("✅ Found translate button, setting onClickListener")
                        translateBtn.setOnClickListener {
                            logger.info("🖱️ Translate button clicked for messageId=${message.id}")
                            val entry = translatedMessages[message.id]
                            if (entry == null) {
                                logger.info("🔄 Translating new message: '${message.content?.take(30)}...'")
                                Utils.threadPool.execute {
                                    try {
                                        val content = message.content ?: run {
                                            logger.warn("⚠️ Message content is null")
                                            return@execute
                                        }
                                        logger.debug("🌐 Calling Translator.translate(lang=$targetLang())")
                                        val result = Translator.translate(content, targetLang())
                                        if (result.isBlank()) {
                                            logger.warn("⚠️ Translation result is blank")
                                            return@execute
                                        }
                                        translatedMessages[message.id] = TranslatedEntry(
                                            original = content,
                                            translated = result
                                        )
                                        logger.info("✅ Translation cached: original=${content.take(30)}..., translated=${result.take(30)}...")
                                        Utils.mainThread.post {
                                            showTranslation(menu.requireContext(), content, result)
                                            menu.dismiss()
                                        }
                                    } catch (e: Exception) {
                                        logger.error("❌ Translation failed", e)
                                        Utils.mainThread.post {
                                            Utils.showToast("Translate error: ${e.message}")
                                        }
                                    }
                                }
                            } else {
                                logger.info("🔁 Toggling display for cached message: showingTranslation=${!entry.showingTranslation}")
                                entry.showingTranslation = !entry.showingTranslation
                                showTranslation(menu.requireContext(), entry.original, entry.translated)
                                menu.dismiss()
                            }
                        }
                    }

                    // 🔽 全体翻訳ボタン: リスナー設定
                    val autoBtn = binding.a.findViewById<TextView>(autoButtonId)
                    if (autoBtn == null) {
                        logger.warn("⚠️ Auto-translate button (id=$autoButtonId) not found in binding.a")
                    } else {
                        logger.debug("✅ Found auto-translate button, setting onClickListener")
                        autoBtn.setOnClickListener {
                            logger.info("🖱️ Auto-translate button clicked for channelId=${message.channelId}")
                            if (message.channelId in autoChannels) {
                                autoChannels.remove(message.channelId)
                                logger.info("🔕 Removed channelId=${message.channelId} from autoChannels")
                                Utils.showToast("全体翻訳をOFFにしました")
                            } else {
                                autoChannels.add(message.channelId)
                                logger.info("🔔 Added channelId=${message.channelId} to autoChannels")
                                Utils.showToast("全体翻訳をONにしました")
                            }
                            menu.dismiss()
                        }
                    }
                }
            )
            logger.info("✅ Successfully patched configureUI")
        } catch (e: Exception) {
            logger.error("❌ Failed to patch configureUI", e)
        }

        // ── 2. onViewCreated: 実際のビューにボタンを追加 ───────────────────
        logger.info("🔧 Patching onViewCreated method")
        try {
            patcher.patch(
                messageContextMenu,
                "onViewCreated",
                arrayOf(View::class.java, Bundle::class.java),
                Hook { cf ->
                    logger.debug("⚡ onViewCreated hook triggered")
                    
                    val rootView = cf.args[0] as? View ?: run {
                        logger.warn("⚠️ rootView is null or not View")
                        return@Hook
                    }
                    val linearLayout = (rootView as? NestedScrollView)?.getChildAt(0) as? LinearLayout
                        ?: run {
                            logger.warn("⚠️ Failed to get LinearLayout from rootView (type=${rootView::class.java.simpleName})")
                            return@Hook
                        }
                    val ctx2 = linearLayout.context

                    // 🔽 messageId 取得
                    val messageId = try {
                        WidgetChatListActions.`access$getMessageId$p`(cf.thisObject as WidgetChatListActions)
                    } catch (e: Throwable) {
                        logger.error("❌ Failed to access messageId", e)
                        return@Hook
                    }
                    logger.debug("📨 Retrieved messageId=$messageId")

                    // 🔽 channelId 取得
                    val channelId = try {
                        WidgetChatListActions.`access$getChannelId$p`(cf.thisObject as WidgetChatListActions)
                    } catch (e: Throwable) {
                        logger.warn("⚠️ Failed to access channelId, falling back to 0L", e)
                        0L
                    }
                    logger.debug("📺 Retrieved channelId=$channelId")

                    // 🔽 翻訳ボタン: 重複追加防止 + テキスト更新
                    val translateBtn = linearLayout.findViewById<TextView>(buttonId)
                    if (translateBtn == null) {
                        logger.debug("➕ Adding new translate button (id=$buttonId)")
                        TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                            id = buttonId
                            linearLayout.addView(this)
                        }
                    } else {
                        logger.debug("♻️ Reusing existing translate button")
                    }
                    val entry = translatedMessages[messageId]
                    linearLayout.findViewById<TextView>(buttonId)?.text = when {
                        entry == null -> "🌐 翻訳"
                        entry.showingTranslation -> "🌐 原文を表示"
                        else -> "🌐 訳文を表示"
                    }.also { logger.debug("🏷️ Set translate button text: '$it'") }

                    // 🔽 全体翻訳ボタン: 重複追加防止 + テキスト更新
                    val autoBtn = linearLayout.findViewById<TextView>(autoButtonId)
                    if (autoBtn == null) {
                        logger.debug("➕ Adding new auto-translate button (id=$autoButtonId)")
                        TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                            id = autoButtonId
                            linearLayout.addView(this)
                        }
                    } else {
                        logger.debug("♻️ Reusing existing auto-translate button")
                    }
                    val autoText = if (channelId in autoChannels) "🌐 全体翻訳 OFF" else "🌐 全体翻訳 ON"
                    linearLayout.findViewById<TextView>(autoButtonId)?.text = autoText
                    logger.debug("🏷️ Set auto-translate button text: '$autoText' (channelId=$channelId, inSet=${channelId in autoChannels})")
                }
            )
            logger.info("✅ Successfully patched onViewCreated")
        } catch (e: Exception) {
            logger.error("❌ Failed to patch onViewCreated", e)
        }
        
        logger.info("✅ start() completed")
    }

    override fun stop(ctx: Context) {
        logger.info("⏹️ stop() called, unpatching all")
        patcher.unpatchAll()
        logger.info("✅ stop() completed")
    }

    private fun showTranslation(ctx: Context, original: String, translated: String) {
        logger.debug("🗨️ Showing translation dialog: original='${original.take(30)}...', translated='${translated.take(30)}...'")
        android.app.AlertDialog.Builder(ctx)
            .setTitle("翻訳")
            .setMessage("$original\n\n---\n\n$translated")
            .setPositiveButton("閉じる", null)
            .show()
    }
}
