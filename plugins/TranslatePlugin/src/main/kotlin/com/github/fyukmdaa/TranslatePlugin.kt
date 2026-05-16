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
        logger.info("start() called")
        
        val buttonId = View.generateViewId()
        val autoButtonId = View.generateViewId()

        val messageContextMenu = WidgetChatListActions::class.java
        
        // getBinding accessor
        val getBinding = try {
            messageContextMenu.getDeclaredMethod("getBinding").apply { isAccessible = true }
        } catch (e: Exception) {
            logger.error("getBinding method not found", e)
            return
        }

        // ── 1. configureUI: click listeners ───────────────────────────────
        logger.info("Patching configureUI")
        patcher.patch(
            messageContextMenu.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            Hook { cf ->
                logger.debug("configureUI hook")
                
                val menu = cf.thisObject as WidgetChatListActions
                val binding = try {
                    getBinding.invoke(menu) as WidgetChatListActionsBinding
                } catch (e: Exception) {
                    logger.error("getBinding invoke failed", e)
                    return@Hook
                }
                val model = cf.args[0] as? WidgetChatListActions.Model ?: return@Hook
                val message = model.message
                
                logger.debug("message.id=${message.id}, channelId=${message.channelId}")

                // 🔹 Translate Button
                binding.a.findViewById<TextView>(buttonId)?.setOnClickListener {
                    logger.info("Translate button clicked")
                    val entry = translatedMessages[message.id]
                    if (entry == null) {
                        Utils.threadPool.execute {
                            try {
                                val content = message.content ?: return@execute
                                val result = Translator.translate(content, targetLang())
                                if (result.isBlank()) return@execute
                                translatedMessages[message.id] = TranslatedEntry(content, result)
                                Utils.mainThread.post {
                                    showTranslation(menu.requireContext(), content, result)
                                    menu.dismiss()
                                }
                            } catch (e: Exception) {
                                logger.error("Translation failed", e)
                                Utils.mainThread.post { Utils.showToast("Error: ${e.message}") }
                            }
                        }
                    } else {
                        entry.showingTranslation = !entry.showingTranslation
                        showTranslation(menu.requireContext(), entry.original, entry.translated)
                        menu.dismiss()
                    }
                }

                // 🔹 Auto-Translate Button
                binding.a.findViewById<TextView>(autoButtonId)?.setOnClickListener {
                    logger.info("Auto-translate button clicked, channelId=${message.channelId}")
                    if (message.channelId in autoChannels) {
                        autoChannels.remove(message.channelId)
                        Utils.showToast("Auto-Translate OFF")
                    } else {
                        autoChannels.add(message.channelId)
                        Utils.showToast("Auto-Translate ON")
                    }
                    menu.dismiss()
                }
            }
        )

        // ── 2. onViewCreated: add buttons to view ─────────────────────────
        logger.info("Patching onViewCreated")
        patcher.patch(
            messageContextMenu,
            "onViewCreated",
            arrayOf(View::class.java, Bundle::class.java),
            Hook { cf ->
                logger.debug("onViewCreated hook")
                
                val linearLayout = (cf.args[0] as? NestedScrollView)?.getChildAt(0) as? LinearLayout
                    ?: return@Hook
                val ctx2 = linearLayout.context

                // 🔹 messageId
                val messageId = try {
                    WidgetChatListActions.`access$getMessageId$p`(cf.thisObject as WidgetChatListActions)
                } catch (e: Throwable) {
                    logger.error("Cannot access messageId", e)
                    return@Hook
                }

                // 🔹 channelId
                val channelId = try {
                    WidgetChatListActions.`access$getChannelId$p`(cf.thisObject as WidgetChatListActions)
                } catch (e: Throwable) {
                    logger.warn("Cannot access channelId, using 0L")
                    0L
                }

                // 🔹 Translate Button
                val translateBtn = linearLayout.findViewById<TextView>(buttonId)
                    ?: TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        id = buttonId
                        linearLayout.addView(this)
                    }
                val entry = translatedMessages[messageId]
                translateBtn.text = when {
                    entry == null -> "Translate"
                    entry.showingTranslation -> "Show Original"
                    else -> "Show Translation"
                }

                // 🔹 Auto-Translate Button
                val autoBtn = linearLayout.findViewById<TextView>(autoButtonId)
                    ?: TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        id = autoButtonId
                        linearLayout.addView(this)
                    }
                autoBtn.text = if (channelId in autoChannels) "Auto-Translate OFF" else "Auto-Translate ON"
            }
        )
        
        logger.info("start() done")
    }

    override fun stop(ctx: Context) {
        logger.info("stop() called")
        patcher.unpatchAll()
    }

    private fun showTranslation(ctx: Context, original: String, translated: String) {
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Translation")
            .setMessage("$original\n\n---\n\n$translated")
            .setPositiveButton("Close", null)
            .show()
    }
}
