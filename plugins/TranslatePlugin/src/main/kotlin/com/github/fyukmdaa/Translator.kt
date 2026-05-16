package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import org.json.JSONArray
import java.net.URLEncoder

object Translator {
    private val logger = Logger("TranslatePlugin")

    fun translate(text: String, targetLang: String): String {
        // 1. 入力ログ（単純連結）
        var textPreview = ""
        if (text.length > 50) {
            textPreview = text.substring(0, 50) + "..."
        } else {
            textPreview = text
        }
        logger.info("[1/4] translate start. text=" + textPreview + ", lang=" + targetLang)

        // 2. URL エンコード
        var encodedText = ""
        try {
            encodedText = URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            logger.error("URL encode failed: " + e.message, e)
            throw RuntimeException("URL encode error", e)
        }
        
        // 3. URL 構築（1 行 1 連結）
        var url = ""
        url = url + "https://translate.googleapis.com/translate_a/single"
        url = url + "?client=gtx"
        url = url + "&sl=auto"
        url = url + "&tl=" + targetLang
        url = url + "&dt=t"
        url = url + "&q=" + encodedText
        
        logger.debug("[2/4] Request URL: " + url)

        // 4. HTTP リクエスト
        var response: Http.Response? = null
        try {
            var request = Http.Request(url, "GET")
            request.setHeader("User-Agent", "Mozilla/5.0")
            response = request.execute()
        } catch (e: Exception) {
            logger.error("HTTP execute failed: " + e.message, e)
            throw RuntimeException("HTTP request failed: " + e.message, e)        }

        // 5. null チェック
        if (response == null) {
            throw RuntimeException("Response is null")
        }
        
        logger.debug("[3/4] HTTP status: " + response.statusCode + ", ok=" + response.ok())

        // 6. エラーレスポンス処理
        if (!response.ok()) {
            var bodyPreview = "(read failed)"
            try {
                var full = response.text()
                if (full.length > 200) {
                    bodyPreview = full.substring(0, 200) + "..."
                } else {
                    bodyPreview = full
                }
            } catch (e: Exception) {
                // ignore read error
            }
            var msg = "HTTP " + response.statusCode + ": " + bodyPreview
            logger.error(msg, null)
            throw RuntimeException(msg)
        }

        // 7. レスポンス本文取得
        var body = ""
        try {
            body = response.text()
        } catch (e: Exception) {
            logger.error("Failed to read response body: " + e.message, e)
            throw RuntimeException("Response read error", e)
        }
        
        var bodyPreview = ""
        if (body.length > 300) {
            bodyPreview = body.substring(0, 300) + "..."
        } else {
            bodyPreview = body
        }
        logger.debug("[4/4] Response body: " + bodyPreview)

        // 8. JSON 解析
        return parseResponseSafe(body)
    }

    private fun parseResponseSafe(body: String): String {
        logger.debug("[PARSE] start. body length: " + body.length)        
        var json: JSONArray? = null
        try {
            json = JSONArray(body)
            logger.debug("[PARSE] root array length: " + json.length())
        } catch (e: Exception) {
            logger.error("[PARSE] JSONArray creation failed: " + e.message, e)
            throw e
        }
        
        if (json == null) {
            logger.warn("[PARSE] json is null", null)
            return ""
        }
        
        if (json.length() == 0) {
            logger.warn("[PARSE] empty root array", null)
            return ""
        }
        
        var sections: JSONArray? = null
        try {
            sections = json.getJSONArray(0)
            logger.debug("[PARSE] sections length: " + sections.length())
        } catch (e: Exception) {
            logger.error("[PARSE] no array at index 0: " + e.message, e)
            return ""
        }
        
        if (sections == null) {
            return ""
        }
        
        var result = StringBuilder()
        var idx = 0
        var len = sections.length()
        
        logger.debug("[PARSE] looping " + len + " segments")
        while (idx < len) {
            try {
                var segment = sections.optJSONArray(idx)
                if (segment != null) {
                    if (segment.length() > 0) {
                        var part = segment.optString(0)
                        if (part != null) {
                            if (part.isNotEmpty()) {
                                result.append(part)
                                var partPreview = ""
                                if (part.length > 30) {
                                    partPreview = part.substring(0, 30) + "..."                                } else {
                                    partPreview = part
                                }
                                logger.debug("[PARSE] segment " + idx + ": " + partPreview)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.warn("[PARSE] failed at idx " + idx + ": " + e.message, e)
            }
            idx = idx + 1
        }
        
        var finalResult = result.toString()
        var resultPreview = ""
        if (finalResult.length > 100) {
            resultPreview = finalResult.substring(0, 100) + "..."
        } else {
            resultPreview = finalResult
        }
        logger.info("[PARSE] success: " + resultPreview)
        return finalResult
    }
}
