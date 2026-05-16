package com.github.fyukmdaa

import android.annotation.SuppressLint
import android.view.View
import android.widget.*
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.views.Divider
import com.aliucord.views.TextInput

class PluginSettings(private val settings: SettingsAPI) : SettingsPage() {

    @SuppressLint("SetTextI18n")
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("TranslatePlugin")

        val ctx = view.context

        // 翻訳先言語設定
        val langInput = TextInput(ctx, "Target language code (ex. ja, en, zh-CN)")
        val langEditText = langInput.editText!!.apply {
            maxLines = 1
            // 保存されている設定があれば読み込み、なければ "ja" をデフォルトにする
            setText(settings.getString("targetLang", "ja"))
        }
        addView(langInput)

        addView(Divider(ctx))

        // 保存ボタン
        addView(Button(ctx).apply {
            text = "Save"
            setOnClickListener {
                val lang = langEditText.text.toString().trim()
                if (lang.isNotEmpty()) {
                    // 設定を保存
                    settings.setString("targetLang", lang)
                    Toast.makeText(ctx, "Saved! Target: $lang", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "Please enter a language code", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }
}
