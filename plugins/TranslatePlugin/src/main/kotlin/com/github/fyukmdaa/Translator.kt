package com.github.fyukmdaa

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

object Translator {

    fun translate(text: String, targetLang: String): String {
        val encodedText = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            throw RuntimeException("URL encode error", e)
        }
        
        val urlString = StringBuilder().apply {
            append("https://translate.googleapis.com/translate_a/single")
            append("?client=gtx")
            append("&sl=auto")
            append("&tl=$targetLang")
            append("&dt=t")
            append("&q=$encodedText")
        }.toString()

        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                throw RuntimeException("HTTP $responseCode")
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line)
            }
            reader.close()

            return parseResponseWithJavaRegex(response.toString())

        } catch (e: Exception) {
            throw RuntimeException("Request failed", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseResponseWithJavaRegex(body: String): String {
        try {
            val pattern = Pattern.compile("""\["((?:[^"\\]|\\.)*)",""")
            val matcher = pattern.matcher(body)
            val result = StringBuilder()
            var foundTranslation = false

            while (matcher.find()) {
                val part = matcher.group(1) ?: continue
                
                // 【修正】Google Translateのメタデータ（32桁の16進数ハッシュ）を除外
                // 例: "466914b2b9b759682681a550c00b67dd" のようなもの
                if (part.matches(Regex("^[a-f0-9]{32}$"))) continue

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
