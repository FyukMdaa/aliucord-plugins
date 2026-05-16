// src/plugins/TranslatePlugin/src/main/kotlin/com/github/fyukmdaa/Translator.kt
package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import org.json.JSONArray

object Translator {
    private val logger = Logger("TranslatePlugin")

    fun translate(text: String, targetLang: String = "ja"): String {
        logger.info("翻訳開始: text=${text.take(50)}..., lang=$targetLang")

        val url = Http.QueryBuilder("https://translate.googleapis.com/translate_a/single")
            .append("client", "gtx")
            .append("sl", "auto")
            .append("tl", targetLang)
            .append("dt", "t")
            .append("q", text)
            .toString()

        logger.debug("URL: $url")

        val response = try {
            Http.Request(url, "GET").apply {
                setHeader("Content-Type", "application/json")
                setHeader("User-Agent", "Mozilla/5.0")
            }.execute()
        } catch (e: Exception) {
            logger.error("HTTP request failed", e)
            throw e
        }

        logger.debug("HTTP status: ${response.statusCode}")
        if (!response.ok()) {
            throw Exception("HTTP ${response.statusCode}: ${response.text().take(200)}")
        }

        val body = response.text()
        logger.debug("Response body: ${body.take(500)}...")

        return try {
            parseTranslationResponse(body)
        } catch (e: Exception) {
            logger.error("Failed to parse response", e)
            throw e
        }
    }

    /**
     * Google Translate API のレスポンスを解析する
     * 🔧 Iterator 関連のキャストエラーを避けるため、伝統的な while ループを使用
     */
    private fun parseTranslationResponse(body: String): String {
        val json = JSONArray(body)
        
        // 最初の配列 [0] が翻訳セグメントの配列
        if (json.length() == 0) return ""
        
        val sections = json.getJSONArray(0)
        val result = StringBuilder()
        
        // 🔧 重要: Kotlin の "for (i in 0 until ...)" は避ける
        // 難読化環境では IntIterator へのキャストで失敗する可能性があるため、
        // 従来の while + 手動インクリメントを使用
        var i = 0
        while (i < sections.length()) {
            try {
                val segment = sections.getJSONArray(i)
                if (segment.length() > 0) {
                    val translatedPart = segment.optString(0)
                    if (translatedPart.isNotEmpty()) {
                        result.append(translatedPart)
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse segment at index $i", e)
                // 一部のパース失敗は全体を失敗させず続行
            }
            i++  // 🔧 手動インクリメント
        }
        
        return result.toString()
    }
}
