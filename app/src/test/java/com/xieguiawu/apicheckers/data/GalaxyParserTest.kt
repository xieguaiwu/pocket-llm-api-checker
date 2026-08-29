package com.xieguiawu.apicheckers.data

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智星云 AI Galaxy 解析器测试：逐条移植自 Go 姊妹项目 internal/parsers/galaxy_test.go。
 * fixture 形状取契约 §2.6 实测快照，凭据与口令字段用假值（SECRET_PWD_* 哨兵）。
 */
class GalaxyParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    // 固定时钟（与 Go 测试同口径：2026-08-29 18:00，东八区）
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 8, 29, 18, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
    private val serialJson = Json { ignoreUnknownKeys = true }

    // ── 签名 ───────────────────────────────────────────────

    @Test
    fun `待签名字符串字典序空值剔除`() {
        val params = mapOf(
            "apikey" to "8b90cf872569460a",
            "nonce" to "CrpsYHp",
            "param1" to "iamcoolman",
            "param2" to "18",
            "param3" to "true",
            "param4" to "", // 空值不参与签名
            "timestamp" to "1733814154",
            "sign" to "should-be-ignored",
        )
        val want = "apikey=8b90cf872569460a&nonce=CrpsYHp&param1=iamcoolman&param2=18&param3=true&timestamp=1733814154"
        assertEquals(want, galaxyStringToSign(params))
    }

    @Test
    fun `签名已知向量`() {
        val params = mapOf(
            "apikey" to "8b90cf872569460a",
            "nonce" to "CrpsYHp",
            "param1" to "iamcoolman",
            "param2" to "18",
            "param3" to "true",
            "param4" to "",
            "timestamp" to "1733814154",
            "sign" to "should-be-ignored",
        )
        assertEquals("883c5a86f9dab614490c6021da5f531c", galaxySign(params, "testsecretkey"))
    }

    @Test
    fun `签名空 secret 不拼尾缀`() {
        val params = mapOf("apikey" to "a")
        assertEquals("58bd93007b141c0164c435502bed759b", galaxySign(params, ""))
        assertEquals("7711ef1481c3c6b3f0adfcc1af027d21", galaxySign(params, "s"))
    }

    @Test
    fun `签名字节高位不为负`() {
        // 逐字节 MD5 若把有符号 Byte 直接 %02x 会出 ffffffab 式 8 位串，这里防回归
        val sig = galaxySign(mapOf("apikey" to "8b90cf872569460a", "timestamp" to "1733814154"), "k")
        assertTrue("签名必须为 32 位小写 hex: $sig", sig.matches(Regex("[0-9a-f]{32}")))
    }

    @Test
    fun `签名乱序输入仍按字典序`() {
        // 输入 map 故意乱序（timestamp 在首位、b 在 a 前）：期望与字典序向量一致。
        // 若实现退化成了「按插入顺序拼串」，本用例必须红。
        val params = linkedMapOf(
            "timestamp" to "1733814154",
            "b" to "2",
            "apikey" to "8b90cf872569460a",
            "a" to "1",
        )
        assertEquals("20106a43df3dd1ea93a70e890a574bfd", galaxySign(params, "testsecretkey"))
        // 顺带验证：乱序插入后待签名字符串仍是字典序
        assertEquals(
            "a=1&apikey=8b90cf872569460a&b=2&timestamp=1733814154",
            galaxyStringToSign(params),
        )
    }

    @Test
    fun `字符串布尔不当真值`() {
        // has_more:"false"（字符串）必须视为 false——否则会继续翻页，与 Go 行为分叉。
        // 通过公开入口 parseGalaxyInstances 验证（galaxyRawBool 是文件私有）。
        val raw = """{"list":[],"has_more":"false","total_count":0}"""
        val page = parseGalaxyInstances(raw, now).getOrThrow()
        assertTrue(page.instances.isEmpty())
        assertFalse("字符串 false 不得当真值（否则翻页行为与 Go 分叉）", page.hasMore)
    }

    // ── 状态与时间 ─────────────────────────────────────────

    @Test
    fun `状态码文案与活跃判定`() {
        val texts = mapOf(
            -2 to "已退费", -1 to "启动错误", 0 to "已结束", 1 to "运行中",
            4 to "启动中", 5 to "重启中", 7 to "重启失败", 8 to "磁盘保留",
        )
        for ((status, want) in texts) {
            assertEquals("Status=$status", want, galaxyStatusText(status))
        }
        assertEquals("未知(42)", galaxyStatusText(42))
        assertTrue(galaxyStatusActive(8))
        assertFalse(galaxyStatusActive(0))
        assertFalse(galaxyStatusActive(-2))
    }

    @Test
    fun `到期时刻按 ServerTime 折算`() {
        // due 比 server 晚 1 小时 → 到期时刻 = now + 1h
        assertEquals(now.toEpochSecond() + 3600, galaxyDeadlineUnix(3700, 100, now))
        // 无 ServerTime → 直接用 due
        assertEquals(1234L, galaxyDeadlineUnix(1234, 0, now))
        // 无到期时间 → 0
        assertEquals(0L, galaxyDeadlineUnix(0, 100, now))
    }

    @Test
    fun `手机号脱敏`() {
        assertEquals("138****1111", maskPhone("13800001111"))
        assertEquals("1****", maskPhone("1380000"))
        assertEquals("", maskPhone(""))
    }

    // ── 主账户信息 ─────────────────────────────────────────

    @Test
    fun `主账户解析`() {
        val bal = parseGalaxyBalance(fixture("galaxy_account_info.json"), now.zone).getOrThrow()
        assertEquals(96.2805, bal.money, 1e-9)
        assertEquals(12.5, bal.powerMoney, 1e-9)
        assertEquals("用户#2433", bal.name)
        assertEquals(2, bal.vipLevel)
        assertEquals("138****1111", bal.phone) // 手机号必须脱敏
        assertTrue("最后登录时间应解析出来", bal.lastLoginAt.isNotEmpty())
        assertTrue("LastLoginAt 应为 RFC3339", runCatching { Instant.parse(bal.lastLoginAt) }.isSuccess)
    }

    @Test
    fun `主账户字符串数值宽容`() {
        // 平台偶发把金额序列化成字符串（DeepSeek 同款口径），不能整体失败
        val raw = """{"Money":"12.34","PowerMoney":"0.5","CreditMoneyQuota":"0","VipLevel":"3","Name":"n","Phone":""}"""
        val bal = parseGalaxyBalance(raw).getOrThrow()
        assertEquals(12.34, bal.money, 1e-9)
        assertEquals(0.5, bal.powerMoney, 1e-9)
        assertEquals(3, bal.vipLevel)
    }

    @Test
    fun `主账户缺 Money 报错`() {
        assertTrue("缺少 Money 字段应显式失败，不能当 0 元展示", parseGalaxyBalance("""{"Name":"x"}""").isFailure)
    }

    // ── 实例统计 ───────────────────────────────────────────

    @Test
    fun `实例统计解析`() {
        val s = parseGalaxyStatusCount(fixture("galaxy_status_count.json")).getOrThrow()
        assertEquals(85, s.all)
        assertEquals(4, s.running)
        assertEquals(0, s.keeppedDisk)
        assertEquals(0, s.createError)
        assertEquals(0, s.runningError)
    }

    @Test
    fun `空统计报错`() {
        assertTrue("空统计应失败，避免把「拉取失败」显示成 0 台", parseGalaxyStatusCount("{}").isFailure)
    }

    // ── 实例列表 ───────────────────────────────────────────

    @Test
    fun `实例列表解析`() {
        val page = parseGalaxyInstances(fixture("galaxy_instance_list.json"), now).getOrThrow()
        assertEquals(4, page.instances.size)
        assertEquals(9, page.total)
        assertTrue(page.hasMore)

        val first = page.instances[0]
        assertEquals("lyg0098xh", first.host)
        assertEquals("js1.example.cn", first.sshHost)
        assertEquals(20812, first.sshPort)
        assertEquals("运行中", first.statusText)
        assertTrue(first.autoRenew)
        // ServerTime 1787998666、Due_time 1788000681 → 差 2015s，到期 = now + 2015s
        val due = Instant.parse(first.dueAt)
        assertEquals(now.toEpochSecond() + 2015, due.epochSecond)

        val gpu = page.instances[1]
        assertEquals("GeForce RTX 3080", gpu.gpuType)
        assertEquals(1, gpu.gpuNum)
        assertEquals(48, gpu.memoryGb)
        assertFalse("SubscribeStatus=2 且已取消订阅 → 不应判为自动续费", gpu.autoRenew)
        assertEquals("训练机", gpu.note)

        val keepped = page.instances[2]
        assertEquals(8, keepped.status)
        assertEquals("磁盘保留", keepped.statusText)
        assertTrue("磁盘保留实例应有磁盘释放时间", keepped.diskReleaseAt.isNotEmpty())
        assertEquals("Due_time=0 表示无到期时间，应留空而非 1970", "", keepped.dueAt)

        val abnormal = page.instances[3]
        assertTrue("IsAbnormal!=0 应判为运行异常", abnormal.abnormal)
    }

    @Test
    fun `白名单解码弃口令`() {
        // 🔴 红线 1：fixture 里放 SECRET_PWD_* 哨兵，断言解析结果与 JSON 序列化都不含这些串
        val raw = fixture("galaxy_instance_list.json")
        for (probe in listOf("SECRET_PWD_1", "SECRET_PWD_2", "SECRET_PWD_3", "SECRET_PWD_4")) {
            assertTrue("fixture 应包含 $probe 才能验证屏蔽", raw.contains(probe))
        }
        val page = parseGalaxyInstances(raw, now).getOrThrow()
        for (inst in page.instances) {
            val serialized = serialJson.encodeToString(inst)
            for (probe in listOf("SECRET_PWD", "passwd", "Passwd", "Init_passwd", "RdpPasswd", "VncPasswd")) {
                assertTrue("实例序列化结果含敏感字段 $probe: $serialized", !serialized.contains(probe))
            }
            assertTrue("toString 不得含口令哨兵: ${inst}", !inst.toString().contains("SECRET_PWD"))
        }
    }

    // ── 余额变更 ───────────────────────────────────────────

    @Test
    fun `余额变更解析`() {
        val page = parseGalaxyChanges(fixture("galaxy_balance_changes.json"), now.zone).getOrThrow()
        assertTrue(page.hasMore)
        // 5 条里 1 条 CreateTime 非法 → 跳过
        assertEquals(4, page.changes.size)
        assertEquals("现金+算力券应合并计消耗", 0.325, page.changes[1].spent, 1e-9)
        assertTrue("退费条目净消耗应为负", page.changes[2].spent < 0)
    }

    @Test
    fun `消耗聚合窗口标记`() {
        val page = parseGalaxyChanges(fixture("galaxy_balance_changes.json"), now.zone).getOrThrow()
        val cost = aggregateGalaxyCost(page.changes, page.hasMore, now)
        // 今日：0.87 + 0.325（退费 -0.17 不计入）
        assertEquals(1.195, cost.today, 1e-9)
        // 近 7 天含 08-24 那条（+100 充值为净返还 → 不计入），仍 1.195
        assertEquals(1.195, cost.last7d, 1e-9)
        assertFalse("已取到 08-24（早于今日）的明细 → 今日窗口已翻完", cost.todayPartial)
        assertTrue("最早只到 08-24，未跨过 7 天下界（08-23）→ 7 天值应标为下限", cost.weekPartial)
        assertEquals(4, cost.entries.size)
    }

    @Test
    fun `消耗聚合窗口取完不再标下限`() {
        val changes = listOf(
            GalaxyChange(at = now.minusHours(1), remark = "x", spent = 1.0, left = 0.0),
            GalaxyChange(at = now.minusDays(10), remark = "y", spent = 2.0, left = 0.0), // 早于 7 天下界
        )
        val cost = aggregateGalaxyCost(changes, hasMore = true, now = now)
        assertFalse(cost.todayPartial)
        assertFalse(cost.weekPartial)
        assertEquals(1.0, cost.today, 1e-9)
        assertEquals("10 天前的条目不该进 7 天窗口", 1.0, cost.last7d, 1e-9)
    }

    @Test
    fun `无更多页时不标下限`() {
        val changes = listOf(GalaxyChange(at = now.minusHours(1), remark = "x", spent = 1.0, left = 0.0))
        val cost = aggregateGalaxyCost(changes, hasMore = false, now = now)
        assertFalse("hasMore=false 表示明细已全部取到", cost.todayPartial || cost.weekPartial)
    }
}
