package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import java.net.URLEncoder

object Translator {
    private val logger = Logger("TranslatePlugin")

    fun translate(text: String, targetLang: String): String {
        val textPreview = if (text.length > 50) "${text.substring(0, 50)}..." else text
        logger.info("[1/4] translate start. text=$textPreview, lang=$targetLang")

        val encodedText = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            logger.error("URL encode failed: ${e.message}", e)
            throw RuntimeException("URL encode error", e)
        }
        
        val url = StringBuilder().apply {
            append("https://translate.googleapis.com/translate_a/single")
            append("?client=gtx")
            append("&sl=auto")
            append("&tl=$targetLang")
            append("&dt=t")
            append("&q=$encodedText")
        }.toString()
        
        logger.debug("[2/4] Request URL: $url")

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

        val bodyPreview = if (body.length > 300) "${body.substring(0, 300)}..." else body
        logger.debug("[4/4] Response body: $bodyPreview")

        // 【重要】JSONパースをやめて正規表現で抽出します
        return parseResponseWithRegex(body)
    }

    private fun parseResponseWithRegex(body: String): String {
        return try {
            // Google Translateの形式: [[["翻訳テキスト", "元テキスト", ...], ...], ...]
            // 「["」で始まり、「","」で終わる箇所を探す（＝各セグメントの先頭、つまり翻訳結果）
            // エスケープされたクオーテーション(\"など)を考慮した正規表現
            val regex = Regex("""\["((?:[^"\\]|\\.)*)",""")
            
            val matches = regex.findAll(body)
            val result = StringBuilder()
            
            var count = 0
            for (match in matches) {
                // group(1) がキャプチャされた翻訳テキスト
                val translatedPart = match.groupValues[1]
                // エスケープシーケンスをデコード (\" -> ", \/ -> /, \\ -> \ など)
                val unescapedPart = unescapeJsonString(translatedPart)
                
                result.append(unescapedPart)
                count++
            }
            
            if (count == 0) {
                logger.warn("[PARSE] No matches found in response.")
                return ""
            }
            
            val finalResult = result.toString()
            val resultPreview = if (finalResult.length > 100) "${finalResult.substring(0, 100)}..." else finalResult
            logger.info("[PARSE] success (Regex): $resultPreview")
            
            finalResult
        } catch (e: Exception) {
            logger.error("[PARSE] Regex parsing failed: ${e.message}", e)
            throw RuntimeException("Parsing error", e)
        }
    }

    // JSON文字列内のエスケープを解除する簡易ヘルパー
    private fun unescapeJsonString(str: String): String {
        return str
            .replace("\\\"", "\"")  // クオーテーション
            .replace("\\/", "/")    // スラッシュ
            .replace("\\\\", "\\")  // バックスラッシュ
            .replace("\\n", "\n")   // 改行
            .replace("\\r", "\r")   // 復帰
            .replace("\\t", "\t")   // タブ
    }
}
