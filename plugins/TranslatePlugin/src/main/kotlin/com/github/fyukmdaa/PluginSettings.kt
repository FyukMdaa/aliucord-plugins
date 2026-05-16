package com.fyukmdaa.translateplugin

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

        // 翻訳先言語
        val langInput = TextInput(ctx).apply {
            hint = "Target language code (ex. ja, en, zh-CN)"
        }
        val langEditText = langInput.editText!!.apply {
            maxLines = 1
            setText(settings.getString("targetLang", "ja"))
        }
        addView(langInput)

        addView(Divider(ctx))

        // 全体翻訳モードのデフォルト
        val autoSwitch = Switch(ctx).apply {
            text = "Enable full translation by default"
            isChecked = settings.getBool("autoTranslate", false)
            setOnCheckedChangeListener { _, checked ->
                settings.setBool("autoTranslate", checked)
            }
        }
        addView(autoSwitch)

        addView(Divider(ctx))

        // 保存ボタン
        addView(Button(ctx).apply {
            text = "Save"
            setOnClickListener {
                val lang = langEditText.text.toString().trim()
                if (lang.isNotEmpty()) {
                    settings.setString("targetLang", lang)
                    Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }
}
