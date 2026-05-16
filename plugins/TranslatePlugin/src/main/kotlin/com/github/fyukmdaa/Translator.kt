// src/plugins/TranslatePlugin/src/main/kotlin/com/github/fyukmdaa/Translator.kt
package com.github.fyukmdaa

import com.aliucord.Logger

object Translator {
    private val logger = Logger("TranslatePlugin")

    /**
     * 🔧 切り分け用: 実際の翻訳は行わず、入力テキストをそのまま返す
     * これで「プラグイン→Translator→結果表示」のフローが動くか確認
     */
    fun translate(text: String, targetLang: String): String {
        logger.info("TEST: translate called")
        logger.info("TEST: input text: " + text.substring(0, kotlin.math.min(30, text.length)))
        logger.info("TEST: target lang: " + targetLang)
        
        // 🔧 実際の翻訳の代わりに、入力テキストにプレフィックスをつけて返す
        val result = "[TEST] " + text
        
        logger.info("TEST: returning: " + result.substring(0, kotlin.math.min(50, result.length)))
        return result
    }
}
