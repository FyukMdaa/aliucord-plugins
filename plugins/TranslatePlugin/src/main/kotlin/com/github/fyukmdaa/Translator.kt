package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import org.json.JSONArray

object Translator {
    private val logger = Logger("TranslatePlugin")

    fun translate(text: String, targetLang: String = "ja"): String {
        logger.info("翻訳開始: text=${text.take(50)}..., lang=$targetLang")

        // URL 構築
        val baseUrl = "https://translate.googleapis.com/translate_a/single"
        val url = "$baseUrl?client=gtx&sl=auto&tl=$targetLang&dt=t&q=${Http.urlEncode(text)}"
        
        logger.debug("URL: $url")

        // HTTP リクエスト
        val response = try {
            Http.Request(url, "GET").apply {
                setHeader("User-Agent", "Mozilla/5.0")
            }.execute()
        } catch (e: Exception) {
            logger.error("HTTP request failed", e)
            throw RuntimeException("Network error: ${e.message}", e)
        }

        if (!response.ok()) {
            val msg = "HTTP ${response.statusCode}: ${response.text().take(200)}"
            logger.error(msg)
            throw RuntimeException(msg)
        }

        val body = response.text()
        logger.debug("Response: ${body.take(300)}...")

        // 解析（例外は上位に投げる）
        return parseGoogleTranslateResponse(body)
    }

    /**
     * 🔧 難読化環境対応: Kotlin 拡張関数・イテレータ構文を一切使わない
     * 純粋なインデックスアクセス + while ループのみで実装
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun parseGoogleTranslateResponse(body: String): String {
        val json = JSONArray(body)
        
        // 防御的チェック
        if (json.length() == 0) {
            logger.warn("Empty response array")
            return ""
        }
        
        // 最初の要素 [0] が翻訳セグメントの配列
        val sections: JSONArray = try {
            json.getJSONArray(0)
        } catch (e: Exception) {
            logger.warn("No translations array at index 0", e)
            return ""
        }
        
        val result = StringBuilder()
        
        // 🔧 重要: 従来の while ループ + 手動インクリメント
        // "for (i in 0 until n)" や "for (item in array)" は使わない
        var index = 0
        val sectionsLength = sections.length()
        while (index < sectionsLength) {
            try {
                val segment = sections.optJSONArray(index)
                if (segment != null && segment.length() > 0) {
                    val translatedPart = segment.optString(0)
                    if (translatedPart != null && translatedPart.isNotEmpty()) {
                        result.append(translatedPart)
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to parse segment at index $index", e)
                // 一部失敗しても続行
            }
            index = index + 1  // 🔧 手動インクリメント（++ も避ける）
        }
        
        val finalResult = result.toString()
        logger.debug("Parsed translation: ${finalResult.take(100)}...")
        return finalResult
    }
}
