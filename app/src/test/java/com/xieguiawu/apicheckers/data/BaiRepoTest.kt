package com.xieguiawu.apicheckers.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 白B.AI 仓库 + 解析测试：逐条移植自 Go 姊妹项目 internal/repo/bai_test.go 与
 * internal/parsers/bai_test.go。HTTP 服务用 MiniServer 同款单线程服务器（零新增依赖）。
 * 测试凭据一律假值（sk-test）。契约：Go 仓 docs/plans/2026-09-04-bai-provider.md。
 */
class BaiRepoTest {

    /** 迷你 HTTP 服务器（同 GalaxyRepoTest 的实现，按 host:port 双面分流两域） */
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

    // ── fixtures（真实形状截取，与 Go 测试共享口径） ──

    private val baiModelsBody =
        "{\"data\":[{\"id\":\"qwen3.8-flash\",\"object\":\"model\",\"owned_by\":\"qwen\",\"supported_endpoint_types\":[\"openai\"]}," +
            "{\"id\":\"deepseek-v4-flash\",\"object\":\"model\",\"owned_by\":\"deepseek\",\"supported_endpoint_types\":null}]," +
            "\"object\":\"list\",\"success\":true}"

    private val baiPointsBody =
        """{"result":{"data":{"json":{"points_balance":27166591,"points_expiring":7166591}}}}"""

    private val baiSummaryBody =
        """{"result":{"data":{"json":{"monthly_spent":2833409,"points_balance":27166591}}}}"""

    private val baiUnauthBody =
        """{"error":{"json":{"message":"UNAUTHORIZED","code":-32001,"data":{"code":"UNAUTHORIZED","httpStatus":401,"path":"usage.points"}}}}"""

    // ── 解析器 ──

    @Test
    fun parseBaiModels_dedupesAndSorts() {
        val raw = "{\"data\":[" +
            "{\"id\":\"minimax-m3\",\"owned_by\":\"minimax\",\"supported_endpoint_types\":[\"openai\"]}," +
            "{\"id\":\"deepseek-v4-flash\",\"owned_by\":\"deepseek\",\"supported_endpoint_types\":null}," +
            "{\"id\":\"qwen3.8-flash\",\"owned_by\":\"qwen\"}," +
            "{\"id\":\"deepseek-v4-flash\",\"owned_by\":\"deepseek\",\"supported_endpoint_types\":null}" +
            "],\"object\":\"list\",\"success\":true}"
        val models = parseBaiModels(raw).getOrThrow()
        assertEquals(3, models.size)
        assertEquals(listOf("deepseek-v4-flash", "minimax-m3", "qwen3.8-flash"), models.map { it.id })
    }

    @Test
    fun parseBaiModels_emptyFails() {
        val e = parseBaiModels("""{"data":[],"success":true}""").exceptionOrNull()!!
        assertTrue(e.message!!.contains("未获取到 BAI 模型"))
        val e2 = parseBaiModels("""{"success":false,"message":"boom"}""").exceptionOrNull()!!
        assertTrue(e2.message!!.contains("BAI 网关返回错误: boom"))
    }

    @Test
    fun parseBaiPoints_happyAndTolerantShapes() {
        val pts = parseBaiPoints(baiPointsBody).getOrThrow()
        assertEquals(27166591L, pts.balance)
        assertEquals(7166591L, pts.expiring)
        // 宽容形状：字符串 / 浮点
        val pts2 = parseBaiPoints("""{"result":{"data":{"json":{"points_balance":"12345","points_expiring":2.7e6}}}}""").getOrThrow()
        assertEquals(12345L, pts2.balance)
        assertEquals(2700000L, pts2.expiring)
    }

    @Test
    fun parseBaiPoints_missingBalanceIsExplicitFailure() {
        val e = parseBaiPoints("""{"result":{"data":{"json":{"has_more":false}}}}""").exceptionOrNull()!!
        assertTrue(e.message!!.contains("响应缺少 points_balance"))
    }

    @Test
    fun parseBaiPoints_unauthorizedNormalizes() {
        val e = parseBaiPoints(baiUnauthBody).exceptionOrNull()!!
        assertEquals(BaiAuthError, e.message)
    }

    @Test
    fun parseBaiPoints_nonAuthErrorKeepsRaw() {
        val e = parseBaiPoints("""{"error":{"json":{"message":"boom","data":{"code":"BAD"}}}}""").exceptionOrNull()!!
        assertTrue(e.message!!.contains("BAI 积分额度 返回错误: boom"))
    }

