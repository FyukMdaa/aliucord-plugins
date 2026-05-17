package com.github.fyukmdaa

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.view.View
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.aliucord.views.Divider

class PluginSettings(private val settings: SettingsAPI) : SettingsPage() {

    // サポートされている全言語データ (表示名 to コード)
    private val allLanguages = listOf(
        "Afrikaans" to "af", "Albanian" to "sq", "Amharic" to "am", "Arabic" to "ar",
        "Armenian" to "hy", "Assamese" to "as", "Azerbaijani" to "az", "Basque" to "eu",
        "Bengali" to "bn", "Bulgarian" to "bg", "Burmese" to "my", "Catalan" to "ca",
        "Cherokee" to "chr", "Chinese (Hong Kong)" to "zh-HK", "Chinese (Simplified)" to "zh-CN",
        "Chinese (Traditional)" to "zh-TW", "Croatian" to "hr", "Czech" to "cs",
        "Danish" to "da", "Dutch" to "nl", "English (UK)" to "en-GB", "English (US)" to "en",
        "Estonian" to "et", "Filipino" to "fil", "Finnish" to "fi", "French" to "fr",
        "French (Canada)" to "fr-CA", "Galician" to "gl", "Georgian" to "ka",
        "German" to "de", "Greek" to "el", "Gujarati" to "gu", "Hebrew" to "iw",
        "Hindi" to "hi", "Hungarian" to "hu", "Icelandic" to "is", "Indonesian" to "id",
        "Irish" to "ga", "Italian" to "it", "Japanese" to "ja", "Kannada" to "kn",
        "Kazakh" to "kk", "Khmer" to "km", "Korean" to "ko", "Lao" to "lo",
        "Latvian" to "lv", "Lithuanian" to "lt", "Macedonian" to "mk", "Malay" to "ms",
        "Malayalam" to "ml", "Marathi" to "mr", "Mongolian" to "mn", "Nepali" to "ne",
        "Norwegian" to "no", "Oriya" to "or", "Persian" to "fa", "Polish" to "pl",
        "Portuguese (Brazil)" to "pt-BR", "Portuguese (Portugal)" to "pt-PT", "Punjabi" to "pa",
        "Romanian" to "ro", "Russian" to "ru", "Serbian" to "sr", "Sinhala" to "si",
        "Slovak" to "sk", "Slovenian" to "sl", "Spanish" to "es", "Spanish (Latin America)" to "es-419",
        "Swahili" to "sw", "Swedish" to "sv", "Tamil" to "ta", "Telugu" to "te",
        "Thai" to "th", "Turkish" to "tr", "Ukrainian" to "uk", "Urdu" to "ur",
        "Uzbek" to "uz", "Vietnamese" to "vi", "Welsh" to "cy", "Zulu" to "zu"
    )

    private fun getLanguageName(code: String): String {
        return allLanguages.find { it.second == code }?.first ?: "Unknown ($code)"
    }

    @SuppressLint("SetTextI18n")
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Translate Settings")

        val ctx = view.context

        // 1. セクションラベル
        val label = TextView(ctx).apply {
            text = "Target Language"
            textSize = 14f
            setPadding(0, 16, 0, 4)
        }
        addView(label)

        // 2. 現在の選択状態を表示・タップでダイアログを開くボタン
        val langSelectButton = Button(ctx).apply {
            val currentCode = settings.getString("targetLang", "ja")
            text = "${getLanguageName(currentCode)} [ $currentCode ]"
            
            setOnClickListener {
                showLanguageSelectorDialog(ctx) { selectedName, selectedCode ->
                    settings.setString("targetLang", selectedCode)
                    text = "$selectedName [ $selectedCode ]"
                    Toast.makeText(ctx, "Target language updated to $selectedName", Toast.LENGTH_SHORT).show()
                }
            }
        }
        addView(langSelectButton)

        addView(Divider(ctx))

        // 3. オプションチェックボックス
        val showOriginalCheckbox = CheckBox(ctx).apply {
            text = "Show original text with translation"
            isChecked = settings.getBool("showOriginal", true)
            setPadding(0, 16, 0, 16)
            setOnCheckedChangeListener { _, isChecked ->
                settings.setBool("showOriginal", isChecked)
            }
        }
        addView(showOriginalCheckbox)
    }

    /**
     * 検索フィルター付きの言語選択ダイアログを表示する
     */
    private fun showLanguageSelectorDialog(
        context: android.content.Context,
        onSelected: (name: String, code: String) -> Unit
    ) {
        // レイアウトを動的に生成
        val dialogLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }

        // 検索窓
        val searchView = EditText(context).apply {
            hint = "Search language..."
            maxLines = 1
        }
        dialogLayout.addView(searchView)

        // リスト表示用
        val listView = ListView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                800 // ダイアログが縦に伸びすぎないよう高さを適度に固定
            )
        }
        dialogLayout.addView(listView)

        // 現在の一覧を保持する動的なリスト
        var filteredList = allLanguages.toList()
        
        val adapter = ArrayAdapter(
            context,
            android.R.layout.simple_list_item_1,
            filteredList.map { "${it.first} (${it.second})" }
        )
        listView.adapter = adapter

        val dialog = AlertDialog.Builder(context)
            .setTitle("Select Target Language")
            .setView(dialogLayout)
            .setNegativeButton("Cancel", null)
            .create()

        // タイピングに合わせてリアルタイムフィルター
        searchView.doAfterTextChanged { text ->
            val query = text.toString().trim()
            filteredList = if (query.isEmpty()) {
                allLanguages
            } else {
                allLanguages.filter { 
                    it.first.contains(query, ignoreCase = true) || 
                    it.second.contains(query, ignoreCase = true) 
                }
            }
            
            adapter.clear()
            adapter.addAll(filteredList.map { "${it.first} (${it.second})" })
            adapter.notifyDataSetChanged()
        }

        // アイテム選択時
        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = filteredList[position]
            onSelected(selected.first, selected.second)
            dialog.dismiss()
        }

        dialog.show()
    }
}
