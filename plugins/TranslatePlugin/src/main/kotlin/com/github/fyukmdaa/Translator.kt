package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import org.json.JSONArray

object Translator {
    private val logger = Logger("TranslatePlugin")

    fun translate(text: String, targetLang: String = "ja"): String {
        logger.info("翻訳開始: text=$text, lang=$targetLang")

        val url = Http.QueryBuilder("https://translate.googleapis.com/translate_a/single")
            .append("client", "gtx")
            .append("sl", "auto")
            .append("tl", targetLang)
            .append("dt", "t")
            .append("q", text)
            .toString()

        logger.info("URL: $url")

        val response = try {
            Http.Request(url, "GET").apply {
                setHeader("Content-Type", "application/json")
                setHeader("User-Agent", "Mozilla/5.0")
            }.execute()
        } catch (e: Exception) {
            logger.error("リクエスト例外", e)
            throw e
        }

        logger.info("HTTPステータス: ${response.statusCode}")

        if (!response.ok()) throw Exception("HTTP ${response.statusCode}")

        val body = response.text()
        logger.info("レスポンス: $body")

        val json = JSONArray(body)
        val sections = json.getJSONArray(0)
        return buildString {
            for (i in 0 until sections.length()) {
                append(sections.getJSONArray(i).getString(0))
            }
        }
    }
}
