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
    // messageId -> Entry
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

            // ── 0. 【新機能】メッセージ表示時にテキストを書き換えるパッチ ─────────────────
            try {
                // チャットリストのアイテム（1行1メッセージ）のクラス
                val itemClass = Class.forName("com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage")
                // バインディングクラス
                val bindingClass = Class.forName("com.discord.databinding.WidgetChatListItemMessageBinding")

                patcher.patch(itemClass, "onConfigure", null, Hook { cf ->
                    try {
                        // メッセージオブジェクトを取得
                        val message = cf.args[0] as? Message ?: return@Hook
                        
                        // 翻訳済みデータがあるか確認
                        val entry = translatedMessages[message.id]
                        if (entry != null && entry.showingTranslation) {
                            // バインディングを取得
                            val bindingField = itemClass.getDeclaredField("binding").apply { isAccessible = true }
                            val binding = bindingField.get(cf.thisObject)
                            
                            // バインディングからテキストビューを探す
                            // chatListContentView はメッセージ内容を含むビュー
                            val contentViewField = bindingClass.getDeclaredField("chatListContentView").apply { isAccessible = true }
                            val contentView = contentViewField.get(binding) as? View
                            
                            if (contentView != null) {
                                // R.i.chat_list_item_message はメッセージ本文のTextViewのID
                                val textView = contentView.findViewById<TextView>(R.i.chat_list_item_message)
                                if (textView != null) {
                                    textView.text = entry.translated
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 個別のエラーは無視してログだけ出す（クラッシュ防止）
                        // logger.error("Error in message rewrite", null) 
                    }
                })
                logger.info("✅ Message rewrite patch applied")
            } catch (e: Exception) {
                logger.error("❌ Failed to apply message rewrite patch", e)
            }

            // ── 1. configureUI Patch ───────────────────────────────
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
                                                // ダイアログは出さず、トーストだけ出す
                                                Toast.makeText(menu.requireContext(), "Message Translated!", Toast.LENGTH_SHORT).show()
                                                menu.dismiss()
                                                
                                                // チャット画面を強制リロードして書き換えを反映させる
                                                // リストをスクロールさせるなどして再描画を促すのが理想だが、
                                                // 簡易的にユーザーに少しスクロールしてもらうか、
                                                // もし可能なら adapter.notifyDataSetChanged() を叩きたいが
                                                // ここでは安全策としてトーストのみ。
                                                // (メッセージをスクロールして再表示させると翻訳が見えます)
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
                                // すでに翻訳済みの場合、オリジナルとトグルする
                                entry.showingTranslation = !entry.showingTranslation
                                Toast.makeText(menu.requireContext(), if(entry.showingTranslation) "Showing Translation" else "Showing Original", Toast.LENGTH_SHORT).show()
                                menu.dismiss()
                                // 再描画させるために何らかのアクションが必要だが、
                                // 手軽な方法としてユーザーがスクロールすると反映される。
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

    // 今はダイアログを使わないのでこのメソッドは不要ですが、一応残しておきます
    private fun showTranslation(ctx: Context, original: String, translated: String) {
        try {
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Translation")
                .setMessage("$original\n\n---\n\n$translated")
                .setPositiveButton("Close", null)
                .show()
        } catch (e: Exception) {
            logger.error("Failed to show dialog: ${e.javaClass.simpleName}", null)
        }
    }
}
