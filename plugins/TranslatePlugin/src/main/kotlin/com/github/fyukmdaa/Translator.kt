package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.utils.GsonUtils // AliucordのGsonユーティリティをインポート
import com.google.gson.JsonArray // GsonのJsonArrayを使用
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

        // 4. HTTP リクエスト & 5. レスポンス処理
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
            throw e // 呼び出し元に投げる
        }

        // ログ（長すぎる場合は切り詰め）
        val bodyPreview = if (body.length > 300) "${body.substring(0, 300)}..." else body
        logger.debug("[4/4] Response body: $bodyPreview")

        // 8. JSON 解析 (Gsonを使用)
        return parseResponseWithGson(body)
    }

    private fun parseResponseWithGson(body: String): String {
        return try {
            // Gsonでルートの配列をパース
            val root = GsonUtils.gson.fromJson(body, JsonArray::class.java)
            
            // Google Translate APIの構造: [[[ "翻訳済みテキスト", "元テキスト", ... ], ...], ...]
            // root[0] が翻訳セグメントの配列
            if (root.size() == 0) return ""
            
            val segments = root.get(0).asJsonArray
            val result = StringBuilder()
            
            for (i in 0 until segments.size()) {
                try {
                    // 各セグメントは [ "翻訳", "原文", ... ] という配列
                    val segment = segments.get(i).asJsonArray
                    if (segment.size() > 0) {
                        val translatedPart = segment.get(0).asString
                        if (translatedPart.isNotEmpty()) {
                            result.append(translatedPart)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to parse segment $i: ${e.message}")
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
