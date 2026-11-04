package io.github.cornming.nfccardlab

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 測試用的迷你 HTTP 伺服器，只聽本機。
 * 不用 JDK 內建的 com.sun.net.httpserver，因為 Android 單元測試編譯時看不到它。
 */
private class TinyHttpServer(private val respond: (Request) -> Reply) : AutoCloseable {

    class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    class Reply(
        val status: Int,
        val body: String = "",
        val headers: Map<String, String> = emptyMap(),
        val delayMs: Long = 0,
    )

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int = socket.localPort
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    init {
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                Thread({ handle(client) }, "tiny-http-conn").apply { isDaemon = true }.start()
            }
        }, "tiny-http-accept").apply { isDaemon = true }.start()
    }

    private fun handle(client: Socket) {
        try {
            val input = client.getInputStream()
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val first = lines[0].split(" ")
            val headers = lines.drop(1)
                .filter { it.contains(":") }
                .associate { it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim() }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bodyBytes, read, length - read)
                if (n < 0) break
                read += n
            }
            val request = Request(first[0], first[1], headers, String(bodyBytes, 0, read, Charsets.UTF_8))
            requests.add(request)

            val reply = respond(request)
            if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            val out = StringBuilder("HTTP/1.1 ${reply.status} X\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            reply.headers.forEach { (k, v) -> out.append("$k: $v\r\n") }
            out.append("\r\n")
            val stream = client.getOutputStream()
            stream.write(out.toString().toByteArray(Charsets.UTF_8))
            stream.write(bytes)
            stream.flush()
        } catch (_: IOException) {
            // 客戶端先斷線（例如逾時）時會到這裡，測試不需要處理
        } finally {
            try {
                client.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.length >= 4 && sb.endsWith("\r\n\r\n")) return sb.toString().trimEnd()
        }
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }
}

/** 對本機真實的 HTTP 伺服器送出 POST，驗證請求的方法、標頭、內容，以及各種回應的處理。 */
class HttpPostTest {

    private lateinit var server: TinyHttpServer

    @Before
    fun start() {
        // 先建立 server 再回頭用它的 port 組出轉址位置
        lateinit var self: TinyHttpServer
        server = TinyHttpServer { req ->
            when (req.path.substringBefore('?')) {
                "/ok" -> TinyHttpServer.Reply(200, """{"result":"ok"}""")
                "/fail" -> TinyHttpServer.Reply(500, "internal boom")
                "/redirect" -> TinyHttpServer.Reply(
                    302,
                    headers = mapOf("Location" to "http://127.0.0.1:${self.port}/ok"),
                )
                "/slow" -> TinyHttpServer.Reply(200, delayMs = 2_000)
                else -> TinyHttpServer.Reply(404, "no such path")
            }
        }
        self = server
    }

    @After
    fun stop() {
        server.close()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

    private fun seenOk(): TinyHttpServer.Request? = server.requests.firstOrNull { it.path.startsWith("/ok") }

    private fun runActuator(config: ActionConfig.HttpPost, ctx: ActionContext): Result<String> {
        val latch = CountDownLatch(1)
        val out = AtomicReference<Result<String>>()
        HttpPostActuator(config).execute(ctx) {
            out.set(it)
            latch.countDown()
        }
        assertTrue("動作逾時沒有回呼", latch.await(10, TimeUnit.SECONDS))
        return out.get()
    }

    private val ctx = ActionContext("F1:7A:70:5C", "大門卡", "2026-11-04T10:00:00+08:00")

    @Test
    fun postSendsMethodHeadersAndBody() {
        val r = HttpPoster.post(
            url("/ok"),
            """{"uid":"F1:7A:70:5C"}""",
            "application/json",
            listOf("Authorization" to "Bearer abc"),
        )
        assertEquals(200, r.code)
        assertEquals("""{"result":"ok"}""", r.bodySnippet)
        val s = seenOk()
        assertNotNull(s)
        assertEquals("POST", s!!.method)
        assertEquals("application/json", s.headers["content-type"])
        assertEquals("Bearer abc", s.headers["authorization"])
        assertEquals("""{"uid":"F1:7A:70:5C"}""", s.body)
    }

    @Test
    fun multiByteBodyIsSentAsUtf8WithCorrectLength() {
        HttpPoster.post(url("/ok"), """{"who":"大門卡"}""", "application/json", emptyList())
        val s = seenOk()!!
        assertEquals("""{"who":"大門卡"}""", s.body)
        assertEquals("""{"who":"大門卡"}""".toByteArray(Charsets.UTF_8).size.toString(), s.headers["content-length"])
    }

    @Test
    fun actuatorRendersPlaceholdersAndReportsSuccess() {
        val cfg = ActionConfig.HttpPost(
            url = url("/ok"),
            body = """{"uid":"{uid}","who":"{label}","at":"{time}"}""",
            contentType = "application/json",
            headersText = "Authorization: Bearer abc",
        )
        val result = runActuator(cfg, ctx)
        assertTrue(result.isSuccess)
        assertEquals("POST 成功（HTTP 200）", result.getOrNull())
        assertEquals(
            """{"uid":"F1:7A:70:5C","who":"大門卡","at":"2026-11-04T10:00:00+08:00"}""",
            seenOk()!!.body,
        )
        assertEquals("Bearer abc", seenOk()!!.headers["authorization"])
    }

    @Test
    fun serverErrorIsReportedAsFailureWithoutLeakingUrlOrBody() {
        val cfg = ActionConfig.HttpPost(url("/fail") + "?token=secret", "x", "text/plain", "X-Key: topsecret")
        val result = runActuator(cfg, ctx)
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message!!
        assertEquals("伺服器回應 HTTP 500", msg)
        assertFalse(msg.contains("secret"))
        assertFalse(msg.contains("127.0.0.1"))
    }

    @Test
    fun redirectsAreNotFollowedAndCountAsFailure() {
        val result = runActuator(ActionConfig.HttpPost(url("/redirect"), "x", "text/plain", ""), ctx)
        assertTrue(result.isFailure)
        assertEquals("伺服器回應 HTTP 302", result.exceptionOrNull()!!.message)
        assertNull("轉址目標不應該被連線", seenOk())
    }

    @Test
    fun publicHttpIsRejectedBeforeAnyConnection() {
        val result = runActuator(ActionConfig.HttpPost("http://example.com/x", "x", "text/plain", ""), ctx)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("區域網路"))
    }

    @Test
    fun badHeaderTextIsRejectedBeforeAnyConnection() {
        val result = runActuator(ActionConfig.HttpPost(url("/ok"), "x", "text/plain", "bad header line"), ctx)
        assertTrue(result.isFailure)
        assertNull("格式錯誤時不應該送出任何請求", seenOk())
    }

    @Test
    fun unreachableHostIsReportedAsConnectionFailure() {
        // 找一個沒有人在聽的本機埠
        val free = ServerSocket(0).use { it.localPort }
        val result = runActuator(ActionConfig.HttpPost("http://127.0.0.1:$free/x", "x", "text/plain", ""), ctx)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.startsWith("連線失敗"))
    }

    @Test
    fun timeoutIsReported() {
        val started = System.nanoTime()
        try {
            HttpPoster.post(url("/slow"), "x", "text/plain", emptyList(), timeoutMs = 300)
            throw AssertionError("應該逾時")
        } catch (_: SocketTimeoutException) {
            // 預期
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue("逾時花太久：$ms ms", ms < 1_800)
    }
}
