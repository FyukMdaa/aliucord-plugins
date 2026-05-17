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
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.widgets.chat.list.actions.WidgetChatListActions
import com.discord.widgets.chat.list.entries.MessageEntry
import com.lytefast.flexinput.R
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.regex.Pattern

@AliucordPlugin
class TranslatePlugin : Plugin() {

    init {
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(settings)
    }

    private data class TranslatedEntry(
        val original: String,
        val translated: String,
        var showingTranslation: Boolean = true,
        val channelId: Long // チャンネル一括操作用に保持
    )

    private val translatedMessages = mutableMapOf<Long, TranslatedEntry>()
    private val autoChannels = mutableSetOf<Long>()
    private val translatingIds = mutableSetOf<Long>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var chatList: WidgetChatList? = null
    private var adapterField: Field? = null
    private var dataField: Field? = null

    private val discordTagPattern = Pattern.compile("<(?:a?:\\w+:\\d+|@&?\\d+|#\\d+|@!\\d+)>")

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
        val list = chatList ?: return
        mainHandler.post {
            try {
                if (adapterField == null) {
                    adapterField = WidgetChatList::class.java.declaredFields
                        .find { it.type.name.contains("WidgetChatListAdapter") }
                    adapterField?.isAccessible = true
                }
                val adapter = adapterField?.get(list) ?: return@post

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

                val data = dataField?.get(adapter) as? List<*> ?: return@post
                val index = data.indexOfFirst { (it as? MessageEntry)?.message?.id == id }
                if (index == -1) return@post

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
            } catch (e: Exception) {
                logger.error("rerenderMessage failed", e)
            }
        }
    }

    private fun translateAsync(
        messageId: Long,
        channelId: Long,
        content: String,
        lang: String,
        onComplete: (() -> Unit)? = null
    ) {
        if (messageId in translatingIds) return
        translatingIds.add(messageId)
        Thread {
            try {
                val tagsList = mutableListOf<String>()
                val matcher = discordTagPattern.matcher(content)
                val sb = StringBuffer()
                
                while (matcher.find()) {
                    tagsList.add(matcher.group())
                    matcher.appendReplacement(sb, " __TAG_${tagsList.size - 1}__ ")
                }
                matcher.appendTail(sb)
                val processedContent = sb.toString()

                var result = Translator.translate(processedContent, lang)

                if (result.isNotEmpty()) {
                    for (i in tagsList.indices) {
                        val placeholderPattern = Pattern.compile("\\s*__TAG_${i}__\\s*")
                        val tagMatcher = placeholderPattern.matcher(result)
                        if (tagMatcher.find()) {
                            result = tagMatcher.replaceAll(tagsList[i])
                        }
                    }

                    if (result.contains("\\u003c")) result = result.replace("\\u003c", "<")
                    if (result.contains("\\u003e")) result = result.replace("\\u003e", ">")

                    // 【除外言語/不要翻訳ガード】
                    // 翻訳結果が元のテキストと完全に一致する場合、または翻訳先が「ja」かつ結果に日本語が含まれていないなどの不整合を防ぐため、
                    // 原文と変化がなければ翻訳を適用せずスキップ（無駄な描画更新を防止）
                    if (result.trim().equals(content.trim(), ignoreCase = true)) {
                        return@Thread
                    }

                    translatedMessages[messageId] = TranslatedEntry(content, result, true, channelId)
                    onComplete?.invoke()
                    rerenderMessage(messageId)
                }
            } catch (e: Exception) {
                logger.error("Translation processing failed", e)
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

            try {
                patcher.patch(WidgetChatList::class.java.getDeclaredConstructor(), Hook {
                    chatList = it.thisObject as WidgetChatList
                })
            } catch (e: Exception) {
                logger.error("Failed to patch WidgetChatList constructor", e)
            }

            // ── 1. Message.getContent フック ────────────────────────
            try {
                patcher.patch(Message::class.java, "getContent", emptyArray(), Hook { cf ->
                    try {
                        val message = cf.thisObject as Message
                        val entry = translatedMessages[message.id]

                        if (entry == null && message.channelId in autoChannels) {
                            val rawContent = getRawContent(message)
                            if (!rawContent.isNullOrEmpty() && !isBlankSafe(rawContent)) {
                                translateAsync(message.id, message.channelId, rawContent, targetLang())
                            }
                        }

                        if (entry != null && entry.showingTranslation) {
                            val display = if (showOriginal()) {
                                "${entry.original}\n---\n${entry.translated}"
                            } else {
                                entry.translated
                            }
                            cf.result = display
                        }
                    } catch (e: Exception) { }
                })
            } catch (e: Exception) {
                logger.error("Failed to patch Message.getContent", e)
            }

            // ── 2. configureUI フック ───────────────────────────────
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

                            val model = cf.args[0] as? WidgetChatListActions.Model ?: return@Hook
                            val message = model.message
                            val context = try { menu.requireContext() } catch (e: Exception) { null }

                            binding.a.findViewById<TextView>(buttonId)?.setOnClickListener {
                                val entry = translatedMessages[message.id]
                                if (entry == null) {
                                    val rawContent = getRawContent(message) ?: return@setOnClickListener
                                    if (isBlankSafe(rawContent)) return@setOnClickListener
                                    translateAsync(message.id, message.channelId, rawContent, targetLang()) {
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
                                val currentChannelId = message.channelId
                                if (currentChannelId in autoChannels) {
                                    autoChannels.remove(currentChannelId)
                                    
                                    // 【自動翻訳OFF連動：表示リセット】
                                    // 該当チャンネルに属するメッセージの表示フラグを一括で引き剥がし、再レンダリングをかける
                                    translatedMessages.filterValues { it.channelId == currentChannelId }
                                        .forEach { (id, entry) ->
                                            entry.showingTranslation = false
                                            rerenderMessage(id)
                                        }

                                    context?.let { Toast.makeText(it, "Auto-Translate OFF", Toast.LENGTH_SHORT).show() }
                                } else {
                                    autoChannels.add(currentChannelId)
                                    context?.let { Toast.makeText(it, "Auto-Translate ON", Toast.LENGTH_SHORT).show() }
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

            // ── 3. onViewCreated フック ─────────────────────────────
            try {
                patcher.patch(
                    messageContextMenu,
                    "onViewCreated",
                    arrayOf(View::class.java, Bundle::class.java),
                    Hook { cf ->
                        try {
                            val linearLayout = (cf.args[0] as? NestedScrollView)?.getChildAt(0) as? LinearLayout ?: return@Hook
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
                                    WidgetChatListActions.`access$getMessageId$p`(cf.thisObject as WidgetChatListActions)
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
                                    WidgetChatListActions.`access$getChannelId$p`(cf.thisObject as WidgetChatListActions)
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
