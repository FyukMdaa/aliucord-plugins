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

    // messageId -> 翻訳済みデータ
    private data class TranslatedEntry(
        val original: String,
        val translated: String,
        var showingTranslation: Boolean = true
    )
    private val translatedMessages = mutableMapOf<Long, TranslatedEntry>()

    // チャンネルIDごとの全体翻訳ON状態
    private val autoChannels = mutableSetOf<Long>()

    private fun targetLang() = settings.getString("targetLang", "ja")

    // 🔧 リフレクションヘルパー
    private inline fun <reified T> Any.getPrivateFieldOrNull(name: String): T? {
        return this::class.java.declaredFields
            .firstOrNull { it.name == name }
            ?.also { it.isAccessible = true }
            ?.get(this) as? T
    }

    override fun start(ctx: Context) {
        val buttonId = View.generateViewId()
        val autoButtonId = View.generateViewId()

        val messageContextMenu = WidgetChatListActions::class.java

        patcher.patch(
            messageContextMenu,
            "onViewCreated",
            arrayOf(View::class.java, Bundle::class.java),
            Hook { cf ->
                // 🔴 最重要: 元のメソッドを先に実行して、通常のメニュー表示を有効にする
                cf.call()

                val menu = cf.thisObject as WidgetChatListActions
                // cf.args[0] は onViewCreated 第1引数の View
                val rootView = cf.args[0] as? View ?: return@Hook
                val ctx2 = rootView.context

                // NestedScrollView 内の LinearLayout を探す（構造が変わっている可能性があるため柔軟に）
                val linearLayout = if (rootView is LinearLayout) {
                    rootView
                } else {
                    (rootView as? NestedScrollView)?.getChildAt(0) as? LinearLayout ?: return@Hook
                }

                // 🔧 messageId と channelId をリフレクションで取得
                // ※ 元のメソッド実行後にアクセスする必要がある
                val messageId = menu.getPrivateFieldOrNull<Long>("messageId") ?: return@Hook
                val channelId = menu.getPrivateFieldOrNull<Long>("channelId")

                // 📝 Message オブジェクトと content 取得
                val messageField = WidgetChatListActions::class.java.declaredFields
                    .firstOrNull { it.type.simpleName == "Message" }
                    ?.also { it.isAccessible = true }
                val message = messageField?.get(menu)
                
                val content = message?.javaClass?.getMethod("getContent")?.invoke(message) as? String
                    ?: run {
                        message?.javaClass?.declaredFields
                            ?.firstOrNull { it.type == String::class.java }
                            ?.also { it.isAccessible = true }
                            ?.get(message) as? String
                    } ?: return@Hook

                val entry = translatedMessages[messageId]

                // 🔽 翻訳トグルボタン（既存のボタンと重複しないよう ID 管理）
                // 注意: onViewCreated は再呼び出しされる可能性があるため、既存ビューのチェックを入れると安全です
                if (linearLayout.findViewById<View>(buttonId) == null) {
                    linearLayout.addView(TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        id = buttonId
                        text = when {
                            entry == null -> "Translate message"
                            entry.showingTranslation -> "Show Original"
                            else -> "Show Translation"
                        }
                        setOnClickListener {
                            if (entry == null) {
                                Utils.threadPool.execute {
                                    try {
                                        val result = Translator.translate(content, targetLang())
                                        translatedMessages[messageId] = TranslatedEntry(
                                            original = content,
                                            translated = result
                                        )
                                        Utils.mainThread.post {
                                            showTranslation(ctx2, content, result)
                                            menu.dismiss()
                                        }
                                    } catch (e: Exception) {
                                        Utils.mainThread.post {
                                            Utils.showToast("Translate error: ${e.message}")
                                        }
                                    }
                                }
                            } else {
                                entry.showingTranslation = !entry.showingTranslation
                                showTranslation(ctx2, entry.original, entry.translated)
                                menu.dismiss()
                            }
                        }
                    })
                }

                // 🔽 全体翻訳トグルボタン
                if (linearLayout.findViewById<View>(autoButtonId) == null) {
                    linearLayout.addView(TextView(ctx2, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                        id = autoButtonId
                        text = if (channelId != null && channelId in autoChannels) {
                            "Disable Full Translate"
                        } else {
                            "Enable Full Translate"
                        }
                        setOnClickListener {
                            channelId?.let { cid ->
                                if (cid in autoChannels) {
                                    autoChannels.remove(cid)
                                    text = "Enable Full Translate"
                                } else {
                                    autoChannels.add(cid)
                                    text = "Disable Full Translate"
                                }
                            } ?: Utils.showToast("Channel ID not available")
                        }
                    })
                }
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
