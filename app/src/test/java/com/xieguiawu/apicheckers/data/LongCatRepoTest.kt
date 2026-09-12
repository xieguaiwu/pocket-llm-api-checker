package com.xieguiawu.apicheckers.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LongCat 仓库 + 解析测试：逐条移植自 Go 姊妹项目 internal/repo/longcat.go、
 * internal/parsers（fc30bb8）。HTTP 服务用 MiniServer 同款单线程服务器（零新增依赖）。
 * 测试凭据一律假值。平台语义：探活 402 = 余额不足（非错误），401 = 无效 key。
 */
class LongCatRepoTest {

    /** 迷你 HTTP 服务器（同 BaiRepoTest 的实现） */
    private class MiniServer(private val handler: (Request) -> Response) {
        data class Request(val method: String, val path: String, val query: String, val headers: Map<String, String>, val body: String)
        data class Response(val code: Int, val body: String)

        private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            Thread {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (e: Exception) { break }
                    try { handle(socket) } catch (_: Exception) {} finally { runCatching { socket.close() } }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun handle(socket: java.net.Socket) {
            socket.use { s ->
                val input = s.getInputStream()
                val output = s.getOutputStream()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                val target = parts[1]
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val idx = line.indexOf(":")
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                val body = String(readBytes(input, len), Charsets.UTF_8)
                val resp = handler(Request(parts[0], target.substringBefore("?"), target.substringAfter("?", ""), headers, body))
                writeResponse(output, resp.code, resp.body)
            }
        }

        private fun readLine(input: java.io.InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        private fun readBytes(input: java.io.InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r == -1) break
                off += r
            }
            return buf
        }

        private fun writeResponse(output: java.io.OutputStream, code: Int, body: String) {
            val payload = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 $code OK\r\n")
                append("Content-Length: ${payload.size}\r\n")
                append("Content-Type: application/json\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(payload)
            output.flush()
        }

        fun stop() = runCatching { server.close() }
    }

    private var server: MiniServer? = null

    fun tearDown() { server?.stop(); server = null }

    // ── fixtures（OpenAI 兼容信封，与 Go 测试共享口径） ──

    private val longcatModelsBody =
        "{\"object\":\"list\",\"data\":[{\"id\":\"LongCat-2.0\",\"object\":\"model\",\"owned_by\":\"meituan\"}," +
            "{\"id\":\"LongCat-Flash\",\"object\":\"model\",\"owned_by\":\"meituan\"}]}"

    // ── 账号模型 ──

    @Test
    fun longCatAccount_masksKeyInToString() {
        val acc = LongCatAccount(id = "1", name = "龙猫", apiKey = "lc-secret-value")
        assertTrue(acc.keyConfigured)
        assertFalse(acc.toString().contains("lc-secret-value"))
        assertTrue(acc.toString().contains("apiKey=****"))
        val empty = LongCatAccount(id = "2", name = "空", apiKey = "  ")
        assertFalse(empty.keyConfigured)
    }

    // ── 解析器 ──

    @Test
    fun parseLongCatModels_dedupesAndSorts() {
        val raw = "{\"data\":[" +
            "{\"id\":\"LongCat-2.0\",\"owned_by\":\"meituan\"}," +
            "{\"id\":\"LongCat-Flash\",\"owned_by\":\"meituan\"}," +
            "{\"id\":\"LongCat-2.0\",\"owned_by\":\"meituan\"}," +
            "{\"id\":\" \",\"owned_by\":\"x\"}" +
            "]}"
        val models = parseLongCatModels(raw).getOrThrow()
        assertEquals(2, models.size)
        assertEquals(listOf("LongCat-2.0", "LongCat-Flash"), models.map { it.id })
        assertEquals("meituan", models[0].ownedBy)
    }

    @Test
    fun parseLongCatModels_emptyFails() {
        val e1 = parseLongCatModels("""{"data":[]}""").exceptionOrNull()!!
        assertTrue(e1.message!!.contains("未获取到 LongCat 可用模型"))
        // 缺 data 键 = 显式失败（不把「接口没回」当「零模型」）
        val e2 = parseLongCatModels("""{"object":"list"}""").exceptionOrNull()!!
        assertTrue(e2.message!!.contains("未获取到 LongCat 可用模型"))
    }

    @Test
    fun parseLongCatModels_badJsonFails() {
        val e = parseLongCatModels("not-json").exceptionOrNull()!!
        assertTrue(e.message!!.contains("LongCat 模型清单 JSON 解析失败"))
    }

    // ── repo：模型清单 ──

