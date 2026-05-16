package com.github.fyukmdaa

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Logger
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.databinding.WidgetChatListActionsBinding
import com.discord.models.message.Message
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

    private fun isBlankSafe(str: String): Boolean {
        if (str.isEmpty()) return true
        for (i in 0 until str.length) {
            if (!str[i].isWhitespace()) return false
        }
        return true
    }

    override fun start(ctx: Context) {
        try {
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

            // ── 0. 【決定版】メッセージ書き換えパッチ ─────────────────────────────
            try {
                val itemClassName = "com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage"
                val messageClass = Class.forName("com.discord.models.message.Message")
                
                // onConfigure(Message, Int, Boolean) などのシグネチャに対応するため、
                // 引数の数や型を柔軟に扱う必要があるが、Aliucordのpatcherは正確な型を必要とする。
                // 一般的なシグネチャ (Message, Int) を試す
                patcher.patch(
                    itemClassName, 
                    "onConfigure", 
                    arrayOf<Class<*>>(messageClass, Int::class.javaPrimitiveType), 
                    Hook { cf ->
                        try {
                            val message = cf.args[0] as? Message ?: return@Hook
                            val entry = translatedMessages[message.id]
                            
                            if (entry != null && entry.showingTranslation) {
                                // 【重要】ViewHolderからitemViewを取得する (Bindingフィールド名に依存しない)
                                // RecyclerView.ViewHolder.itemView は親クラスのフィールドなので安全
                                val viewHolder = cf.thisObject
                                val itemViewField = RecyclerView.ViewHolder::class.java.getDeclaredField("itemView").apply { isAccessible = true }
                                val itemView = itemViewField.get(viewHolder) as? View ?: return@Hook

                                // 本文と一致するTextViewを探して書き換える
                                replaceTextViewText(itemView, entry.original, entry.translated)
                            }
                        } catch (e: Exception) {
                            // エラーはログに出すが、クラッシュはさせない
                            logger.error("Error in message rewrite hook: ${e.message}", null)
                        }
                    }
                )
                logger.info("✅ Message rewrite patch applied (Method: onConfigure)")
            } catch (e: Exception) {
                // onConfigure(Message, Int) で失敗した場合、別のシグネチャを試すか諦める
                logger.error("❌ Failed to patch onConfigure (maybe signature changed)", e)
                // 念のため configure(Message) も試す
                try {
                    val messageClass = Class.forName("com.discord.models.message.Message")
                    patcher.patch(
                        itemClassName, 
                        "configure", 
                        arrayOf<Class<*>>(messageClass), 
                        Hook { cf ->
                            try {
                                val message = cf.args[0] as? Message ?: return@Hook
                                val entry = translatedMessages[message.id]
                                if (entry != null && entry.showingTranslation) {
                                    val viewHolder = cf.thisObject
                                    val itemViewField = RecyclerView.ViewHolder::class.java.getDeclaredField("itemView").apply { isAccessible = true }
                                    val itemView = itemViewField.get(viewHolder) as? View ?: return@Hook
                                    replaceTextViewText(itemView, entry.original, entry.translated)
                                }
                            } catch (e: Exception) {
                                logger.error("Error in configure hook", null)
                            }
                        }
                    )
                    logger.info("✅ Message rewrite patch applied (Method: configure)")
                } catch (e2: Exception) {
                    logger.error("❌ Failed to patch configure as well", e2)
                }
            }

            // ── 1. configureUI Patch (ボタン動作) ───────────────────────────────
            try {
                val configureMethod = try {
                    messageContextMenu.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java)
                } catch (e: NoSuchMethodException) {
                    logger.error("❌ configureUI method not found.", e)
                    return
                }

                patcher.patch(configureMethod, Hook { cf ->
                    try {
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
                                val rawContent = message.content
                                val content = if (rawContent is String) rawContent else rawContent?.toString() ?: return@setOnClickListener
                                
                                if (isBlankSafe(content)) return@setOnClickListener
                                
                                val lang = targetLang()

                                Thread {
                                    try {
                                        val result = Translator.translate(content, lang)
                                        if (result.isNotEmpty()) {
                                            translatedMessages[message.id] = TranslatedEntry(content, result)
                                            mainHandler.post {
                                                logger.info("Translation success for msg ${message.id}")
                                                Toast.makeText(menu.requireContext(), "Message Translated! (Scroll to see)", Toast.LENGTH_SHORT).show()
                                                menu.dismiss()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        mainHandler.post { 
                                            logger.error("Translation error", null)
                                            Toast.makeText(menu.requireContext(), "Translation Failed", Toast.LENGTH_SHORT).show() 
                                        }
                                    }
                                }.start()
                            } else {
                                entry.showingTranslation = !entry.showingTranslation
                                Toast.makeText(menu.requireContext(), if(entry.showingTranslation) "Showing Translation" else "Showing Original", Toast.LENGTH_SHORT).show()
                                menu.dismiss()
                            }
                        }

                        binding.a.findViewById<TextView>(autoButtonId)?.setOnClickListener {
                            if (message.channelId in autoChannels) {
                                autoChannels.remove(message.channelId)
                                Toast.makeText(menu.requireContext(), "Auto-Translate OFF", Toast.LENGTH_SHORT).show()
                            } else {
                                autoChannels.add(message.channelId)
                                Toast.makeText(menu.requireContext(), "Auto-Translate ON", Toast.LENGTH_SHORT).show()
                            }
                            menu.dismiss()
                        }
                    } catch (e: Exception) {
                        logger.error("Error inside configureUI hook", null)
                    }
                })
            } catch (e: Exception) {
                logger.error("Failed to patch configureUI", e)
            }

            // ── 2. onViewCreated Patch (ボタン表示) ─────────────────────────
            try {
                patcher.patch(
                    messageContextMenu,
                    "onViewCreated",
                    arrayOf(View::class.java, Bundle::class.java),
                    Hook { cf ->
                        try {
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
                                entry == null -> "🌐 Translate"
                                entry.showingTranslation -> "🌐 Show Original"
                                else -> "🌐 Show Translation"
                            }

                            val autoBtn = linearLayout.findViewById<TextView>(autoButtonId)
                                ?: TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                                    id = autoButtonId
                                    linearLayout.addView(this)
                                }
                            autoBtn.text = if (channelId in autoChannels) "🌐 Auto-Translate OFF" else "🌐 Auto-Translate ON"
                        } catch (e: Exception) {
                            logger.error("Error inside onViewCreated hook", null)
                        }
                    }
                )
            } catch (e: Exception) {
                logger.error("Failed to patch onViewCreated", e)
            }

        } catch (e: Throwable) {
            logger.error("Fatal error starting TranslatePlugin", e)
        }
    }

    override fun stop(ctx: Context) {
        patcher.unpatchAll()
    }

    // 【改善】原文と一致するTextViewを探して置換する関数
    private fun replaceTextViewText(view: View, original: String, translated: String) {
        if (view is TextView) {
            // TextViewなら内容を比較
            // Markdown等が含まれていても toString() で平文になるので比較可能
            if (view.text.toString() == original) {
                view.text = translated
                return
            }
        } else if (view is ViewGroup) {
            // ViewGroupなら再帰的に子を探索
            for (i in 0 until view.childCount) {
                replaceTextViewText(view.getChildAt(i), original, translated)
            }
        }
    }

    private fun showTranslation(ctx: Context, original: String, translated: String) {
        // 未使用
    }
}
