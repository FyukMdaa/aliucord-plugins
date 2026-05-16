package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import java.net.URLEncoder

object Translator {
    private val logger = Logger("TranslatePlugin")

    fun translate(text: String, targetLang: String): String {
        // 1. URL エンコード
        val encodedText = URLEncoder.encode(text, "UTF-8")
        
        // 2. URL 構築
        val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx" +
                "&sl=auto" +
                "&tl=" + targetLang +
                "&dt=t" +
                "&q=" + encodedText
        
        // 3. HTTP リクエスト
        val request = Http.Request(url, "GET")
        request.setHeader("User-Agent", "Mozilla/5.0")
        val response = request.execute()
        
        // 4. エラーチェック
        if (!response.ok()) {
            val msg = "HTTP " + response.statusCode
            logger.error(msg, null)
            throw RuntimeException(msg)
        }
        
        // 5. レスポンス取得
        val body = response.text()
        
        // 6. 簡易パース
        return parseSimple(body)
    }
    
    /**
     * 🔧 超簡易パース: JSONArray/Regex 不使用
     * 形式: [[["翻訳文","原文",...],...],...]
     * 最初の ["翻訳文","原文" ペアを抽出
     */
    private fun parseSimple(body: String): String {
        // [[[" 以降を探す
        val marker = "[[\""
        val startIdx = body.indexOf(marker)
        if (startIdx < 0) {
            return ""
        }
        
        // marker 以降の文字列
        val after = body.substring(startIdx + marker.length)
        
        // 最初の "," までが翻訳文
        val endIdx = after.indexOf("\",\"")
        if (endIdx < 0) {
            return ""
        }
        
        val raw = after.substring(0, endIdx)
        
        // 基本的なエスケープ解除
        return unescapeJson(raw)
    }
    
    /**
     * 🔧 最小限の JSON エスケープ解除
     */
    private fun unescapeJson(input: String): String {
        return input
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
    }
}
