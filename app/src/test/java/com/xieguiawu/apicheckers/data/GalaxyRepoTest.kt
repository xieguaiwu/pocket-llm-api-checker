package com.xieguiawu.apicheckers.data

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智星云仓库测试：逐条移植自 Go 姊妹项目 internal/repo/galaxy_test.go。
 * HTTP 服务用 java.net.ServerSocket 自建迷你单线程服务器（java.base 类，零新增依赖）。
 * 测试凭据一律假值（ak-test / sk-test）。
 */
class GalaxyRepoTest {

    /** 迷你 HTTP 服务器：每个连接处理一个请求后关闭连接 */
    private class MiniServer(private val handler: (Request) -> Response) {
        data class Request(
            val method: String,
            val path: String,
            val headers: Map<String, String>,
            val body: String,
        )

        data class Response(val code: Int, val body: String)

        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort

        init {
            Thread {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (e: Exception) {
                        break // server closed
                    }
                    try {
                        handle(socket)
                    } catch (_: Exception) {
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun handle(socket: Socket) {
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
                val resp = handler(
                    Request(
                        method = parts[0],
                        path = target.substringBefore("?"),
                        headers = headers,
                        body = body,
                    ),
                )
                writeResponse(output, resp.code, resp.body)
            }
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        private fun readBytes(input: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r == -1) break
                off += r
            }
            return buf
        }

        private fun writeResponse(output: OutputStream, code: Int, body: String) {
            val payload = body.toByteArray(Charsets.UTF_8)
            val reason = if (code == 200) "OK" else "ERR"
            val head = buildString {
                append("HTTP/1.1 $code $reason\r\n")
                append("Content-Length: ${payload.size}\r\n")
                append("Content-Type: application/json\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(payload)
            output.flush()
        }

        fun stop() {
            runCatching { server.close() }
        }
    }

    private var server: MiniServer? = null
    private val client = OkHttpClient()

    /** 固定时钟（与 Go 测试一致：2026-08-29 18:00 东八区） */
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 8, 29, 18, 0, 0, 0, ZoneId.of("Asia/Shanghai"))

    private fun testAccount() = GalaxyAccount(id = "g1", name = "测试", accessKey = "ak-test", secretKey = "sk-test")

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    /** 与 Go galaxyOK 等价：包一层成功信封 */
    private fun ok(data: String) = """{"success":true,"code":"2000","message":"","data":$data}"""

    private fun repo(
        costPages: Int = 0,
        instancePages: Int = 0,
        nonce: () -> String = { galaxyRandomNonce(12) },
    ) = GalaxyRepo(
        client = client,
        baseURL = "http://127.0.0.1:${server!!.port}",
        now = { now },
        nonce = nonce,
        costPages = costPages,
        instancePages = instancePages,
    )

    private fun serve(handler: (MiniServer.Request) -> MiniServer.Response) {
        server = MiniServer(handler)
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun respond(body: String, code: Int = 200) = MiniServer.Response(code, body)

    private fun parseForm(body: String): Map<String, String> =
        body.split("&").mapNotNull { kv ->
            val parts = kv.split("=", limit = 2)
            if (parts.size == 2) {
                java.net.URLDecoder.decode(parts[0], "UTF-8") to java.net.URLDecoder.decode(parts[1], "UTF-8")
            } else null
        }.toMap()

    /** 与 Go mustHandler 等价：校验公共参数与签名（服务端复算 = 与平台同一套算法） */
    private fun signedHandler(body: (MiniServer.Request) -> String): (MiniServer.Request) -> MiniServer.Response =
        { req ->
            val ct = req.headers["content-type"].orEmpty()
            assertTrue("Content-Type 应为表单编码: $ct", ct.startsWith("application/x-www-form-urlencoded"))
            val form = parseForm(req.body)
            for (k in listOf("apikey", "timestamp", "nonce", "sign")) {
                assertTrue("缺少公共参数 $k: ${req.body}", form[k]?.isNotEmpty() == true)
            }
            val params = form - "sign"
            assertEquals(
                "签名校验失败（服务端复算）",
                galaxySign(params, "sk-test"),
                form["sign"],
            )
            respond(body(req))
        }

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ── 余额 ───────────────────────────────────────────────

    @Test
    fun `余额成功且路径与签名正确`() {
        serve(signedHandler { req ->
            assertEquals("/account/get_main_account_info", req.path)
            ok(fixture("galaxy_account_info.json"))
        })
        try {
            runBlocking {
                val bal = repo().balance(testAccount()).getOrThrow()
                assertEquals(96.2805, bal.money, 1e-9)
                assertEquals("138****1111", bal.phone)
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `信封错误逐条映射`() {
        val cases = listOf(
            """{"success":false,"code":"4000","message":"accesskey不存在!","data":null}""" to "AccessKey 无效或已删除",
            """{"success":false,"code":"4000","message":"sign验证失败!","data":null}""" to "签名校验失败",
            """{"success":false,"code":"4000","message":"请先完成实名认证","data":null}""" to "实名认证",
            """{"success":false,"code":"4000","message":"时间戳错误","data":null}""" to "本机时钟不准",
            """{"success":false,"code":"4000","message":"page_size参数超限!","data":null}""" to "page_size参数超限",
            """{"success":false,"code":"4000","message":"","data":null}""" to "code=4000",
        )
        for ((envelope, want) in cases) {
            serve { respond(envelope) }
            try {
                runBlocking {
                    val err = repo().balance(testAccount()).exceptionOrNull()?.message.orEmpty()
                    assertTrue("信封错误应含「$want」，实得「$err」", err.contains(want))
                }
            } finally {
                stopServer()
            }
        }
    }

    @Test
    fun `code 数字形式容忍`() {
        serve { respond("""{"success":true,"code":2000,"message":"","data":{"Money":1,"Name":"n","Phone":""}}""") }
        try {
            runBlocking {
                val bal = repo().balance(testAccount()).getOrThrow()
                assertEquals(1.0, bal.money, 1e-9)
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `data 为 null 报错`() {
        serve { respond("""{"success":true,"code":"2000","message":"","data":null}""") }
        try {
            runBlocking {
                val err = repo().balance(testAccount()).exceptionOrNull()?.message.orEmpty()
                assertTrue("data=null 应报错，实得 $err", err.contains("data"))
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `HTTP 错误报状态码`() {
        serve { respond("<html>gateway</html>", 502) }
        try {
            runBlocking {
                val err = repo().balance(testAccount()).exceptionOrNull()?.message.orEmpty()
                assertTrue("非 2xx 应报 HTTP 码，实得 $err", err.startsWith("HTTP 502"))
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `非 JSON 响应报格式错误`() {
        serve { respond("not json") }
        try {
            runBlocking {
                val err = repo().balance(testAccount()).exceptionOrNull()?.message.orEmpty()
                assertTrue("非 JSON 响应应报格式错误，实得 $err", err.contains("响应格式错误"))
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `缺凭据直接失败且不发请求`() {
        val hits = AtomicInteger(0)
        serve {
            hits.incrementAndGet()
            respond("{}")
        }
        try {
            runBlocking {
                assertTrue(repo().balance(GalaxyAccount("g", "n", "only-ak", "")).isFailure)
                assertTrue(repo().statusCount(GalaxyAccount("g", "n", "", "")).isFailure)
            }
            assertEquals("缺凭据不该发请求", 0, hits.get())
        } finally {
            stopServer()
        }
    }

    // ── 实例统计 ───────────────────────────────────────────

    @Test
    fun `统计成功`() {
        serve(signedHandler { req ->
            assertEquals("/instance/get_instance_status_count", req.path)
            ok(fixture("galaxy_status_count.json"))
        })
        try {
            runBlocking {
                val s = repo().statusCount(testAccount()).getOrThrow()
                assertEquals(85, s.all)
                assertEquals(4, s.running)
            }
        } finally {
            stopServer()
        }
    }

    // ── 实例列表 ───────────────────────────────────────────

    @Test
    fun `实例 limit 截断且带上过滤`() {
        val pages = mutableListOf<String>()
        serve(signedHandler { req ->
            assertEquals("/instance/get_instance_list", req.path)
            val form = parseForm(req.body)
            pages.add("${form["page"]}/${form["page_size"]}/${form["status_type"]}")
            ok(fixture("galaxy_instance_list.json"))
        })
        try {
            runBlocking {
                val list = repo().instances(testAccount(), GalaxyStatusDefault, 2).getOrThrow()
                assertEquals("应截断到 limit=2", 2, list.size)
            }
            assertEquals("应只请求一页且带上过滤", listOf("1/2/statusDefault"), pages)
        } finally {
            stopServer()
        }
    }

    @Test
    fun `page_size 夹到 100 且受翻页上限`() {
        val hits = AtomicInteger(0)
        var seen: String? = null
        serve { req ->
            val form = parseForm(req.body)
            if (hits.incrementAndGet() > 6) {
                respond("""{"success":true,"code":"2000","message":"","data":{"list":[],"has_more":false,"total_count":0}}""")
            } else {
                seen = form["page_size"]
                respond(ok("""{"list":[],"has_more":true,"total_count":999}"""))
            }
        }
        try {
            runBlocking {
                repo().instances(testAccount(), GalaxyStatusAll, 500).getOrThrow()
            }
            assertEquals("page_size 应夹到 100", "100", seen)
            assertTrue("翻页数应受 instancePages 限制，实发 ${hits.get()}", hits.get() <= 3)
        } finally {
            stopServer()
        }
    }

    // ── 消耗 ───────────────────────────────────────────────

    @Test
    fun `消耗翻到窗口外停翻`() {
        val hits = AtomicInteger(0)
        serve(signedHandler { req ->
            assertEquals("/billing/get_balance_change_list", req.path)
            val page = parseForm(req.body)["page"]
            hits.incrementAndGet()
            if (page == "1") {
                ok("""{"list":[{"CreateTime":"2026-08-29 10:00:00","DiffMoney":-1,"DiffPower":0,"MoneyLeft":9,"Remark":"续费"}],"has_more":true,"total_count":500}""")
            } else {
                ok("""{"list":[{"CreateTime":"2026-08-20 10:00:00","DiffMoney":-2,"DiffPower":0,"MoneyLeft":7,"Remark":"很久以前"}],"has_more":true,"total_count":500}""")
            }
        })
        try {
            runBlocking {
                val cost = repo().cost(testAccount()).getOrThrow()
                assertEquals("窗口取完应停止翻页", 2, hits.get())
                assertEquals("今日消耗只该含今日条目", 1.0, cost.today, 1e-9)
                assertFalse("已翻到窗口下界外仍标下限", cost.todayPartial || cost.weekPartial)
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `消耗页数上限与下限标记`() {
        val hits = AtomicInteger(0)
        serve {
            hits.incrementAndGet()
            respond(ok("""{"list":[{"CreateTime":"2026-08-29 10:00:00","DiffMoney":-1,"DiffPower":0,"MoneyLeft":9,"Remark":"x"}],"has_more":true,"total_count":9999}"""))
        }
        try {
            runBlocking {
                val cost = repo(costPages = 2).cost(testAccount()).getOrThrow()
                assertEquals("应受 costPages 上限约束", 2, hits.get())
                assertTrue("翻不完时两个窗口都该标下限", cost.todayPartial && cost.weekPartial)
            }
        } finally {
            stopServer()
        }
    }

    // ── nonce / 时间戳 / 线上格式 ──────────────────────────

    @Test
    fun `nonce 12 位字母数字`() {
        val seen = mutableSetOf<String>()
        for (i in 0 until 200) {
            val n = galaxyRandomNonce(12)
            assertEquals("nonce 长度不符: $n", 12, n.length)
            assertTrue("nonce 非字母数字: $n", n.matches(Regex("[A-Za-z0-9]{12}")))
            assertTrue("nonce 重复（平台会拒绝重复随机串）: $n", seen.add(n))
        }
    }

    @Test
    fun `timestamp 取注入时钟`() {
        var ts: String? = null
        serve { req ->
            ts = parseForm(req.body)["timestamp"]
            respond(ok("""{"Money":1,"Name":"n","Phone":""}"""))
        }
        try {
            runBlocking {
                repo().balance(testAccount()).getOrThrow()
            }
            assertEquals(now.toEpochSecond().toString(), ts)
        } finally {
            stopServer()
        }
    }

    /**
     * 线上格式金标准：固定时钟 + 固定 nonce 下，body 必须只含 4 个公共参数，
     * 且 sign = 独立复算（手工拼串 + MD5）的结果。
     * 刻意不复用 galaxySign——自证式的服务端复算只验自洽，拼串顺序变了也测不出。
     */
    @Test
    fun `线上格式金标准`() {
        var body: String? = null
        serve { req ->
            body = req.body
            respond(ok("""{"Money":1,"Name":"n","Phone":""}"""))
        }
        try {
            runBlocking {
                repo(nonce = { "testnonce0001" }).balance(testAccount()).getOrThrow()
            }
            val form = parseForm(body!!)
            val want = mapOf(
                "apikey" to "ak-test",
                "nonce" to "testnonce0001",
                "timestamp" to now.toEpochSecond().toString(),
            )
            assertEquals("body 参数集合不符（应只有 4 个公共参数）", want.size + 1, form.size)
            for ((k, v) in want) {
                assertEquals("参数 $k 不符", v, form[k])
            }
            val independent = md5Hex("apikey=ak-test&nonce=testnonce0001&timestamp=${want["timestamp"]}&secret=sk-test")
            assertEquals("sign 与独立复算不符", independent, form["sign"])
        } finally {
            stopServer()
        }
    }
}
