package com.xieguiawu.apicheckers.data

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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
 * Qwen 仓库测试：逐条移植自 Go 姊妹项目 internal/repo/repo_test.go 的 Qwen 段。
 * HTTP 服务用 java.net.ServerSocket 自建迷你单线程服务器（java.base 类，零新增依赖），
 * 端点整体注入 QwenRepo。
 */
class QwenRepoTest {

    /** 迷你 HTTP 服务器：每个连接处理一个请求后关闭连接 */
    private class MiniServer(private val handler: (Request) -> Response) {
        data class Request(
            val method: String,
            val path: String,
            val query: String,
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
                        query = target.substringAfter("?", ""),
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

    /** 固定时钟（与 Go 测试一致） */
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 8, 29, 1, 0, 0, 0, ZoneId.of("UTC"))

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    /** 与 Go qwenTestEndpoints 等价：三组端点全指向同一个本地服务 */
    private fun testEndpoints(): QwenEndpoints {
        val base = "http://127.0.0.1:${server!!.port}"
        return QwenEndpoints(
            gateway = base,
            dashboard = "$base/cn-beijing?tab=plan",
            userInfo = "",
            quota = "$base/data/api.json",
            action = "BroadScopeAspnGateway",
            region = "cn-beijing",
            consoleSite = "BAILIAN_ALIYUN",
            domain = "bailian.console.aliyun.com",
            lang = "zh-CN",
            commodityCode = "sfm_tokenplansolo_public_cn",
            origin = base,
        )
    }

    private fun repo(
        attempts: Int = 0,
        delayMs: Long = 1L,
    ) = QwenRepo(
        client = client,
        endpointsOverride = testEndpoints(),
        usageAttempts = attempts,
        usageRetryDelayMs = delayMs,
        now = { now },
    )