    @Test
    fun repo_modelsWireFormat() = runBlocking {
        var auth: String? = null
        var path = ""
        server = MiniServer { req ->
            auth = req.headers["authorization"]
            path = req.path
            MiniServer.Response(200, longcatModelsBody)
        }
        val repo = LongCatRepo(baseURL = "http://127.0.0.1:${server!!.port}")
        val plan = repo.models("lc-test").getOrThrow()
        assertEquals("Bearer lc-test", auth)
        assertEquals("/v1/models", path)
        assertEquals(2, plan.models.size)
        tearDown()
    }

    @Test
    fun repo_modelsAuthError401And403() = runBlocking {
        server = MiniServer { _ -> MiniServer.Response(401, """{"error":{"message":"invalid api key"}}""") }
        val repo = LongCatRepo(baseURL = "http://127.0.0.1:${server!!.port}")
        val e = repo.models("lc-test").exceptionOrNull()!!
        assertEquals(LongCatAuthError, e.message)
        tearDown()

        server = MiniServer { _ -> MiniServer.Response(403, """{"error":{"message":"forbidden"}}""") }
        val repo2 = LongCatRepo(baseURL = "http://127.0.0.1:${server!!.port}")
        val e2 = repo2.models("lc-test").exceptionOrNull()!!
        assertEquals(LongCatAuthError, e2.message)
        tearDown()
    }

    @Test
    fun repo_modelsEmptyKeyFails() = runBlocking {
        val repo = LongCatRepo(baseURL = "http://127.0.0.1:1")
        val e = repo.models("  ").exceptionOrNull()!!
        assertTrue(e.message!!.contains("未配置 API Key"))
    }

    // ── repo：余额探活（200=true / 402=false 非错误 / 401=认证错误） ──

    @Test
    fun repo_probeBalance_200MeansSufficient() = runBlocking {
        var body = ""
        var path = ""
        server = MiniServer { req ->
            path = req.path
            body = req.body
            MiniServer.Response(200, """{"choices":[{"message":{"content":"pong"}}]}""")
        }
        val repo = LongCatRepo(baseURL = "http://127.0.0.1:${server!!.port}")
        val ok = repo.probeBalance("lc-test").getOrThrow()
        assertTrue(ok)
        assertEquals("/v1/chat/completions", path)
        // max_tokens=1 压最低消耗；stream 固定 false
        assertTrue(body.contains("\"model\":\"LongCat-2.0\""))
        assertTrue(body.contains("\"max_tokens\":1"))
        assertTrue(body.contains("\"stream\":false"))
        tearDown()
    }

    @Test
    fun repo_probeBalance_402IsInsufficientNotError() = runBlocking {
        server = MiniServer { _ ->
            MiniServer.Response(402, """{"error":{"code":"insufficient_quota","message":"quota exceeded"}}""")
        }
        val repo = LongCatRepo(baseURL = "http://127.0.0.1:${server!!.port}")
        val ok = repo.probeBalance("lc-test").getOrThrow()
        assertFalse(ok) // key 有效只是没余额：false 而非 failure
        tearDown()
    }

    @Test
    fun repo_probeBalance_401IsAuthError() = runBlocking {
        server = MiniServer { _ -> MiniServer.Response(401, """{"error":{"message":"invalid api key"}}""") }
        val repo = LongCatRepo(baseURL = "http://127.0.0.1:${server!!.port}")
        val e = repo.probeBalance("lc-test").exceptionOrNull()!!
        assertEquals(LongCatAuthError, e.message)
        tearDown()
    }

    @Test
    fun repo_probeBalance_otherStatusIsFailure() = runBlocking {
        server = MiniServer { _ -> MiniServer.Response(503, """{"error":{"message":"overloaded"}}""") }
        val repo = LongCatRepo(baseURL = "http://127.0.0.1:${server!!.port}")
        val r = repo.probeBalance("lc-test")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("HTTP 503"))
        tearDown()
    }

    @Test
    fun repo_probeEmptyKeyFails() = runBlocking {
        val repo = LongCatRepo(baseURL = "http://127.0.0.1:1")
        val e = repo.probeBalance("").exceptionOrNull()!!
        assertTrue(e.message!!.contains("未配置 API Key"))
    }

    // ── 探活载荷常量（契约快照） ──

    @Test
    fun probePayload_contract() {
        assertEquals(
            """{"model":"LongCat-2.0","messages":[{"role":"user","content":"ping"}],"max_tokens":1,"stream":false}""",
            LongCatRepo.ProbePayload,
        )
        assertEquals("https://api.longcat.chat/openai", LongCatRepo.LongCatBaseURL)
    }

    // ── LongCatUsage 语义 ──

    @Test
    fun longCatUsage_nullMeansNotProbed() {
        assertNull(LongCatUsage().balanceOK)
        assertEquals(true, LongCatUsage(balanceOK = true).balanceOK)
        assertEquals(false, LongCatUsage(balanceOK = false).balanceOK)
    }
}
