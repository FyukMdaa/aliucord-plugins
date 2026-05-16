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
import com.discord.models.message.Message
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.lytefast.flexinput.R
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

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
    
    private var chatList: WidgetChatList? = null
    private var mDraweeStringBuilderField: Field? = null
    private var rerenderMethod: Method? = null
    private var adapterField: Field? = null
    private var dataField: Field? = null

    private fun targetLang() = settings.getString("targetLang", "ja")

    private fun isBlankSafe(str: String): Boolean {
        if (str.isEmpty()) return true
        for (i in 0 until str.length) {
            if (!str[i].isWhitespace()) return false
        }
        return true
    }

    private fun rerenderMessage(id: Long) {
        val list = chatList ?: return
        try {
            // 1. rerenderMessage メソッドの試行 (難読化されている可能性があるため名前で探すのは最終手段)
            if (rerenderMethod == null) {
                try {
                    rerenderMethod = WidgetChatList::class.java.getDeclaredMethod("rerenderMessage", Long::class.javaPrimitiveType)
                    rerenderMethod?.isAccessible = true
                } catch (e: Exception) {
                    // 名前で見つからない場合は引数型で探す
                    rerenderMethod = WidgetChatList::class.java.declaredMethods.find { 
                        it.parameterTypes.size == 1 && it.parameterTypes[0] == Long::class.javaPrimitiveType && it.returnType == Void.TYPE
                    }
                    rerenderMethod?.isAccessible = true
                }
            }
            rerenderMethod?.invoke(list, id)
        } catch (e: Exception) {
            // 2. Adapter を使用した再描画 (フォールバック)
            try {
                if (adapterField == null) {
                    adapterField = WidgetChatList::class.java.declaredFields.find { it.type.name.contains("WidgetChatListAdapter") }
                    adapterField?.isAccessible = true
                }
                val adapter = adapterField?.get(list) ?: return
                
                if (dataField == null) {
                    // List型かつMessageEntryを含む可能性のあるフィールドを探す
                    var clazz: Class<*>? = adapter.javaClass
                    while (clazz != null && dataField == null) {
                        dataField = clazz.declaredFields.find { 
                            List::class.java.isAssignableFrom(it.type) && !Modifier.isStatic(it.modifiers)
                        }
                        clazz = clazz.superclass
                    }
                    dataField?.isAccessible = true
                }
                
                val data = dataField?.get(adapter) as? List<*> ?: return
                val index = data.indexOfFirst { 
                    val entry = it as? MessageEntry
                    entry?.message?.id == id
                }
                
                if (index != -1) {
                    // notifyItemChanged を呼び出す
                    val notifyMethod = adapter.javaClass.superclass.superclass.superclass.getDeclaredMethod("notifyItemChanged", Int::class.javaPrimitiveType)
                    notifyMethod.invoke(adapter, index)
                }
            } catch (ex: Exception) {
                logger.error("❌ Failed to rerender message $id via all methods", ex)
            }
        }
    }

    override fun start(ctx: Context) {
        try {
            logger.info("▶️ TranslatePlugin started")
            
            val buttonId = View.generateViewId()
            val autoButtonId = View.generateViewId()
            val messageContextMenu = WidgetChatListActions::class.java
            
            val getBinding = try {
                messageContextMenu.getDeclaredMethod("getBinding").apply { isAccessible = true }
            } catch (e: Exception) {
                // getBinding が難読化されている場合、WidgetChatListActionsBinding を返すメソッドを探す
                messageContextMenu.declaredMethods.find { it.returnType == WidgetChatListActionsBinding::class.java }?.apply { isAccessible = true }
            }

            // ── 0. WidgetChatList の取得 ─────────────────────────────
            try {
                patcher.patch(WidgetChatList::class.java.getDeclaredConstructor(), Hook {
                    chatList = it.thisObject as WidgetChatList
                })
            } catch (e: Exception) {
                logger.error("❌ Failed to patch WidgetChatList constructor", e)
            }

            // ── 1. メッセージ書き換えパッチ (processMessageText) ─────────────────────────────
            try {
                if (mDraweeStringBuilderField == null) {
                    mDraweeStringBuilderField = SimpleDraweeSpanTextView::class.java.getDeclaredField("mDraweeStringBuilder").apply { isAccessible = true }
                }
                
                patcher.patch(
                    WidgetChatListAdapterItemMessage::class.java,
                    "processMessageText",
                    arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java),
                    Hook { cf ->
                        try {
                            val messageEntry = cf.args[1] as MessageEntry
                            val message = messageEntry.message ?: return@Hook
                            val entry = translatedMessages[message.id]
                            
                            if (entry != null && entry.showingTranslation) {
                                val textView = cf.args[0] as SimpleDraweeSpanTextView
                                val builder = mDraweeStringBuilderField?.get(textView) as? DraweeSpanStringBuilder ?: return@Hook
                                
                                // 元のテキストを探して置換
                                val content = builder.toString()
                                if (content == entry.original) {
                                    builder.replace(0, builder.length, entry.translated)
                                    textView.setDraweeSpanStringBuilder(builder)
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Error in processMessageText hook", e)
                        }
                    }
                )
                logger.info("✅ Message rewrite patch applied (processMessageText)")
            } catch (e: Exception) {
                logger.error("❌ Failed to patch processMessageText", e)
            }

            // ── 2. configureUI Patch (ボタン動作) ───────────────────────────────
            try {
                val configureMethod = try {
                    messageContextMenu.getDeclaredMethod("configureUI", WidgetChatListActions.Model::class.java)
                } catch (e: NoSuchMethodException) {
                    // 難読化対応: 引数が Model のみのメソッドを探す
                    messageContextMenu.declaredMethods.find { it.parameterTypes.size == 1 && it.parameterTypes[0] == WidgetChatListActions.Model::class.java }
                }

                if (configureMethod != null) {
                    patcher.patch(configureMethod, Hook { cf ->
                        try {
                            val menu = cf.thisObject as WidgetChatListActions
                            val binding = try {
                                getBinding?.invoke(menu) as? WidgetChatListActionsBinding
                            } catch (e: Exception) {
                                null
                            } ?: return@Hook
                            
                            val model = cf.args[0] as? WidgetChatListActions.Model ?: return@Hook
                            val message = model.message

                            binding.a.findViewById<TextView>(buttonId)?.setOnClickListener {
                                val entry = translatedMessages[message.id]
                                if (entry == null) {
                                    val content = message.content ?: return@setOnClickListener
                                    
                                    if (isBlankSafe(content)) return@setOnClickListener
                                    
                                    val lang = targetLang()

                                    Thread {
                                        try {
                                            val result = Translator.translate(content, lang)
                                            if (result.isNotEmpty()) {
                                                translatedMessages[message.id] = TranslatedEntry(content, result)
                                                mainHandler.post {
                                                    logger.info("Translation success for msg ${message.id}")
                                                    Toast.makeText(menu.requireContext(), "Message Translated!", Toast.LENGTH_SHORT).show()
                                                    rerenderMessage(message.id)
                                                    menu.dismiss()
                                                }
                                            }
                                        } catch (e: Exception) {
                                            mainHandler.post { 
                                                logger.error("Translation error", e)
                                                Toast.makeText(menu.requireContext(), "Translation Failed", Toast.LENGTH_SHORT).show() 
                                            }
                                        }
                                    }.start()
                                } else {
                                    entry.showingTranslation = !entry.showingTranslation
                                    rerenderMessage(message.id)
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
                            logger.error("Error inside configureUI hook", e)
                        }
                    })
                }
            } catch (e: Exception) {
                logger.error("Failed to patch configureUI", e)
            }

            // ── 3. onViewCreated Patch (ボタン表示) ─────────────────────────
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
                                val field = WidgetChatListActions::class.java.declaredFields.find { it.type == Long::class.javaPrimitiveType && it.name.contains("messageId", ignoreCase = true) }
                                    ?: WidgetChatListActions::class.java.declaredFields.find { it.type == Long::class.javaPrimitiveType }
                                field?.isAccessible = true
                                field?.get(cf.thisObject) as? Long
                            } catch (e: Throwable) { 
                                try {
                                    WidgetChatListActions.`access$getMessageId$p`(cf.thisObject as WidgetChatListActions)
                                } catch (e2: Throwable) { null }
                            } ?: return@Hook

                            val channelId = try {
                                val field = WidgetChatListActions::class.java.declaredFields.find { it.type == Long::class.javaPrimitiveType && it.name.contains("channelId", ignoreCase = true) }
                                    ?: WidgetChatListActions::class.java.declaredFields.filter { it.type == Long::class.javaPrimitiveType }.getOrNull(1)
                                field?.isAccessible = true
                                field?.get(cf.thisObject) as? Long
                            } catch (e: Throwable) { 
                                try {
                                    WidgetChatListActions.`access$getChannelId$p`(cf.thisObject as WidgetChatListActions)
                                } catch (e2: Throwable) { 0L }
                            } ?: 0L

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
                            logger.error("Error inside onViewCreated hook", e)
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
}
