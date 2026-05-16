package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import org.json.JSONArray // Android標準のJSONを使用
import java.net.URLEncoder

object Translator {
    private val logger = Logger("TranslatePlugin")

    fun translate(text: String, targetLang: String): String {
        // 1. 入力ログ
        val textPreview = if (text.length > 50) "${text.substring(0, 50)}..." else text
        logger.info("[1/4] translate start. text=$textPreview, lang=$targetLang")

        // 2. URL エンコード
        val encodedText = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            logger.error("URL encode failed: ${e.message}", e)
            throw RuntimeException("URL encode error", e)
        }
        
        // 3. URL 構築
        val url = StringBuilder().apply {
            append("https://translate.googleapis.com/translate_a/single")
            append("?client=gtx")
            append("&sl=auto")
            append("&tl=$targetLang")
            append("&dt=t")
            append("&q=$encodedText")
        }.toString()
        
        logger.debug("[2/4] Request URL: $url")

        // 4. HTTP リクエスト & レスポンス処理
        val body = try {
            val request = Http.Request(url, "GET")
                .setHeader("User-Agent", "Mozilla/5.0")
            val response = request.execute()
            
            if (response == null) throw RuntimeException("Response is null")
            
            logger.debug("[3/4] HTTP status: ${response.statusCode}, ok=${response.ok()}")

            if (!response.ok()) {
                val errorBody = try { 
                    val txt = response.text()
                    if (txt.length > 200) "${txt.substring(0, 200)}..." else txt
                } catch (e: Exception) { "(read failed)" }
                
                val msg = "HTTP ${response.statusCode}: $errorBody"
                logger.error(msg, null)
                throw RuntimeException(msg)
            }
            
            response.text()
        } catch (e: Exception) {
            logger.error("HTTP request failed: ${e.message}", e)
            throw e
        }

        // ログ
        val bodyPreview = if (body.length > 300) "${body.substring(0, 300)}..." else body
        logger.debug("[4/4] Response body: $bodyPreview")

        // 5. JSON 解析（安全な実装）
        return parseResponseSafe(body)
    }

    private fun parseResponseSafe(body: String): String {
        return try {
            // org.json.JSONArray を使用
            val root = JSONArray(body)
            
            // 構造: [[["翻訳", "原文", ...], ...], ...]
            // root[0] が翻訳結果の配列
            if (root.length() == 0) {
                logger.warn("[PARSE] Root array is empty")
                return ""
            }
            
            val sections = root.getJSONArray(0)
            val result = StringBuilder()
            
            // 【重要】for (item in sections) だとエラーになるため、
            // インデックスを使ったループ (0 until length) を使用します。
            val len = sections.length()
            for (i in 0 until len) {
                try {
                    val segment = sections.getJSONArray(i)
                    // セグメントの0番目が翻訳テキスト
                    if (segment.length() > 0) {
                        val part = segment.optString(0)
                        if (part.isNotEmpty()) {
                            result.append(part)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("[PARSE] Failed to parse segment $i: ${e.message}")
                }
            }
            
            val finalResult = result.toString()
            val resultPreview = if (finalResult.length > 100) "${finalResult.substring(0, 100)}..." else finalResult
            logger.info("[PARSE] success: $resultPreview")
            
            finalResult
        } catch (e: Exception) {
            logger.error("[PARSE] JSON parsing failed: ${e.message}", e)
            throw RuntimeException("JSON parsing error", e)
        }
    }
}
