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
            setText(settings.getString("targetLang", "ja"))
        }
        addView(langInput)

        addView(Divider(ctx))

        // 原文を併記するオプション
        val showOriginalCheckbox = CheckBox(ctx).apply {
            text = "Show original text with translation"
            isChecked = settings.getBool("showOriginal", true)
            setOnCheckedChangeListener { _, isChecked ->
                settings.setBool("showOriginal", isChecked)
            }
        }
        addView(showOriginalCheckbox)

        addView(Divider(ctx))

        // 保存ボタン
        addView(Button(ctx).apply {
            text = "Save"
            setOnClickListener {
                val lang = langEditText.text.toString().trim()
                if (lang.isNotEmpty()) {
                    settings.setString("targetLang", lang)
                    Toast.makeText(ctx, "Saved!", Toast.LENGTH_SHORT).show()
                    close()
                } else {
                    Toast.makeText(ctx, "Please enter a language code", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }
}