    private fun serve(handler: (MiniServer.Request) -> MiniServer.Response) {
        server = MiniServer(handler)
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun respond(body: String, code: Int = 200) = MiniServer.Response(code, body)

    private fun queryParam(req: MiniServer.Request, name: String): String =
        req.query.split("&").mapNotNull { kv ->
            kv.split("=", limit = 2).takeIf { it.size == 2 && it[0] == name }?.get(1)
        }.firstOrNull()?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()

    private fun parseForm(body: String): Map<String, String> =
        body.split("&").mapNotNull { kv ->
            val parts = kv.split("=", limit = 2)
            if (parts.size == 2) {
                java.net.URLDecoder.decode(parts[0], "UTF-8") to java.net.URLDecoder.decode(parts[1], "UTF-8")
            } else null
        }.toMap()

    @Test
    fun `Plan 成功且 Authorization 头正确`() {
        var auth: String? = null
        serve { req ->
            when (req.path) {
                "/compatible-mode/v1/models" -> {
                    auth = req.headers["authorization"]
                    respond(fixture("qwen_models.json"))
                }
                else -> respond("{}", 404)
            }
        }
        try {
            runBlocking {
                val plan = repo().plan(QwenAccount(id = "a", name = "n", apiKey = "sk-sp-test")).getOrThrow()
                assertEquals("Bearer sk-sp-test", auth)
                assertEquals(4, plan.models.size)
                assertEquals("deepseek-v4-flash-0731", plan.models.first())
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `Plan 401 提示区域绑定`() {
        serve { req ->
            if (req.path == "/compatible-mode/v1/models") respond("{}", 401) else respond("{}", 404)
        }
        try {
            runBlocking {
                val err = repo().plan(QwenAccount(id = "a", name = "n", apiKey = "sk-sp-bad"))
                    .exceptionOrNull()?.message.orEmpty()
                assertTrue("401 需提示区域绑定（实测同 key 换区域即 200）: $err", err.contains("区域"))
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `Plan 空 key 直接提示未配置`() {
        // 不启动服务器：空 key 不应发起任何网络请求
        server = MiniServer { respond("{}", 500) }
        try {
            runBlocking {
                val err = repo().plan(QwenAccount(id = "a", name = "n", apiKey = ""))
                    .exceptionOrNull()?.message.orEmpty()
                assertEquals("未配置 API Key", err)
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `Usage 未配 Cookie 显式提示`() {
        server = MiniServer { respond("{}", 500) }
        try {
            runBlocking {
                val err = repo().usage(QwenAccount(id = "a", name = "n", apiKey = "sk-sp-x"))
                    .exceptionOrNull()?.message.orEmpty()
                assertEquals("未配置控制台 Cookie", err)
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `Usage 成功且信封字段正确`() {
        var cookieHeader: String? = null
        var xsrf: String? = null
        var originHeader: String? = null
        var refererHeader: String? = null
        var paramsOfUsage: String? = null
        var paramsOfSub: String? = null
        var formUsage: String? = null
        serve { req ->
            when {
                req.path.startsWith("/cn-beijing") ->
                    respond("""window.ALIYUN_CONSOLE_CONFIG = { SEC_TOKEN: "tok-from-html" };""")
                req.path == "/data/api.json" -> {
                    val api = queryParam(req, "api")
                    val form = parseForm(req.body)
                    when (api) {
                        QWEN_API_USAGE -> {
                            cookieHeader = req.headers["cookie"]
                            xsrf = req.headers["x-xsrf-token"]
                            originHeader = req.headers["origin"]
                            refererHeader = req.headers["referer"]
                            paramsOfUsage = form["params"]
                            formUsage = req.body
                            respond(fixture("qwen_usage.json"))
                        }
                        QWEN_API_SUBSCRIPTION -> {
                            paramsOfSub = form["params"]
                            respond(fixture("qwen_subscription.json"))
                        }
                        else -> respond("{}", 404)
                    }
                }
                else -> respond("{}", 404)
            }
        }
        try {
            runBlocking {
                val u = repo().usage(
                    QwenAccount(
                        id = "a", name = "n", apiKey = "sk-sp-x",
                        consoleCookie = "Cookie: login_aliyunid_csrf=csrf-1; cna=anon-1",
                    ),
                ).getOrThrow()
                // sec_token 从页面透传
                assertTrue("sec_token 未从页面透传: $formUsage", formUsage!!.contains("sec_token=tok-from-html"))
                assertTrue(formUsage!!.contains("product=sfm_bailian"))
                assertTrue(formUsage!!.contains("action=BroadScopeAspnGateway"))
                assertTrue(formUsage!!.contains("region=cn-beijing"))
                // params 含 Api，且不得含 switchAgent（会绑死他人工作区）
                assertTrue("params 缺 Api: $paramsOfUsage", paramsOfUsage!!.contains(""""Api":"$QWEN_API_USAGE""""))
                assertFalse("params 不得含 switchAgent", paramsOfUsage!!.contains("switchAgent"))
                // Cookie 头剥前缀并压平空白
                assertEquals("login_aliyunid_csrf=csrf-1; cna=anon-1", cookieHeader)
                // x-xsrf-token 应取 login_aliyunid_csrf
                assertEquals("csrf-1", xsrf)
                // 同源校验头
                assertTrue("缺 Origin/Referer（网关同源校验）", originHeader!!.isNotEmpty() && refererHeader!!.isNotEmpty())
                // cna 作为 X-Anonymous-Id 进入 cornerstoneParam
                assertTrue("cna 需作为 X-Anonymous-Id 进入 cornerstoneParam", paramsOfUsage!!.contains("anon-1"))
                // 订阅接口带 commodityCode
                assertTrue("订阅接口需带 commodityCode", paramsOfSub!!.contains("sfm_tokenplansolo_public_cn"))
                // 结果
                assertEquals(79, u.fiveHour?.percent)
                assertEquals("lite", u.planCode)
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `Cookie 内已有 sec_token 不再抓页面`() {
        val dashboardCalls = AtomicInteger(0)
        serve { req ->
            when {
                req.path.startsWith("/cn-beijing") -> {
                    dashboardCalls.incrementAndGet()
                    respond("should-not-be-called")
                }
                req.path == "/data/api.json" -> respond(fixture("qwen_usage.json"))
                else -> respond("{}", 404)
            }
        }
        try {
            runBlocking {
                val u = repo().usage(
                    QwenAccount(id = "a", name = "n", apiKey = "sk-sp-x", consoleCookie = "sec_token=tok-ck; cna=a"),
                ).getOrThrow()
                assertEquals(0, dashboardCalls.get())
                assertEquals(79, u.fiveHour?.percent)
            }
        } finally {
            stopServer()
        }
    }

    // 网关偶发返回「200 Success 但无窗口」，重试后成功
    @Test
    fun `空信封重试至成功`() {
        val usageCalls = AtomicInteger(0)
        serve { req ->
            when {
                req.path == "/data/api.json" && queryParam(req, "api") == QWEN_API_USAGE -> {
                    usageCalls.incrementAndGet()
                    if (usageCalls.get() < 3) respond(fixture("qwen_usage_empty.json"))
                    else respond(fixture("qwen_usage.json"))
                }
                req.path == "/data/api.json" -> respond(fixture("qwen_subscription.json"))
                else -> respond("""SEC_TOKEN: "t";""")
            }
        }
        try {
            runBlocking {
                val u = repo(attempts = 3).usage(
                    QwenAccount(id = "a", name = "n", apiKey = "sk-sp-x", consoleCookie = "sec_token=tok-ck"),
                ).getOrThrow()
                assertEquals(3, usageCalls.get())
                assertEquals(79, u.fiveHour?.percent)
            }
        } finally {
            stopServer()
        }
    }

    // 登录失效重试无意义：必须只请求一次并抛 Cookie 错误
    @Test
    fun `登录失效不重试`() {
        val usageCalls = AtomicInteger(0)
        serve { req ->
            if (req.path == "/data/api.json" && queryParam(req, "api") == QWEN_API_USAGE) {
                usageCalls.incrementAndGet()
            }
            respond(fixture("qwen_login_notlogined.json"))
        }
        try {
            runBlocking {
                val err = repo(attempts = 3).usage(
                    QwenAccount(id = "a", name = "n", apiKey = "sk-sp-x", consoleCookie = "sec_token=tok-ck"),
                ).exceptionOrNull()?.message.orEmpty()
                assertTrue("应报 Cookie 失效: $err", err.contains("Cookie"))
                assertEquals("认证错误不得重试", 1, usageCalls.get())
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `重试耗尽仍空抛暂不可用`() {
        serve { respond(fixture("qwen_usage_empty.json")) }
        try {
            runBlocking {
                val err = repo(attempts = 2).usage(
                    QwenAccount(id = "a", name = "n", apiKey = "sk-sp-x", consoleCookie = "sec_token=tok-ck"),
                ).exceptionOrNull()?.message.orEmpty()
                assertTrue("重试耗尽应抛「暂不可用」: $err", err.contains("暂不可用"))
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `订阅接口登录失效应向上抛出`() {
        val calls = AtomicInteger(0)
        serve { req ->
            when {
                req.path == "/data/api.json" && queryParam(req, "api") == QWEN_API_USAGE ->
                    respond(fixture("qwen_usage.json"))
                req.path == "/data/api.json" -> {
                    calls.incrementAndGet()
                    respond(fixture("qwen_login_notlogined.json"))
                }
                else -> respond("""SEC_TOKEN: "t";""")
            }
        }
        try {
            runBlocking {
                val err = repo().usage(
                    QwenAccount(id = "a", name = "n", apiKey = "sk-sp-x", consoleCookie = "sec_token=tok-ck"),
                ).exceptionOrNull()?.message.orEmpty()
                assertTrue("订阅接口登录失效应向上抛出 Cookie 错误: $err", err.contains("Cookie"))
                assertEquals(1, calls.get())
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun `区域端点映射`() {
        val cn = qwenEndpointsFor("").getOrThrow()
        assertEquals("https://token-plan.cn-beijing.maas.aliyuncs.com", cn.gateway)
        assertEquals("https://bailian-cs.console.aliyun.com/data/api.json", cn.quota)
        assertEquals("BroadScopeAspnGateway", cn.action)
        assertEquals("sfm_tokenplansolo_public_cn", cn.commodityCode)
        assertEquals("zh-CN", cn.lang)

        val intl = qwenEndpointsFor("intl").getOrThrow()
        assertEquals("https://token-plan.ap-southeast-1.maas.aliyuncs.com", intl.gateway)
        assertEquals("IntlBroadScopeAspnGateway", intl.action)
        assertEquals("https://cs-data.qwencloud.com/data/api.json", intl.quota)
        assertEquals("QWENCLOUD", intl.consoleSite)
        assertEquals("sfm_tokenplansolo_public_intl", intl.commodityCode)
        assertEquals("en-US", intl.lang)

        assertTrue(qwenEndpointsFor("mars").isFailure)
    }

    @Test
    fun `Cookie 头归一化与取值`() {
        assertEquals("a=1; b=2", normalizeCookieHeader("  Cookie: a=1;  b=2\n"))
        assertEquals("a=1", normalizeCookieHeader("COOKIE: a=1"))
        assertEquals("cookie=a=1", normalizeCookieHeader("cookie=a=1"))
        val h = "a=1; cna=xyz; sec_token=tok"
        assertEquals("xyz", cookieValue(h, "cna"))
        assertEquals("tok", cookieValue(h, "sec_token"))
        assertEquals("", cookieValue(h, "nope"))
    }

    @Test
    fun `traceId 为小写 UUIDv4`() {
        val id = qwenTraceID()
        assertEquals(36, id.length)
        assertEquals('4', id[14])
        assertTrue("89ab".contains(id[19]))
        assertTrue(qwenTraceID() != id)
    }
}
