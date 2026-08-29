package com.xieguiawu.apicheckers.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Galaxy 模型序列化契约测试：JSON 序列化名与 Go models.go 的 json tag 逐条对齐
 * （便于共享 fixture 与 --json 输出对照）。
 */
class GalaxyAccountTest {
    // encodeDefaults：序列化名对齐断言需要默认值字段也出现在输出里（与 Go 侧无 omitempty 对齐）
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `账号序列化往返保留全部字段`() {
        val acc = GalaxyAccount(id = "id-1", name = "测试", accessKey = "ak-test", secretKey = "sk-test")
        val encoded = json.encodeToString(acc)
        val decoded = json.decodeFromString<GalaxyAccount>(encoded)
        assertEquals(acc, decoded)
        assertTrue(encoded.contains("\"accessKey\""))
        assertTrue(encoded.contains("\"secretKey\""))
    }

    @Test
    fun `凭据为空的账号可往返且判定未配置`() {
        val acc = GalaxyAccount(id = "a", name = "n", accessKey = "", secretKey = "")
        val decoded = json.decodeFromString<GalaxyAccount>(json.encodeToString(acc))
        assertEquals(acc, decoded)
        assertFalse(decoded.keyConfigured)
    }

    @Test
    fun `keyConfigured 空白语义`() {
        assertFalse(GalaxyAccount("a", "n", "ak", "").keyConfigured)
        assertFalse(GalaxyAccount("a", "n", "", "sk").keyConfigured)
        assertFalse(GalaxyAccount("a", "n", " ", "sk").keyConfigured)
        assertTrue(GalaxyAccount("a", "n", "ak-test", "sk-test").keyConfigured)
    }

    @Test
    fun `粘贴清理首尾空白与 ak 等前缀`() {
        assertEquals("ak-test", normalizeGalaxyAccessKey("  ak-test "))
        assertEquals("ak-test", normalizeGalaxyAccessKey("ak=ak-test"))
        assertEquals("ak-test", normalizeGalaxyAccessKey("AK=ak-test")) // 前缀大小写不敏感
        assertEquals("sk-test", normalizeGalaxySecretKey(" sk=sk-test\n")) // 首尾空白 + 前缀一起剥
        assertEquals("sk-test", normalizeGalaxySecretKey("sk-test")) // 无前缀不误伤
        assertEquals("plain", normalizeGalaxySecretKey("sk=plain")) // 前缀只剥一次
        assertEquals("sk-test", normalizeGalaxySecretKey("SK=sk-test")) // 前缀大小写不敏感
    }

    @Test
    fun `余额序列化名对齐 Go json tag`() {
        val bal = GalaxyBalance(
            name = "n", phone = "138****1111", money = 1.0, powerMoney = 2.0,
            creditMoneyQuota = 3.0, vipLevel = 2, customDiscount = 0.9, lastLoginAt = "2026-08-29T10:00:00+08:00",
        )
        val s = json.encodeToString(bal)
        for (key in listOf("\"name\"", "\"phone\"", "\"money\"", "\"power_money\"", "\"credit_money_quota\"",
            "\"vip_level\"", "\"custom_discount\"", "\"last_login_at\"")) {
            assertTrue("余额序列化缺 $key: $s", s.contains(key))
        }
        assertEquals(bal, json.decodeFromString<GalaxyBalance>(s))
    }

    @Test
    fun `统计与消耗序列化名对齐 Go json tag`() {
        val s = json.encodeToString(GalaxyStatusCount(all = 85, running = 4))
        assertTrue(s.contains("\"keepped_disk\""))
        assertTrue(s.contains("\"create_error\""))
        assertTrue(s.contains("\"running_error\""))
        // statusDefault 不得存在（契约 §2.4 弃用）
        assertFalse(s.contains("statusDefault"))

        val cost = GalaxyCost(today = 1.0, last7d = 2.0, todayPartial = true, weekPartial = false)
        val cs = json.encodeToString(cost)
        assertTrue(cs.contains("\"today_partial\":true"))
        assertTrue(cs.contains("\"week_partial\":false"))
        assertTrue(cs.contains("\"last7d\""))
    }

    @Test
    fun `实例序列化名对齐 Go json tag 且不含口令字段名`() {
        val inst = GalaxyInstance(
            name = "n", status = 1, statusText = "运行中", abnormal = false,
            gpuType = "CPU", gpuNum = 0, cpuNum = 8, memoryGb = 16,
            district = "js", host = "lyg0098xh", sshHost = "js1.example.cn", sshPort = 20812,
            image = "ubuntu22", kind = "kvm", dueAt = "2026-08-29T18:51:21+08:00",
            diskReleaseAt = "", totalCost = 0.325, payType = "power", autoRenew = true,
            createdAt = "2026-08-29T10:00:00+08:00",
        )
        val s = json.encodeToString(inst)
        for (key in listOf("\"status_text\"", "\"abnormal\"", "\"gpu_type\"", "\"gpu_num\"", "\"cpu_num\"",
            "\"memory_gb\"", "\"ssh_host\"", "\"ssh_port\"", "\"due_at\"", "\"disk_release_at\"",
            "\"total_cost\"", "\"pay_type\"", "\"auto_renew\"", "\"created_at\"")) {
            assertTrue("实例序列化缺 $key: $s", s.contains(key))
        }
        for (probe in listOf("passwd", "Passwd", "SECRET_PWD")) {
            assertFalse("实例序列化不得含口令字段名 $probe: $s", s.contains(probe))
        }
        assertEquals(inst, json.decodeFromString<GalaxyInstance>(s))
    }

    @Test
    fun `toString 不得带出 SecretKey`() {
        // 防未来调试性 Log.d(state)：任何格式化输出都不能泄明文凭据
        val acc = GalaxyAccount("id-1", "n", "ak-test", "sk-topsecret")
        val s = acc.toString()
        assertFalse("toString 泄漏 SecretKey: $s", s.contains("sk-topsecret"))
        assertTrue("toString 应保留账号名: $s", s.contains("n"))
        assertTrue("toString 应打码标记: $s", s.contains("****"))
    }
}