    @Test
    fun parseBaiMonthlySpent_happyAndMissing() {
        assertEquals(2833409L, parseBaiMonthlySpent(baiSummaryBody).getOrThrow())
        val e = parseBaiMonthlySpent("""{"result":{"data":{"json":{}}}}""").exceptionOrNull()!!
        assertTrue(e.message!!.contains("响应缺少 monthly_spent"))
    }

    @Test
    fun parseBaiRecords_dataMissingFails_emptyIsValid() {
        val e = parseBaiRecords("""{"result":{"data":{"json":{"has_more":false}}}}""").exceptionOrNull()!!
        assertTrue(e.message!!.contains("响应缺少 data"))
        val (recs, more) = parseBaiRecords("""{"result":{"data":{"json":{"data":[],"has_more":false}}}}""").getOrThrow()
        assertEquals(0, recs.size)
        assertFalse(more)
    }

    // ── 聚合纯函数（与 Go AggregateBaiUsage 同口径） ──

    @Test
    fun aggregateBaiUsage_groupsAndSorts() {
        val recs = listOf(
            BaiRecord(model = "glm-5.3-flash", createdAt = "2026-09-06T05:28:30.000Z", inputTokens = 100, outputTokens = 10, totalTokens = 110),
            BaiRecord(model = "qwen3.8-flash", createdAt = "2026-09-06T05:09:11.000Z", totalTokens = 220, costPoints = 5),
            BaiRecord(model = "glm-5.3-flash", createdAt = "2026-09-06T05:15:00.000Z", inputTokens = 300, totalTokens = 330),
        )
        val s = aggregateBaiUsage(recs)
        assertEquals(3, s.recordsFetched)
        assertEquals(660L, s.totalTokens)
        assertEquals(5L, s.totalCostPoints)
        assertEquals("2026-09-06T05:09:11.000Z", s.windowStart)
        assertEquals("2026-09-06T05:28:30.000Z", s.windowEnd)
        assertEquals(2, s.perModel.size)
        assertEquals("glm-5.3-flash", s.perModel[0].model)
        assertEquals(2L, s.perModel[0].requests)
        assertEquals(440L, s.perModel[0].totalTokens)
        assertFalse(s.complete) // 纯函数不设 complete，由 repo 翻页层决定
    }

    @Test
    fun aggregateBaiUsage_tieBreaksAlphabetically() {
        val s = aggregateBaiUsage(
            listOf(
                BaiRecord(model = "b-model", totalTokens = 2),
                BaiRecord(model = "a-model", totalTokens = 2),
            ),
        )
        assertEquals(listOf("a-model", "b-model"), s.perModel.map { it.model })
    }

    @Test
    fun baiPlan_missingFreeFlash() {
        val plan = BaiPlan(models = listOf(BaiModel("deepseek-v4-flash"), BaiModel("glm-5.3-flash")))
        assertEquals(listOf("deepseek-v4-flash-vision-exp", "qwen3.8-flash"), plan.missingFreeFlash())
        val full = BaiPlan(models = BaiFreeFlashModels.map { BaiModel(it) })
        assertTrue(full.missingFreeFlash().isEmpty())
    }

    // ── repo（MiniServer 双端口分流推理面/控制台） ──

    @Test
    fun repo_pointsTwoLanesAndSummaryOptional() = runBlocking {
        server = MiniServer { req ->
            when {
                req.path == "/trpc/lambda/usage.points" -> {
                    assertTrue(req.headers["authorization"] == "Bearer sk-test")
                    MiniServer.Response(200, baiPointsBody)
                }
                req.path == "/trpc/lambda/usage.summary" -> MiniServer.Response(500, "boom")
                else -> MiniServer.Response(500, "unstubbed")
            }
        }
        val repo = BaiRepo(consoleURL = "http://127.0.0.1:${server!!.port}")
        val pts = repo.points("sk-test").getOrThrow()
        assertEquals(27166591L, pts.balance)
        assertFalse(pts.hasMonthly) // summary 失败不致命
        assertEquals(0L, pts.monthlySpent)
        tearDown()
    }

    @Test
    fun repo_modelsWireFormat() = runBlocking {
        var auth: String? = null
        server = MiniServer { req ->
            auth = req.headers["authorization"]
            MiniServer.Response(200, baiModelsBody)
        }
        val repo = BaiRepo(baseURL = "http://127.0.0.1:${server!!.port}")
        val plan = repo.models("sk-test").getOrThrow()
        assertEquals("Bearer sk-test", auth)
        assertEquals(2, plan.models.size)
        tearDown()
    }

