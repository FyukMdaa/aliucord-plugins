package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import org.json.JSONArray
import java.net.URLEncoder

object Translator {
    private val logger = Logger("TranslatePlugin")

    fun translate(text: String, targetLang: String = "ja"): String {
        logger.info("翻訳開始: text=${text.take(50)}..., lang=$targetLang")

        // 🔧 URL エンコード: URLEncoder を使用
        val encodedText = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            logger.error("URL encode failed", e)
            throw RuntimeException("URL encode error: ${e.message}", e)
        }
        
        val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx" +
                "&sl=auto" +
                "&tl=$targetLang" +
                "&dt=t" +
                "&q=$encodedText"
        
        logger.debug("URL: $url")

        // HTTP リクエスト
        val response = try {
            Http.Request(url, "GET").apply {
                setHeader("User-Agent", "Mozilla/5.0")
            }.execute()
        } catch (e: Exception) {
            logger.error("HTTP request failed", e)  // 🔧 第2引数は Throwable
            throw RuntimeException("Network error: ${e.message}", e)
        }

        if (!response.ok()) {
            val bodyPreview = response.text().take(200)
            val msg = "HTTP ${response.statusCode}: $bodyPreview"
            logger.error(msg, null)  // 🔧 例外がない場合は null
            throw RuntimeException(msg)
        }

        val body = response.text()
        logger.debug("Response: ${body.take(300)}...")

        return parseGoogleTranslateResponse(body)
    }

    /**
     * 🔧 難読化環境対応: Kotlin 拡張関数・イテレータ構文を一切使わない
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun parseGoogleTranslateResponse(body: String): String {
        val json = JSONArray(body)
        
        if (json.length() == 0) {
            logger.warn("Empty response array", null)
            return ""
        }
        
        val sections: JSONArray = try {
            json.getJSONArray(0)
        } catch (e: Exception) {
            logger.warn("No translations array at index 0", e)
            return ""
        }
        
        val result = StringBuilder()
        
        // 🔧 伝統的な while ループ + 手動インクリメント
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
            }
            index = index + 1
        }
        
        val finalResult = result.toString()
        logger.debug("Parsed: ${finalResult.take(100)}...")
        return finalResult
    }
}
