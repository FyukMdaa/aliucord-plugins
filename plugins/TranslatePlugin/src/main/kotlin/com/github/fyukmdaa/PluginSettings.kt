package com.fyukmdaa.translateplugin

import android.annotation.SuppressLint
import android.content.Context
import android.widget.*
import com.aliucord.api.SettingsAPI
import com.aliucord.views.Divider
import com.discord.app.AppFragment

class PluginSettings(private val settings: SettingsAPI) : AppFragment() {

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // 対象言語
        layout.addView(TextView(ctx).apply { text = "翻訳先言語コード (例: ja, en, zh-CN)" })
        val langInput = EditText(ctx).apply {
            setText(settings.getString("targetLang", "ja"))
        }
        layout.addView(langInput)

        layout.addView(Divider(ctx))

        // 全体翻訳モード
        layout.addView(TextView(ctx).apply { text = "チャンネル全体翻訳" })
        val autoSwitch = Switch(ctx).apply {
            text = "有効にする"
            isChecked = settings.getBool("autoTranslate", false)
            setOnCheckedChangeListener { _, checked ->
                settings.setBool("autoTranslate", checked)
            }
        }
        layout.addView(autoSwitch)

        // 保存ボタン
        layout.addView(Button(ctx).apply {
            text = "保存"
            setOnClickListener {
                settings.setString("targetLang", langInput.text.toString().trim())
                Toast.makeText(ctx, "保存しました", Toast.LENGTH_SHORT).show()
            }
        })

        // ScrollView に包んでセット
        val scroll = ScrollView(ctx).apply { addView(layout) }
        (view as? FrameLayout)?.addView(scroll)
            ?: (view as? LinearLayout)?.addView(scroll)
    }
}
