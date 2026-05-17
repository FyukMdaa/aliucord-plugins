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
import com.discord.models.message.Message
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
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
    private val translatingIds = mutableSetOf<Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var chatList: WidgetChatList? = null
    private var adapterField: Field? = null
    private var dataField: Field? = null

    private fun targetLang() = settings.getString("targetLang", "ja")
    private fun showOriginal() = settings.getBool("showOriginal", true)

    private fun isBlankSafe(str: String): Boolean {
        if (str.isEmpty()) return true
        for (i in 0 until str.length) {
            if (!str[i].isWhitespace()) return false
        }
        return true
    }

    private fun getRawContent(message: Message): String? {
        return try {
            Message::class.java.declaredFields
                .firstOrNull { it.type == String::class.java }
                ?.also { it.isAccessible = true }
                ?.get(message) as? String
        } catch (e: Exception) { null }
    }

    private fun rerenderMessage(id: Long) {
        val list = chatList ?: run {
            logger.warn("rerenderMessage: chatList is null!")
            return
        }
        mainHandler.post {
            try {
                // adapterを取得
                if (adapterField == null) {
                    adapterField = WidgetChatList::class.java.declaredFields
                        .find { it.type.name.contains("WidgetChatListAdapter") }
                    adapterField?.isAccessible = true
                }
                val adapter = adapterField?.get(list) ?: run {
                    logger.warn("rerenderMessage: adapter is null")
                    return@post
                }

                // データリストを取得
                if (dataField == null) {
                    var clazz: Class<*>? = adapter.javaClass
                    while (clazz != null && dataField == null) {
                        dataField = clazz.declaredFields.find {
                            List::class.java.isAssignableFrom(it.type) &&
                            !Modifier.isStatic(it.modifiers)
                        }
                        clazz = clazz.superclass
                    }
                    dataField?.isAccessible = true
                }

                val data = dataField?.get(adapter) as? List<*> ?: run {
                    logger.warn("rerenderMessage: data is null")
                    return@post
                }

                val index = data.indexOfFirst {
                    (it as? MessageEntry)?.message?.id == id
                }

                if (index == -1) {
                    logger.warn("rerenderMessage: message not found in list, id=$id")
                    return@post
                }

                // notifyItemChanged をスーパークラスまで遡って探す
                var notifyMethod: Method? = null
                var currentClass: Class<*>? = adapter.javaClass
                while (currentClass != null && notifyMethod == null) {
                    try {
                        notifyMethod = currentClass.getDeclaredMethod(
                            "notifyItemChanged", Int::class.javaPrimitiveType
                        )
                    } catch (ex: NoSuchMethodException) {
                        currentClass = currentClass.superclass
                    }
                }
                notifyMethod?.invoke(adapter, index)
                logger.info("rerenderMessage: notifyItemChanged($index) called for id=$id")

            } catch (e: Exception) {
                logger.error("rerenderMessage failed", e)
            }
        }
    }

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
                if (result.isNotEmpty()) {
                    translatedMessages[messageId] = TranslatedEntry(content, result)
                    onComplete?.invoke()
                    rerenderMessage(messageId)
                }
            } catch (e: Exception) {
            } finally {
                translatingIds.remove(messageId)
            }
        }.start()
    }

    override fun start(ctx: Context) {
        try {
            logger.info("TranslatePlugin started")

            val buttonId = View.generateViewId()
            val autoButtonId = View.generateViewId()
            val messageContextMenu = WidgetChatListActions::class.java

            val getBinding = try {
                messageContextMenu.getDeclaredMethod("getBinding")
                    .apply { isAccessible = true }
            } catch (e: Exception) {
                messageContextMenu.declaredMethods
                    .find { it.returnType == WidgetChatListActionsBinding::class.java }
                    ?.apply { isAccessible = true }
            }

            // ── 0. WidgetChatList の取得 ──────────────────────────────
            try {
                patcher.patch(WidgetChatList::class.java.getDeclaredConstructor(), Hook {
                    chatList = it.thisObject as WidgetChatList
                })
            } catch (e: Exception) {
                logger.error("Failed to patch WidgetChatList constructor", e)
            }

            // ── 1a. Message.getContent フック → 全体翻訳のトリガーのみ ──
            try {
                patcher.patch(Message::class.java, "getContent", emptyArray(), Hook { cf ->
                    try {
                        val message = cf.thisObject as Message
                        if (translatedMessages[message.id] == null && message.channelId in autoChannels) {
                            val rawContent = getRawContent(message)
                            if (!rawContent.isNullOrEmpty() && !isBlankSafe(rawContent)) {
                                translateAsync(message.id, rawContent, targetLang())
                            }
                        }
                    } catch (e: Exception) { }
                })
            } catch (e: Exception) {
                logger.error("Failed to patch Message.getContent", e)
            }

            // ── 1b. processMessageText フック → 翻訳テキストを即時反映 ──
            try {
                val mDraweeStringBuilder: Field = SimpleDraweeSpanTextView::class.java
                    .getDeclaredField("mDraweeStringBuilder")
                    .apply { isAccessible = true }

                patcher.patch(
                    WidgetChatListAdapterItemMessage::class.java,
                   "processMessageText",
                    arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java),
                    Hook { cf ->
                        try {
                            val messageEntry = cf.args[1] as MessageEntry
                            val message = messageEntry.message ?: return@Hook
                            val entry = translatedMessages[message.id]
                            logger.info("processMessageText called: id=${message.id}, entry=$entry")
                            
                            entry ?: return@Hook
                            if (!entry.showingTranslation) return@Hook

                            val textView = cf.args[0] as SimpleDraweeSpanTextView
                            val builder = mDraweeStringBuilder[textView] as? DraweeSpanStringBuilder
                                ?: return@Hook

                            val display = if (showOriginal()) {
                                "${entry.original}\n---\n${entry.translated}"
                            } else {
                                entry.translated
                            }

                            builder.replace(0, builder.length, display)
                            textView.setDraweeSpanStringBuilder(builder)
                        } catch (e: Exception) { }
                    }
                )
            } catch (e: Exception) {
                logger.error("Failed to patch processMessageText", e)
            }

            // ── 2. configureUI フック → ボタンクリック処理 ───────────
            try {
                val configureMethod = try {
                    messageContextMenu.getDeclaredMethod(
                        "configureUI", WidgetChatListActions.Model::class.java
                    )
                } catch (e: NoSuchMethodException) {
                    messageContextMenu.declaredMethods.find {
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == WidgetChatListActions.Model::class.java
                    }
                }

                if (configureMethod != null) {
                    patcher.patch(configureMethod, Hook { cf ->
                        try {
                            val menu = cf.thisObject as WidgetChatListActions
                            val binding = try {
                                getBinding?.invoke(menu) as? WidgetChatListActionsBinding
                            } catch (e: Exception) { null } ?: return@Hook

                            val model = cf.args[0] as? WidgetChatListActions.Model
                                ?: return@Hook
                            val message = model.message
                            val context = try { menu.requireContext() } catch (e: Exception) { null }

                            binding.a.findViewById<TextView>(buttonId)?.setOnClickListener {
                                val entry = translatedMessages[message.id]
                                if (entry == null) {
                                    val rawContent = getRawContent(message)
                                        ?: return@setOnClickListener
                                    if (isBlankSafe(rawContent)) return@setOnClickListener
                                    translateAsync(message.id, rawContent, targetLang()) {
                                        mainHandler.post {
                                            context?.let {
                                                Toast.makeText(it, "Message Translated!", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    menu.dismiss()
                                } else {
                                    entry.showingTranslation = !entry.showingTranslation
                                    rerenderMessage(message.id)
                                    menu.dismiss()
                                }
                            }

                            binding.a.findViewById<TextView>(autoButtonId)?.setOnClickListener {
                                if (message.channelId in autoChannels) {
                                    autoChannels.remove(message.channelId)
                                    context?.let {
                                        Toast.makeText(it, "Auto-Translate OFF", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    autoChannels.add(message.channelId)
                                    context?.let {
                                        Toast.makeText(it, "Auto-Translate ON", Toast.LENGTH_SHORT).show()
                                    }
                                    rerenderMessage(message.id)
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

            // ── 3. onViewCreated フック → ボタンをメニューに追加 ─────
            try {
                patcher.patch(
                    messageContextMenu,
                    "onViewCreated",
                    arrayOf(View::class.java, Bundle::class.java),
                    Hook { cf ->
                        try {
                            val linearLayout = (cf.args[0] as? NestedScrollView)
                                ?.getChildAt(0) as? LinearLayout ?: return@Hook
                            val ctx2 = linearLayout.context

                            val messageId = try {
                                val field = WidgetChatListActions::class.java.declaredFields
                                    .find { it.type == Long::class.javaPrimitiveType && it.name.contains("messageId", ignoreCase = true) }
                                    ?: WidgetChatListActions::class.java.declaredFields
                                        .find { it.type == Long::class.javaPrimitiveType }
                                field?.isAccessible = true
                                field?.get(cf.thisObject) as? Long
                            } catch (e: Throwable) {
                                try {
                                    WidgetChatListActions.`access$getMessageId$p`(
                                        cf.thisObject as WidgetChatListActions
                                    )
                                } catch (e2: Throwable) { null }
                            } ?: return@Hook

                            val channelId = try {
                                val field = WidgetChatListActions::class.java.declaredFields
                                    .find { it.type == Long::class.javaPrimitiveType && it.name.contains("channelId", ignoreCase = true) }
                                    ?: WidgetChatListActions::class.java.declaredFields
                                        .filter { it.type == Long::class.javaPrimitiveType }
                                        .getOrNull(1)
                                field?.isAccessible = true
                                field?.get(cf.thisObject) as? Long
                            } catch (e: Throwable) {
                                try {
                                    WidgetChatListActions.`access$getChannelId$p`(
                                        cf.thisObject as WidgetChatListActions
                                    )
                                } catch (e2: Throwable) { 0L }
                            } ?: 0L

                            val entry = translatedMessages[messageId]
                            val translateBtn = linearLayout.findViewById<TextView>(buttonId)
                                ?: TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                                    id = buttonId
                                    linearLayout.addView(this)
                                }
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
