package com.fyukmdaa.translateplugin

import com.aliucord.Http
import com.aliucord.utils.LogUtils
import org.json.JSONArray

object Translator {
    fun translate(text: String, targetLang: String = "ja"): String {
        LogUtils.log("TranslatePlugin", "翻訳開始: text=$text, lang=$targetLang")
        
        val url = Http.QueryBuilder("https://translate.googleapis.com/translate_a/single")
            .append("client", "gtx")
            .append("sl", "auto")
            .append("tl", targetLang)
            .append("dt", "t")
            .append("q", text)
            .toString()

        LogUtils.log("TranslatePlugin", "URL: $url")

        val response = try {
            Http.Request(url, "GET").apply {
                setHeader("Content-Type", "application/json")
                setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4592.0 Safari/537.36")
            }.execute()
        } catch (e: Exception) {
            LogUtils.log("TranslatePlugin", "リクエスト例外: ${e.message}")
            throw e
        }

        LogUtils.log("TranslatePlugin", "HTTPステータス: ${response.statusCode}")

        if (!response.ok()) throw Exception("HTTP ${response.statusCode}")

        val body = response.text()
        LogUtils.log("TranslatePlugin", "レスポンス: $body")

        val json = JSONArray(body)
        val sections = json.getJSONArray(0)
        return buildString {
            for (i in 0 until sections.length()) {
                append(sections.getJSONArray(i).getString(0))
            }
        }
    }
}
