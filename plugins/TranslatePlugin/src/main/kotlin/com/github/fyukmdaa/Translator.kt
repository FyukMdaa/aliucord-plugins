// src/plugins/TranslatePlugin/src/main/kotlin/com/github/fyukmdaa/Translator.kt
package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import org.json.JSONArray

object Translator {
    private val logger = Logger("TranslatePlugin")

    fun translate(text: String, targetLang: String): String {
        // 1. URL 構築（参考コードと同じ構造）
        val queryBuilder = Http.QueryBuilder("https://translate.googleapis.com/translate_a/single")
            .append("client", "gtx")
            .append("sl", "auto")
            .append("tl", targetLang)
            .append("dt", "t")
            .append("q", text)
        
        // 2. HTTP リクエスト（参考コードと同じヘッダー）
        val request = Http.Request(queryBuilder.toString(), "GET").apply {
            setHeader("Content-Type", "application/json")
            setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4592.0 Safari/537.36")
        }
        
        val response = request.execute()
        
        // 3. エラー処理
        if (!response.ok()) {
            val msg = "HTTP " + response.statusCode
            logger.error(msg, null)
            throw RuntimeException(msg)
        }
        
        // 4. レスポンス取得
        val body = response.text()
        
        // 5. JSON 解析（参考コードと同じ構造）
        val parsedJson = JSONArray(body)
        val translatedSections = parsedJson.getJSONArray(0)
        
        // 6. 翻訳文抽出（while ループで安全に）
        val translatedText = buildString {
            var i = 0
            val len = translatedSections.length()
            while (i < len) {
                val segment = translatedSections.getJSONArray(i)
                val part = segment.getString(0)
                append(part)
                i = i + 1
            }
        }
        
        return translatedText
    }
}