    @Test
    fun repo_modelsAuthError() = runBlocking {
        server = MiniServer { _ -> MiniServer.Response(401, """{"error":{"message":"Invalid token"}}""") }
        val repo = BaiRepo(baseURL = "http://127.0.0.1:${server!!.port}")
        val e = repo.models("sk-test").exceptionOrNull()!!
        assertEquals(BaiAuthError, e.message)
        tearDown()
    }

    @Test
    fun repo_probeFreeFlash_aliveAndDead() = runBlocking {
        val bodies = mutableListOf<String>()
        server = MiniServer { req ->
            assertTrue(req.path == "/v1/chat/completions")
            assertTrue(req.headers["authorization"] == "Bearer sk-test")
            bodies.add(req.body)
            if (req.body.contains("glm-5.3-flash")) {
                MiniServer.Response(200, """{"choices":[{"message":{"content":""}}]}""")
            } else {
                MiniServer.Response(503, """{"error":{"message":"pre_consume_token_quota_failed"}}""")
            }
        }
        val repo = BaiRepo(baseURL = "http://127.0.0.1:${server!!.port}")
        val probes = repo.probeFreeFlash("sk-test", listOf("deepseek-v4-flash", "glm-5.3-flash")).getOrThrow()
        assertEquals(2, probes.size)
        assertEquals("deepseek-v4-flash", probes[0].model)
        assertFalse(probes[0].alive)
        assertTrue(probes[0].detail.contains("HTTP 503"))
        assertTrue(probes[0].detail.contains("pre_consume_token_quota_failed"))
        assertTrue(probes[1].alive)
        assertTrue(bodies.all { it.contains("\"max_tokens\":8") && it.contains("\"stream\":false") })
        tearDown()
    }

    @Test
    fun repo_probeEmptyKeyFails() = runBlocking {
        val repo = BaiRepo(baseURL = "http://127.0.0.1:1")
        val e = repo.probeFreeFlash("  ", listOf("m")).exceptionOrNull()!!
        assertTrue(e.message!!.contains("未配置 API Key"))
        tearDown()
    }

    @Test
    fun repo_statsPaginationAndTruncation() = runBlocking {
        var page = 0
        server = MiniServer { req ->
            page++
            assertTrue(req.query.contains("page%22%3A$page"))
            val recs = (1..2).joinToString(",") {
                """{"model":"glm-5.3-flash","created_at":"2026-09-06T05:0${page}:00.000Z","input_tokens":100,"total_tokens":110}"""
            }
            MiniServer.Response(200, """{"result":{"data":{"json":{"data":[$recs],"has_more":${page < 3}}}}}""")
        }
        val repo = BaiRepo(consoleURL = "http://127.0.0.1:${server!!.port}", statsPages = 10)
        val s = repo.stats("sk-test").getOrThrow()
        assertEquals(3, page) // has_more=false 自然终止
        assertTrue(s.complete)
        assertEquals(6, s.recordsFetched)
        assertEquals(660L, s.totalTokens)
        tearDown()
    }

    @Test
    fun repo_statsTruncatesAtPageCap() = runBlocking {
        server = MiniServer { _ ->
            val recs = (1..100).joinToString(",") {
                """{"model":"m","created_at":"2026-09-06T05:00:00.000Z","total_tokens":1}"""
            }
            MiniServer.Response(200, """{"result":{"data":{"json":{"data":[$recs],"has_more":true}}}}""")
        }
        val repo = BaiRepo(consoleURL = "http://127.0.0.1:${server!!.port}", statsPages = 3)
        val s = repo.stats("sk-test").getOrThrow()
        assertFalse(s.complete)
        assertEquals(300, s.recordsFetched)
        tearDown()
    }

    @Test
    fun sanitizeServerText_stripsAnsiAndControls() {
        assertEquals("a红b", sanitizeServerText("a\u001B[31m红\u001B[0mb"))
        assertEquals("ab", sanitizeServerText("a\u001B]0;evil\u0007b"))
        assertEquals("a\nb\tc", sanitizeServerText("a\nb\tc"))
        assertEquals("余额不足", sanitizeServerText("余额不足"))
        assertEquals("ab", sanitizeServerText("a\u0000b"))
    }
}
