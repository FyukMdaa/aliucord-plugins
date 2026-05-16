package com.github.fyukmdaa

import com.aliucord.Http
import java.net.URLEncoder
import java.util.regex.Pattern
import java.util.regex.Matcher

object Translator {

    fun translate(text: String, targetLang: String): String {
        // 1. URL エンコード
        val encodedText = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            throw RuntimeException("URL encode error", e)
        }
        
        // 2. URL 構築
        val url = StringBuilder().apply {
            append("https://translate.googleapis.com/translate_a/single")
            append("?client=gtx")
            append("&sl=auto")
            append("&tl=$targetLang")
            append("&dt=t")
            append("&q=$encodedText")
        }.toString()

        // 3. HTTP リクエスト & レスポンス処理
        val body = try {
            val request = Http.Request(url, "GET")
                .setHeader("User-Agent", "Mozilla/5.0")
            val response = request.execute()
            
            if (response == null) throw RuntimeException("Response is null")
            if (!response.ok()) {
                val errorBody = try { response.text() } catch (e: Exception) { "(read failed)" }
                throw RuntimeException("HTTP ${response.statusCode}: $errorBody")
            }
            response.text()
        } catch (e: Exception) {
            throw e
        }

        // 4. JSON 解析 (ロガーなし)
        return parseResponseWithJavaRegex(body)
    }

    private fun parseResponseWithJavaRegex(body: String): String {
        try {
            // パターン: ["翻訳テキスト","原文", ...] の構造を抽出
            // ただし、Google Translateのレスポンス末尾にはメタデータ [[["ハッシュ","ファイル名"]]] があるため、
            // これを避けるために、キャプチャした文字列がある程度の長さ（3文字以上）であることを確認します。
            val pattern = Pattern.compile("""\["((?:[^"\\]|\\.)*)",""")

            val matcher = pattern.matcher(body)
            val result = StringBuilder()
            var foundTranslation = false

            while (matcher.find()) {
                val part = matcher.group(1) ?: continue
                
                // メタデータ（ハッシュなど）は短い場合が多いので、極端に短いものは無視
                // これによりレスポンス末尾のゴミを除外します
                if (part.length >= 3) {
                    result.append(unescapeJsonString(part))
                    foundTranslation = true
                }
            }
            
            if (!foundTranslation) {
                throw RuntimeException("No translation found")
            }
            
            return result.toString()
        } catch (e: Exception) {
            throw RuntimeException("Parsing error", e)
        }
    }

    private fun unescapeJsonString(str: String): String {
        return str
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }
}
