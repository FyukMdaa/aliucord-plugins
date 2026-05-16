package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import java.net.URLEncoder
import java.util.regex.Pattern

object Translator {
    private val logger = Logger("TranslatePlugin")

    fun translate(text: String, targetLang: String): String {
        // 1. 入力ログ
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
        
        // 3. URL 構築
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
                // ignore
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

        // 8. 正規表現で翻訳テキストを抽出（JSONArray 不使用）
        return extractTranslationWithRegex(body)
    }

    /**
     * 🔧 Google Translate API レスポンスを正規表現で解析     * JSONArray 等の難読化クラスを一切使用しない安全な実装
     * 
     * レスポンス例:
     * [[["こんにちは","Hello",...],["世界","world",...]],..."en",...]
     */
    private fun extractTranslationWithRegex(body: String): String {
        logger.debug("[REGEX] Parsing response with regex, length: " + body.length)
        
        if (body.isEmpty() || body.charAt(0) != '[') {
            logger.warn("[REGEX] Invalid response format", null)
            return ""
        }
        
        // 🔧 正規表現パターン: "翻訳結果","元テキスト" のペアを抽出
        // グループ1: 翻訳文, グループ2: 原文
        val pattern = Pattern.compile("\"([^\"]*(?:\\\\.[^\"]*)*)\",\"([^\"]*(?:\\\\.[^\"]*)*)\"")
        val matcher = pattern.matcher(body)
        
        var result = StringBuilder()
        var matchCount = 0
        val maxMatches = 20  // 無限ループ防止
        
        logger.debug("[REGEX] Starting regex match loop")
        
        // 🔧 伝統的な while-loop + 手動カウント
        while (matcher.find() && matchCount < maxMatches) {
            try {
                var translated = ""
                var original = ""
                
                // 🔧 group() 呼び出しは個別に
                try {
                    translated = matcher.group(1)
                } catch (e: Exception) {
                    logger.warn("[REGEX] Failed to get group 1", e)
                }
                try {
                    original = matcher.group(2)
                } catch (e: Exception) {
                    logger.warn("[REGEX] Failed to get group 2", e)
                }
                
                // 🔧 原文がリクエストテキストと一致するペアを翻訳文として採用
                // （Google API は [translated, original, ...] 形式で返す）
                if (translated != null && translated.isNotEmpty()) {
                    if (original != null && original.isNotEmpty()) {
                        // 原文がリクエストの一部と一致すれば、これは翻訳ペア
                        if (original.length >= 3 && text.contains(original)) {
                            result.append(translated)
                            matchCount = matchCount + 1                            logger.debug("[REGEX] Match " + matchCount + ": " + translated.substring(0, kotlin.math.min(30, translated.length)))
                        }
                    } else {
                        // 原文がない場合は単純に追加（フォールバック）
                        result.append(translated)
                        matchCount = matchCount + 1
                    }
                }
            } catch (e: Exception) {
                logger.warn("[REGEX] Failed to process match", e)
                // 1 つのマッチ失敗で全体を失敗させない
            }
        }
        
        var finalResult = result.toString()
        
        // 🔧 Unicode エスケープ (\uXXXX) をデコード
        finalResult = decodeUnicodeEscapes(finalResult)
        
        var resultPreview = ""
        if (finalResult.length > 100) {
            resultPreview = finalResult.substring(0, 100) + "..."
        } else {
            resultPreview = finalResult
        }
        
        if (finalResult.isNotEmpty()) {
            logger.info("[REGEX] Success: " + resultPreview)
        } else {
            logger.warn("[REGEX] No translation found", null)
        }
        
        return finalResult
    }
    
    /**
     * 🔧 Unicode エスケープ (\uXXXX) を文字列にデコード
     */
    private fun decodeUnicodeEscapes(input: String): String {
        if (input.indexOf("\\u") < 0) {
            return input
        }
        
        var result = StringBuilder()
        var i = 0
        var len = input.length
        
        while (i < len) {
            if (i + 5 < len && input.charAt(i) == '\\' && input.charAt(i + 1) == 'u') {
                try {                    var hex = input.substring(i + 2, i + 6)
                    var codePoint = Integer.parseInt(hex, 16)
                    result.append(codePoint.toChar())
                    i = i + 6
                } catch (e: Exception) {
                    // 解析失敗時はそのまま追加
                    result.append(input.charAt(i))
                    i = i + 1
                }
            } else {
                result.append(input.charAt(i))
                i = i + 1
            }
        }
        
        return result.toString()
    }
}
