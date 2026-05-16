package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import org.json.JSONArray
import java.net.URLEncoder

object Translator {
    private val logger = Logger("TranslatePlugin")

    fun translate(text: String, targetLang: String): String {
        // 🔧 文字列テンプレート不使用: 安全な連結のみ
        val textPreview = if (text.length > 50) text.substring(0, 50) + "..." else text
        logger.info("[1/4] translate start. text=" + textPreview + ", lang=" + targetLang)

        // URL エンコード
        val encodedText: String
        try {
            encodedText = URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            logger.error("URL encode failed: " + e.message, e)
            throw RuntimeException("URL encode error", e)
        }
        
        // 🔧 URL 構築も連結で
        val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx" +
                "&sl=auto" +
                "&tl=" + targetLang +
                "&dt=t" +
                "&q=" + encodedText
        
        logger.debug("[2/4] Request URL: " + url)

        // HTTP リクエスト
        val response: Http.Response
        try {
            response = Http.Request(url, "GET").apply {
                setHeader("User-Agent", "Mozilla/5.0")
            }.execute()
        } catch (e: Exception) {
            logger.error("HTTP execute failed: " + e.message, e)
            throw RuntimeException("HTTP request failed: " + e.message, e)
        }

        logger.debug("[3/4] HTTP status: " + response.statusCode + ", ok=" + response.ok())

        if (!response.ok()) {
            val bodyPreview: String            try {
                val full = response.text()
                bodyPreview = if (full.length > 200) full.substring(0, 200) + "..." else full
            } catch (_: Exception) {
                bodyPreview = "(read failed)"
            }
            val msg = "HTTP " + response.statusCode + ": " + bodyPreview
            logger.error(msg, null)
            throw RuntimeException(msg)
        }

        // レスポンス本文取得
        val body: String
        try {
            body = response.text()
        } catch (e: Exception) {
            logger.error("Failed to read response body: " + e.message, e)
            throw RuntimeException("Response read error", e)
        }
        
        val bodyPreview = if (body.length > 300) body.substring(0, 300) + "..." else body
        logger.debug("[4/4] Response body: " + bodyPreview)

        return parseResponseSafe(body)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun parseResponseSafe(body: String): String {
        logger.debug("[PARSE] start. body length: " + body.length)
        
        val json: JSONArray
        try {
            json = JSONArray(body)
            logger.debug("[PARSE] root array length: " + json.length())
        } catch (e: Exception) {
            logger.error("[PARSE] JSONArray creation failed: " + e.message, e)
            throw e
        }
        
        if (json.length() == 0) {
            logger.warn("[PARSE] empty root array", null)
            return ""
        }
        
        val sections: JSONArray
        try {
            sections = json.getJSONArray(0)
            logger.debug("[PARSE] sections length: " + sections.length())
        } catch (e: Exception) {
            logger.error("[PARSE] no array at index 0: " + e.message, e)            return ""
        }
        
        val result = StringBuilder()
        var idx = 0
        val len = sections.length()
        
        logger.debug("[PARSE] looping " + len + " segments")
        while (idx < len) {
            try {
                val segment = sections.optJSONArray(idx)
                if (segment != null && segment.length() > 0) {
                    val part = segment.optString(0)
                    if (part != null && part.isNotEmpty()) {
                        result.append(part)
                        val partPreview = if (part.length > 30) part.substring(0, 30) + "..." else part
                        logger.debug("[PARSE] segment " + idx + ": " + partPreview)
                    }
                }
            } catch (e: Exception) {
                logger.warn("[PARSE] failed at idx " + idx + ": " + e.message, e)
            }
            idx = idx + 1
        }
        
        val finalResult = result.toString()
        val resultPreview = if (finalResult.length > 100) finalResult.substring(0, 100) + "..." else finalResult
        logger.info("[PARSE] success: " + resultPreview)
        return finalResult
    }
}
