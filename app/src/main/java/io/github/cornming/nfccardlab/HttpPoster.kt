package io.github.cornming.nfccardlab

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 最小的 HTTP POST 客戶端，只用 JDK／Android 內建的 HttpURLConnection。
 * 不自動跟隨轉址（轉址的 POST 可能被改成 GET，也可能把資料送到意料之外的地方）。
 * 不依賴 Android 類別，可以直接在 JVM 上做單元測試。
 */
object HttpPoster {

    data class Response(val code: Int, val bodySnippet: String)

    fun post(
        url: String,
        body: String,
        contentType: String,
        headers: List<Pair<String, String>>,
        timeoutMs: Int = 8_000,
    ): Response {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.setRequestProperty("Content-Type", contentType)
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            return Response(code, stream?.use { readLimited(it, MAX_SNIPPET) } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(stream: InputStream, max: Int): String {
        val buf = ByteArray(max)
        var total = 0
        while (total < max) {
            val n = stream.read(buf, total, max - total)
            if (n < 0) break
            total += n
        }
        return String(buf, 0, total, Charsets.UTF_8).replace(Regex("\\s+"), " ").trim()
    }

    private const val MAX_SNIPPET = 512
}
