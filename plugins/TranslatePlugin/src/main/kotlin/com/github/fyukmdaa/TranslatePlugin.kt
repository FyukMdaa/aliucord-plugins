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
import com.aliucord.Logger
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
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private lateinit var safeContext: Context

    private fun targetLang() = settings.getString("targetLang", "ja")

    override fun start(ctx: Context) {
        safeContext = ctx
        logger.info("▶️ TranslatePlugin started")
        
        val buttonId = View.generateViewId()
        val autoButtonId = View.generateViewId()
        val messageContextMenu = WidgetChatListActions::class.java
        
        val getBinding = try {
            messageContextMenu.getDeclaredMethod("getBinding").apply { isAccessible = true }
        } catch (e: Exception) {
            logger.error("❌ getBinding method not found", e)
            return
        }

        // ── 1. configureUI ───────────────────────────────
        patcher.patch(
            messageContextMenu.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java),
            Hook { cf ->
                val menu = cf.thisObject as WidgetChatListActions
                val binding = try {
                    getBinding.invoke(menu) as WidgetChatListActionsBinding
                } catch (e: Exception) {
                    return@Hook
                }
                val model = cf.args[0] as? WidgetChatListActions.Model ?: return@Hook
                val message = model.message

                binding.a.findViewById<TextView>(buttonId)?.setOnClickListener {
                    val entry = translatedMessages[message.id]
                    if (entry == null) {
                        // ── ここでメインスレッド内ですべてのデータをStringとして確保する ──
                        val messageId = message.id
                        val rawContent = message.content
                        // message.content が null または 空なら何もしない
                        val content = rawContent?.toString() ?: return@setOnClickListener
                        if (content.isBlank()) return@setOnClickListener

                        val lang = targetLang()

                        // データを確保してからスレッド開始
                        Thread {
                            try {
                                // Translatorにはもうオブジェクトを渡さない（Stringのみ）
                                val result = Translator.translate(content, lang)
                                
                                if (result.isNotBlank()) {
                                    translatedMessages[messageId] = TranslatedEntry(content, result)
                                    
                                    mainHandler.post {
                                        logger.info("Translation success for msg $messageId")
                                        showTranslation(safeContext, content, result)
                                        menu.dismiss()
                                    }
                                }
                            } catch (e: Exception) {
                                mainHandler.post { 
                                    val msg = "Err: ${e.javaClass.simpleName}"
                                    logger.error(msg, null)
                                    Toast.makeText(safeContext, "Translation Failed", Toast.LENGTH_SHORT).show() 
                                }
                            }
                        }.start()
                    } else {
                        entry.showingTranslation = !entry.showingTranslation
                        showTranslation(safeContext, entry.original, entry.translated)
                        menu.dismiss()
                    }
                }

                binding.a.findViewById<TextView>(autoButtonId)?.setOnClickListener {
                    if (message.channelId in autoChannels) {
                        autoChannels.remove(message.channelId)
                        Toast.makeText(safeContext, "Auto-Translate OFF", Toast.LENGTH_SHORT).show()
                    } else {
                        autoChannels.add(message.channelId)
                        Toast.makeText(safeContext, "Auto-Translate ON", Toast.LENGTH_SHORT).show()
                    }
                    menu.dismiss()
                }
            }
        )

        // ── 2. onViewCreated ─────────────────────────
        patcher.patch(
            messageContextMenu,
            "onViewCreated",
            arrayOf(View::class.java, Bundle::class.java),
            Hook { cf ->
                val linearLayout = (cf.args[0] as? NestedScrollView)?.getChildAt(0) as? LinearLayout
                    ?: return@Hook
                val ctx2 = linearLayout.context

                val messageId = try {
                    WidgetChatListActions.`access$getMessageId$p`(cf.thisObject as WidgetChatListActions)
                } catch (e: Throwable) { return@Hook }

                val channelId = try {
                    WidgetChatListActions.`access$getChannelId$p`(cf.thisObject as WidgetChatListActions)
                } catch (e: Throwable) { 0L }

                val translateBtn = linearLayout.findViewById<TextView>(buttonId)
                    ?: TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        id = buttonId
                        linearLayout.addView(this)
                    }
                val entry = translatedMessages[messageId]
                translateBtn.text = when {
                    entry == null -> "Translate Message"
                    entry.showingTranslation -> "Show Original"
                    else -> "Show Translation"
                }

                val autoBtn = linearLayout.findViewById<TextView>(autoButtonId)
                    ?: TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        id = autoButtonId
                        linearLayout.addView(this)
                    }
                autoBtn.text = if (channelId in autoChannels) "Auto-Translate OFF" else "Auto-Translate ON"
            }
        )
    }

    override fun stop(ctx: Context) {
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
