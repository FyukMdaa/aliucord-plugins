package com.github.fyukmdaa

import com.aliucord.Http
import com.aliucord.Logger
import org.json.JSONArray
import java.net.URLEncoder

object Translator {
    private val logger = Logger("TranslatePlugin")

    fun translate(text: String, targetLang: String = "ja"): String {
        logger.info("【1/4】翻訳開始: text='${text.take(50)}...', lang=$targetLang")

        // ── URL エンコード ───────────────────────────────────────
        val encodedText = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            logger.error("【ERR】URL encode failed: ${e.message}", e)
            throw RuntimeException("URL encode error", e)
        }
        
        val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encodedText"
        
        logger.debug("【2/4】Request URL: $url")

        // ── HTTP リクエスト（タイムアウト明示） ─────────────────────
        val response = try {
            Http.Request(url, "GET").apply {
                setHeader("User-Agent", "Mozilla/5.0")
                // 🔧 Aliucord Http.Request にタイムアウト設定があれば追加
                // setTimeout(10_000)  // 10秒タイムアウト（API に応じて調整）
            }.execute()
        } catch (e: Exception) {
            logger.error("【ERR】HTTP execute failed: ${e.message}", e)
            throw RuntimeException("HTTP request failed: ${e.message}", e)
        }

        logger.debug("【3/4】HTTP status: ${response.statusCode}, ok=${response.ok()}")

        if (!response.ok()) {
            val bodyPreview = try { response.text().take(200) } catch (_: Exception) { "(read failed)" }
            val msg = "HTTP ${response.statusCode}: $bodyPreview"
            logger.error("【ERR】$msg", null)
            throw RuntimeException(msg)
        }

        // ── レスポンス本文の取得 ─────────────────────────────────
        val body = try {            response.text()
        } catch (e: Exception) {
            logger.error("【ERR】Failed to read response body: ${e.message}", e)
            throw RuntimeException("Response read error", e)
        }
        
        logger.debug("【4/4】Response body (first 300 chars): ${body.take(300)}...")

        // ── JSON 解析（安全なインデックスアクセス） ───────────────
        return try {
            parseResponseSafe(body)
        } catch (e: Exception) {
            logger.error("【ERR】JSON parse failed: ${e.message}", e)
            throw e
        }
    }

    /**
     * 🔧 難読化環境対応: Kotlin 拡張関数・イテレータ構文を一切使わない
     * 純粋なインデックスアクセス + while ループのみ
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun parseResponseSafe(body: String): String {
        logger.debug("【PARSE】Starting parse, body length: ${body.length}")
        
        val json: JSONArray
        try {
            json = JSONArray(body)
            logger.debug("【PARSE】Root array length: ${json.length()}")
        } catch (e: Exception) {
            logger.error("【PARSE ERR】Failed to create JSONArray: ${e.message}", e)
            throw e
        }
        
        if (json.length() == 0) {
            logger.warn("【PARSE】Empty root array")
            return ""
        }
        
        val sections: JSONArray
        try {
            sections = json.getJSONArray(0)
            logger.debug("【PARSE】Sections array length: ${sections.length()}")
        } catch (e: Exception) {
            logger.error("【PARSE ERR】No translations array at index 0: ${e.message}", e)
            return ""
        }
        
        val result = StringBuilder()
        var idx = 0        val len = sections.length()
        
        logger.debug("【PARSE】Looping $len segments...")
        while (idx < len) {
            try {
                val segment = sections.optJSONArray(idx)
                if (segment != null && segment.length() > 0) {
                    val part = segment.optString(0)
                    if (part != null && part.isNotEmpty()) {
                        result.append(part)
                        logger.debug("【PARSE】Segment $idx: '${part.take(30)}...'")
                    }
                }
            } catch (e: Exception) {
                logger.warn("【PARSE】Failed segment $idx: ${e.message}", e)
            }
            idx = idx + 1  // 🔧 手動インクリメント
        }
        
        val finalResult = result.toString()
        logger.info("【PARSE OK】Final translation: '${finalResult.take(100)}...'")
        return finalResult
    }
}
