# API Checkers Android App Implementation Plan

> **For agentic workers:** 按本计划任务逐项实施。步骤用 checkbox（`- [ ]`）跟踪。

**Goal:** 一个极简深色 Android APK，快速查看 DeepSeek API 与 OpenCode（Zen + Go 两个 plan）的使用情况。OpenCode 支持 3 个账号区分。

**Architecture:** 单 Activity + Jetpack Compose（Material 3 深色纯色主题）。数据层 = OkHttp + kotlinx.serialization（JSON 解析）+ 正则解析（Zen billing 的 SolidJS SSR HTML）。密钥用 Android Keystore AES-GCM 加密后存 SharedPreferences。页面：总览（首页）→ 账号详情 → 设置。

**Tech Stack:**
- Kotlin 2.0.21 + Jetpack Compose (BOM 2024.10.00) + Material 3
- AGP 8.5.2、Gradle 8.9、compileSdk 35、minSdk 26、targetSdk 35
- OkHttp 4.12.0 + kotlinx-serialization-json 1.7.3 + kotlinx-serialization converter
- AndroidX: activity-compose、lifecycle-viewmodel-compose、navigation-compose 2.8.x
- 测试: JUnit 4.13.2 + kotlinx-coroutines-test（纯 JVM 单测，不依赖设备）

**Spec:** 需求来自用户对话（无规格文件）。验收标准见本计划末尾「Acceptance」。

## Global Constraints

1. 包名 `com.xieguiawu.apicheckers`，minSdk 26（Android 8.0，华为机型兼容），targetSdk 35
2. 应用名「API Checkers」，仅支持深色主题（用户要求深色模式纯色调），不提供浅色
3. 界面极简：背景 `#0E1116`、卡片 `#161B22`、主文字 `#E6E8EB`、次文字 `#8B949E`、强调色单一 `#58A6FF`（蓝）、警告 `#D29922`、危险 `#F85149`、成功 `#3FB950`——不允许其他颜色
4. **禁止硬编码任何 API key / cookie / token**（开发质量关卡 9）。密钥只存在于用户输入 → 加密存储 → 内存使用
5. 所有网络超时 15s；HTTP 401/403 必须在 UI 显示明确错误（如「API Key 无效或已过期」）而非崩溃
6. 中文 UI（用户是中文使用者），代码注释中文
7. 每个解析器必须有 JVM 单元测试（fixtures 放 `app/src/test/resources/fixtures/`）
8. 账号数据模型固定：`Account(id: String, name: String, goApiKey: String, workspaceId: String, authCookie: String)`——cookie 与 workspace 可选（留空则只显示 Go plan）
9. 构建产物：`app/build/outputs/apk/debug/app-debug.apk`（arm64 通用，无需 ABI 过滤）

## 外部 API 契约（全部已实测/验证）

### A. OpenCode Go usage（官方 API，API key 认证，无需 cookie）

```
GET https://opencode.ai/zen/go/v1/usage
Authorization: Bearer {goApiKey}
→ 200:
{"usage":{"rolling":{"status":"ok","percent":0,"resetsAt":"2026-08-14T16:20:08.884Z"},
          "weekly":{"status":"ok","percent":0,"resetsAt":"2026-08-17T00:00:00.884Z"},
          "monthly":{"status":"rate-limited","percent":100,"resetsAt":"2026-08-15T16:02:00.884Z"}}}
```
字段：每个窗口 `status`(ok|rate-limited)、`percent`(0-100)、`resetsAt`(ISO8601 UTC)。401 = key 无效。

### B. OpenCode Zen billing（页面 scrape，workspaceId + auth cookie）

```
GET https://opencode.ai/workspace/{workspaceId}/billing
Cookie: auth={authCookie}
User-Agent: Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 ...
→ 200 HTML（SolidJS SSR hydration）
```
解析算法（移植自 MIT 项目 4cya/pi-go-bars core.ts，已授权复用）：
1. `html.indexOf("customerID:\"cus_")` 找锚点；`html.lastIndexOf("{", start)` 找对象起点
2. 从 `{` 起深度计数到匹配 `}`，取子串 obj
3. 在 obj 内正则（字段顺序可变，逐个匹配）：
   - `balance:(-?\d+(?:\.\d+)?)` → ÷1e8 = USD（microcents）
   - `monthlyUsage:(-?\d+(?:\.\d+)?)` → ÷1e8 = USD
   - `monthlyLimit:(-?\d+(?:\.\d+)?)` → 整 USD
   - `reload:(!0|!1|true|false|null)` → boolean（!0=true, !1=false）
   - `reloadAmount:(-?\d+(?:\.\d+)?)` → 整 USD
   - `reloadTrigger:(-?\d+(?:\.\d+)?)` → 整 USD
4. 找不到 `customerID:"cus_` → 返回错误「会话已过期，请更新 Cookie」
5. 找到对象但 balance/monthlyUsage/monthlyLimit 全 null → 返回错误「页面结构已变化」

### C. DeepSeek 余额（官方 API，API key 认证）

```
GET https://api.deepseek.com/user/balance
Authorization: Bearer {apiKey}
→ 200:
{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"120.00","granted_balance":"0.00","topped_up_balance":"120.00"}]}
```
字段：`is_available`(bool)、`balance_infos[]`：`currency`、`total_balance`、`granted_balance`、`topped_up_balance`（字符串金额）。401 = key 无效。

### D. DeepSeek 消费明细（platform 页面 API，浏览器登录 token）

```
GET https://platform.deepseek.com/api/v0/usage/cost?month={m}&year={y}
Authorization: Bearer {platformToken}   ← 浏览器登录 token，非 API key
Accept: application/json
x-app-version: 1.0.0
Referer: https://platform.deepseek.com/usage
User-Agent: Mozilla/5.0 ... Chrome/126 Safari/537.36
→ 200:
{"code":0,"data":{"biz_data":[{"days":[{"date":"2026-08-14","data":[{"model":"deepseek-chat",
  "usage":[{"type":"input","amount":0.123},{"type":"output","amount":0.456}]}]}]}]}}
```
- `code` 40003 = token 失效；非 0 = 失败
- 用法：拉本月+上月两个月，按 date 汇总每日金额（sum 所有 type 的 amount），算今天/近7天/近30天
- 金额单位：人民币元

## 文件结构

```
api-checkers/
├── settings.gradle.kts
├── build.gradle.kts              (根，插件声明)
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties + gradle-wrapper.jar + gradlew
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/xieguiawu/apicheckers/
│       │   │   ├── MainActivity.kt
│       │   │   ├── data/
│       │   │   │   ├── Models.kt            (全部数据模型)
│       │   │   │   ├── Parsers.kt           (GoUsage/ZenBilling/DeepSeek 解析)
│       │   │   │   ├── ApiClient.kt         (OkHttp 单例 + 请求执行)
│       │   │   │   ├── Repositories.kt      (DeepSeekRepo/OpenCodeRepo)
│       │   │   │   └── SecureSettings.kt    (Keystore AES-GCM + SharedPreferences)
│       │   │   ├── ui/
│       │   │   │   ├── theme/Theme.kt       (深色纯色主题)
│       │   │   │   ├── HomeScreen.kt        (总览)
│       │   │   │   ├── DetailScreen.kt      (账号详情)
│       │   │   │   └── SettingsScreen.kt    (设置)
│       │   │   └── AppViewModel.kt
│       │   └── res/
│       │       ├── values/strings.xml, themes.xml
│       │       ├── values-night/themes.xml  (强制深色)
│       │       └── mipmap/ic_launcher 等
│       └── test/
│           ├── java/com/xieguiawu/apicheckers/
│           │   ├── GoUsageParserTest.kt
│           │   ├── ZenBillingParserTest.kt
│           │   ├── DeepSeekParserTest.kt
│           │   └── SecureSettingsTest.kt    (仅测试加解密算法层，不碰 Android API)
│           └── resources/fixtures/
│               ├── go_usage.json            (实测真实数据)
│               ├── billing.html             (pi-go-bars testdata，MIT)
│               ├── deepseek_balance.json    (官方文档示例)
│               └── deepseek_cost.json       (本计划 D 节格式)
├── README.md / README_zh.md
├── .gitignore
└── docs/plans/2026-08-14-api-checker-app.md
```

## Task 0: 环境准备（SDK + Gradle 脚手架）

**Files:**
- Create: `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/wrapper/gradle-wrapper.properties`、`app/build.gradle.kts`、`app/proguard-rules.pro`、`app/src/main/AndroidManifest.xml`、`.gitignore`

**Interfaces:**
- Consumes: 无
- Produces: 可编译的空 Compose 项目（`./gradlew :app:assembleDebug` 成功），后续任务的落点

- [ ] **Step 1: 安装 Android SDK 组件**

```bash
# 下载 commandline-tools（若 ~/Android/Sdk 无 cmdline-tools）
cd ~/Android/Sdk
curl -sSLo /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q -o /tmp/cmdtools.zip -d /tmp/cmdtools && mkdir -p cmdline-tools && mv /tmp/cmdtools/cmdline-tools cmdline-tools/latest
# 安装 platforms + build-tools
yes | cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null 2>&1
cmdline-tools/latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0"
```
Expected: `~/Android/Sdk/platforms/android-35/` 与 `~/Android/Sdk/build-tools/35.0.0/` 存在

- [ ] **Step 2: 下载 Gradle 并生成 wrapper**

```bash
curl -sSLo /tmp/gradle.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip
unzip -q /tmp/gradle.zip -d /opt/gradle 2>/dev/null || unzip -q /tmp/gradle.zip -d ~/.local/share
GRADLE_HOME=$(ls -d /opt/gradle/gradle-8.9 ~/.local/share/gradle-8.9 2>/dev/null | head -1)
cd /home/xieguiawu/Desktop/android-projects/api-checkers
$GRADLE_HOME/bin/gradle wrapper --gradle-version 8.9
```
Expected: `./gradlew` 存在，`./gradlew --version` 输出 Gradle 8.9

- [ ] **Step 3: 写项目脚手架文件**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "api-checkers"
include(":app")
```

`build.gradle.kts` (根):
```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

`app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.xieguiawu.apicheckers"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.xieguiawu.apicheckers"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

`AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:label="@string/app_name"
        android:theme="@style/Theme.ApiCheckers"
        android:icon="@mipmap/ic_launcher"
        android:usesCleartextTraffic="false">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`.gitignore`:
```gitignore
.gradle/
build/
local.properties
*.apk
.idea/
captures/
.DS_Store
```

- [ ] **Step 4: 最小可编译验证**

写一个最小 `MainActivity.kt`（Compose setContent 显示 "Hello"）+ `res/values/strings.xml`（app_name）+ `res/values/themes.xml`（Theme.ApiCheckers 父 Theme.Material3 相关或直接 `android:Theme.Material.NoActionBar`）+ 简单 launcher icon（用 adaptive icon XML + 纯色 drawable，不引入图片资源）。

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL，`app/build/outputs/apk/debug/app-debug.apk` 存在

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "chore: Android 项目脚手架（Gradle 8.9 + Compose + 深色主题骨架）"
```

## Task 1: 数据模型与解析器（纯 JVM，带单测）

**Files:**
- Create: `app/src/main/java/com/xieguiawu/apicheckers/data/Models.kt`
- Create: `app/src/main/java/com/xieguiawu/apicheckers/data/Parsers.kt`
- Create: `app/src/test/java/com/xieguiawu/apicheckers/GoUsageParserTest.kt`
- Create: `app/src/test/java/com/xieguiawu/apicheckers/ZenBillingParserTest.kt`
- Create: `app/src/test/java/com/xieguiawu/apicheckers/DeepSeekParserTest.kt`
- Create: `app/src/test/resources/fixtures/go_usage.json`
- Create: `app/src/test/resources/fixtures/billing.html`（复制自 /tmp/billing_fixture.html）
- Create: `app/src/test/resources/fixtures/deepseek_balance.json`
- Create: `app/src/test/resources/fixtures/deepseek_cost.json`

**Interfaces:**
- Consumes: 无（纯 Kotlin 标准库 + kotlinx.serialization）
- Produces:
  - `data class GoWindow(status: String, percent: Int, resetsAt: String)`、`data class GoUsage(rolling: GoWindow?, weekly: GoWindow?, monthly: GoWindow?)`
  - `data class ZenBilling(balanceUsd: Double, monthlyUsageUsd: Double, monthlyLimitUsd: Double, autoReload: Boolean, reloadAmountUsd: Double, reloadTriggerUsd: Double)`
  - `data class DeepSeekBalanceInfo(currency: String, totalBalance: Double, grantedBalance: Double, toppedUpBalance: Double)`、`data class DeepSeekBalance(isAvailable: Boolean, infos: List<DeepSeekBalanceInfo>)`
  - `data class DeepSeekCostDay(date: String, total: Double)`、`data class DeepSeekCost(today: Double, last7d: Double, last30d: Double, days: List<DeepSeekCostDay>)`
  - `object Parsers { fun parseGoUsage(json: String): GoUsage; fun parseZenBilling(html: String): Result<ZenBilling>; fun parseDeepSeekBalance(json: String): Result<DeepSeekBalance>; fun parseDeepSeekCost(json: String): Result<DeepSeekCost> }`
  - 解析失败返回 `Result.failure(Exception("..."))`，错误消息人类可读

- [ ] **Step 1: 写 fixture 文件**

`fixtures/go_usage.json` 内容（实测真实数据）:
```json
{"usage":{"rolling":{"status":"ok","percent":0,"resetsAt":"2026-08-14T16:20:08.884Z"},"weekly":{"status":"ok","percent":0,"resetsAt":"2026-08-17T00:00:00.884Z"},"monthly":{"status":"rate-limited","percent":100,"resetsAt":"2026-08-15T16:02:00.884Z"}}}
```

`fixtures/deepseek_balance.json`:
```json
{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"120.00","granted_balance":"0.00","topped_up_balance":"120.00"}]}
```

`fixtures/deepseek_cost.json`:
```json
{"code":0,"data":{"biz_data":[{"days":[{"date":"2026-08-14","data":[{"model":"deepseek-chat","usage":[{"type":"input","amount":1.5},{"type":"output","amount":2.5}]},{"model":"deepseek-reasoner","usage":[{"type":"input","amount":0.5}]}]},{"date":"2026-08-13","data":[{"model":"deepseek-chat","usage":[{"type":"input","amount":0.3}]}]}]}]}}
```

`fixtures/billing.html`: `cp /tmp/billing_fixture.html app/src/test/resources/fixtures/billing.html`

- [ ] **Step 2: 写测试（先红）**

`GoUsageParserTest.kt`:
```kotlin
class GoUsageParserTest {
    private val json = javaClass.classLoader!!.getResource("fixtures/go_usage.json")!!.readText()
    @Test fun `解析真实 go usage JSON`() {
        val usage = Parsers.parseGoUsage(json)
        assertEquals("ok", usage.rolling?.status)
        assertEquals(0, usage.rolling?.percent)
        assertEquals("2026-08-14T16:20:08.884Z", usage.rolling?.resetsAt)
        assertEquals(100, usage.monthly?.percent)
        assertEquals("rate-limited", usage.monthly?.status)
    }
    @Test fun `非法 JSON 抛异常`() { assertThrows(Exception::class.java) { Parsers.parseGoUsage("{bad") } }
    @Test fun `窗口缺失不崩溃`() {
        val u = Parsers.parseGoUsage("""{"usage":{"rolling":{"status":"ok","percent":1,"resetsAt":"x"}}}""")
        assertNull(u.weekly); assertNull(u.monthly)
    }
}
```

`ZenBillingParserTest.kt`:
```kotlin
class ZenBillingParserTest {
    private val html = javaClass.classLoader!!.getResource("fixtures/billing.html")!!.readText()
    @Test fun `解析真实 billing 页面`() {
        val b = Parsers.parseZenBilling(html).getOrThrow()
        // balance:1999960750 → $19.9996075；monthlyUsage:39250 → $0.0003925；monthlyLimit:50
        assertEquals(19.9996075, b.balanceUsd, 1e-6)
        assertEquals(0.0003925, b.monthlyUsageUsd, 1e-9)
        assertEquals(50.0, b.monthlyLimitUsd, 1e-6)
    }
    @Test fun `无 customerID 返回会话过期错误`() {
        val r = Parsers.parseZenBilling("<html>login page</html>")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("会话"))
    }
    @Test fun `字段缺失容忍`() {
        val html2 = html.replace("monthlyUsage:39250", "monthlyUsage:null")
        val b = Parsers.parseZenBilling(html2).getOrThrow()
        assertEquals(0.0, b.monthlyUsageUsd, 1e-9)
    }
}
```
（先让测试编译失败——Parsers 还不存在；步骤 3 实现后转绿）

`DeepSeekParserTest.kt`:
```kotlin
class DeepSeekParserTest {
    @Test fun `解析余额`() {
        val json = javaClass.classLoader!!.getResource("fixtures/deepseek_balance.json")!!.readText()
        val b = Parsers.parseDeepSeekBalance(json).getOrThrow()
        assertTrue(b.isAvailable)
        assertEquals("CNY", b.infos[0].currency)
        assertEquals(120.0, b.infos[0].totalBalance, 1e-6)
        assertEquals(0.0, b.infos[0].grantedBalance, 1e-6)
        assertEquals(120.0, b.infos[0].toppedUpBalance, 1e-6)
    }
    @Test fun `解析消费明细`() {
        val json = javaClass.classLoader!!.getResource("fixtures/deepseek_cost.json")!!.readText()
        val c = Parsers.parseDeepSeekCost(json).getOrThrow()
        assertEquals(4.5, c.today, 1e-6)   // 1.5+2.5+0.5
        assertEquals(4.8, c.last7d, 1e-6)  // +0.3
        assertEquals(4.8, c.last30d, 1e-6)
        assertEquals(2, c.days.size)
        assertEquals("2026-08-14", c.days[0].date)
        assertEquals(4.5, c.days[0].total, 1e-6)
    }
    @Test fun `code 40003 报 token 失效`() {
        val r = Parsers.parseDeepSeekCost("""{"code":40003}""")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("失效"))
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: 编译失败（Parsers 未定义）——这是预期的红

- [ ] **Step 4: 实现 Models.kt 与 Parsers.kt**

`Models.kt`:
```kotlin
package com.xieguiawu.apicheckers.data

import kotlinx.serialization.Serializable

@Serializable
data class GoWindow(val status: String = "", val percent: Int = 0, val resetsAt: String = "")
@Serializable
data class GoUsagePayload(val usage: GoUsage)
@Serializable
data class GoUsage(val rolling: GoWindow? = null, val weekly: GoWindow? = null, val monthly: GoWindow? = null)

data class ZenBilling(
    val balanceUsd: Double, val monthlyUsageUsd: Double, val monthlyLimitUsd: Double,
    val autoReload: Boolean, val reloadAmountUsd: Double, val reloadTriggerUsd: Double,
)

@Serializable
data class DeepSeekBalanceInfo(
    val currency: String = "",
    @SerialName("total_balance") val totalBalance: String = "0",
    @SerialName("granted_balance") val grantedBalance: String = "0",
    @SerialName("topped_up_balance") val toppedUpBalance: String = "0",
)
@Serializable
data class DeepSeekBalancePayload(val is_available: Boolean = false, val balance_infos: List<DeepSeekBalanceInfo> = emptyList())

data class DeepSeekBalance(val isAvailable: Boolean, val infos: List<DeepSeekBalanceInfo>)

data class DeepSeekCostDay(val date: String, val total: Double)
data class DeepSeekCost(val today: Double, val last7d: Double, val last30d: Double, val days: List<DeepSeekCostDay>)

@Serializable
data class Account(
    val id: String, val name: String, val goApiKey: String,
    val workspaceId: String = "", val authCookie: String = "",
) { val hasZen: Boolean get() = workspaceId.isNotBlank() && authCookie.isNotBlank() }
```

`Parsers.kt`（关键实现，参考外部契约 B 节算法）:
```kotlin
package com.xieguiawu.apicheckers.data

import kotlinx.serialization.json.Json

object Parsers {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseGoUsage(raw: String): GoUsage {
        val payload = json.decodeFromString<GoUsagePayload>(raw)
        return payload.usage
    }

    private val RE_BALANCE = Regex("balance:(-?\\d+(?:\\.\\d+)?)")
    private val RE_MONTHLY_USAGE = Regex("monthlyUsage:(-?\\d+(?:\\.\\d+)?)")
    private val RE_MONTHLY_LIMIT = Regex("monthlyLimit:(-?\\d+(?:\\.\\d+)?)")
    private val RE_RELOAD = Regex("reload:(!0|!1|true|false|null)")
    private val RE_RELOAD_AMOUNT = Regex("reloadAmount:(-?\\d+(?:\\.\\d+)?)")
    private val RE_RELOAD_TRIGGER = Regex("reloadTrigger:(-?\\d+(?:\\.\\d+)?)")

    fun parseZenBilling(html: String): Result<ZenBilling> = runCatching {
        val start = html.indexOf("customerID:\"cus_")
        if (start == -1) error("未找到账单数据：Cookie 可能已过期，请在设置中更新")
        val braceStart = html.lastIndexOf("{", start)
        if (braceStart == -1) error("账单页面结构异常")
        var depth = 0
        var end = -1
        for (i in braceStart until html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end == -1) error("账单页面结构异常")
        val obj = html.substring(braceStart, end + 1)
        fun num(re: Regex): Double? = re.find(obj)?.groupValues?.get(1)?.toDoubleOrNull()
        val balance = num(RE_BALANCE)
        val monthlyUsage = num(RE_MONTHLY_USAGE)
        val monthlyLimit = num(RE_MONTHLY_LIMIT)
        if (balance == null && monthlyUsage == null && monthlyLimit == null) error("账单页面结构已变化，请更新应用")
        ZenBilling(
            balanceUsd = (balance ?: 0.0) / 1e8,
            monthlyUsageUsd = (monthlyUsage ?: 0.0) / 1e8,
            monthlyLimitUsd = monthlyLimit ?: 0.0,
            autoReload = when (RE_RELOAD.find(obj)?.groupValues?.get(1)) {
                "!0", "true" -> true
                else -> false
            },
            reloadAmountUsd = num(RE_RELOAD_AMOUNT) ?: 0.0,
            reloadTriggerUsd = num(RE_RELOAD_TRIGGER) ?: 0.0,
        )
    }

    fun parseDeepSeekBalance(raw: String): Result<DeepSeekBalance> = runCatching {
        val p = json.decodeFromString<DeepSeekBalancePayload>(raw)
        DeepSeekBalance(isAvailable = p.is_available, infos = p.balance_infos)
    }

    fun parseDeepSeekCost(raw: String): Result<DeepSeekCost> = runCatching {
        val root = json.parseToJsonElement(raw).jsonObject
        val code = root["code"]?.jsonPrimitive?.intOrNull
        if (code == 40003) error("DeepSeek 平台登录已失效，请更新平台 Token")
        val days = mutableListOf<DeepSeekCostDay>()
        root["data"]?.jsonObject?.get("biz_data")?.jsonArray?.forEach { biz ->
            biz.jsonObject["days"]?.jsonArray?.forEach { dayEl ->
                val day = dayEl.jsonObject
                val date = day["date"]?.jsonPrimitive?.content ?: return@forEach
                var total = 0.0
                day["data"]?.jsonArray?.forEach { modelEl ->
                    modelEl.jsonObject["usage"]?.jsonArray?.forEach { u ->
                        val amt = u.jsonObject["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        total += amt
                    }
                }
                days.add(DeepSeekCostDay(date, total))
            }
        }
        // 计算今天/近7天/近30天（以本地当天为基准，服务器端已按天聚合）
        val now = java.time.LocalDate.now()
        val todayKey = now.toString()
        var today = 0.0; var d7 = 0.0; var d30 = 0.0
        for (i in 0 until 30) {
            val key = now.minusDays(i.toLong()).toString()
            val v = days.firstOrNull { it.date == key }?.total ?: 0.0
            if (i == 0) today = v
            if (i < 7) d7 += v
            d30 += v
        }
        val recent = days.sortedByDescending { it.date }.take(7)
        DeepSeekCost(today, d7, d30, recent)
    }
}
```
（last7d/last30d 的实现：以当天为基准，days 中 date 距今天 ≤6 天计入 7d、≤29 天计入 30d；`java.time` 在 minSdk 26 可用）

- [ ] **Step 5: 跑测试确认通过**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: 4 个测试类全绿（go usage 3 + zen billing 3 + deepseek 3 ≥ 9 个测试）

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat: 数据模型与解析器（GoUsage/ZenBilling/DeepSeek）+ 单元测试"
```

## Task 2: 网络层与仓库

**Files:**
- Create: `app/src/main/java/com/xieguiawu/apicheckers/data/ApiClient.kt`
- Create: `app/src/main/java/com/xieguiawu/apicheckers/data/Repositories.kt`

**Interfaces:**
- Consumes: Task 1 的 `Models.kt`（GoUsage、ZenBilling、DeepSeekBalance、DeepSeekCost、Account）与 `Parsers.kt`
- Produces:
  - `object ApiClient { val client: OkHttpClient }`（15s 超时）
  - `class DeepSeekRepo { suspend fun balance(apiKey: String): Result<DeepSeekBalance>; suspend fun cost(platformToken: String): Result<DeepSeekCost> }`
  - `class OpenCodeRepo { suspend fun goUsage(account: Account): Result<GoUsage>; suspend fun zenBilling(account: Account): Result<ZenBilling> }`
  - 所有方法内部 try/catch 网络异常 → `Result.failure`，错误消息中文

- [ ] **Step 1: 实现 ApiClient.kt**

```kotlin
package com.xieguiawu.apicheckers.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object ApiClient {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
}
```

- [ ] **Step 2: 实现 Repositories.kt**

```kotlin
package com.xieguiawu.apicheckers.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate

class DeepSeekRepo {
    private suspend fun get(url: String, token: String, extraHeaders: Map<String, String> = emptyMap()): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url)
                    .header("Authorization", "Bearer $token")
                    .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                    .build()
                val resp = ApiClient.client.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 -> error("API Key 无效或已过期")
                    !resp.isSuccessful -> error("HTTP ${resp.code}: ${body.take(200)}")
                    else -> body
                }
            }
        }

    suspend fun balance(apiKey: String): Result<DeepSeekBalance> =
        get("https://api.deepseek.com/user/balance", apiKey).mapCatching { Parsers.parseDeepSeekBalance(it).getOrThrow() }

    suspend fun cost(platformToken: String): Result<DeepSeekCost> {
        val now = LocalDate.now()
        val months = listOf(now to now.monthValue, now.minusMonths(1) to now.minusMonths(1).monthValue)
        // 上月可能跨年：用 LocalDate.minusMonths(1) 的 year/month 自动处理
        val dayMap = mutableMapOf<String, Double>()
        var tokenInvalid = false
        for ((date, month) in months) {
            val url = "https://platform.deepseek.com/api/v0/usage/cost?month=$month&year=${date.year}"
            val r = get(url, platformToken, mapOf(
                "Accept" to "application/json",
                "x-app-version" to "1.0.0",
                "Referer" to "https://platform.deepseek.com/usage",
                "User-Agent" to ApiClient.UA,
            ))
            if (r.isFailure) {
                val msg = r.exceptionOrNull()!!.message.orEmpty()
                if (msg.contains("失效")) tokenInvalid = true
                continue
            }
            val cost = Parsers.parseDeepSeekCost(r.getOrThrow()).getOrElse { continue }
            for (d in cost.days) dayMap[d.date] = (dayMap[d.date] ?: 0.0) + d.total
        }
        if (tokenInvalid && dayMap.isEmpty()) return Result.failure(Exception("DeepSeek 平台登录已失效，请更新平台 Token"))
        // 计算 today/7d/30d
        var today = 0.0; var d7 = 0.0; var d30 = 0.0
        for (i in 0 until 30) {
            val d = now.minusDays(i.toLong()).toString()
            val v = dayMap[d] ?: 0.0
            if (i < 7) d7 += v
            d30 += v
            if (i == 0) today = v
        }
        val days = dayMap.entries.sortedByDescending { it.key }.take(7).map { DeepSeekCostDay(it.key, it.value) }
        return Result.success(DeepSeekCost(today, d7, d30, days))
    }
}

class OpenCodeRepo {
    private suspend fun get(url: String, headers: Map<String, String>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                val resp = ApiClient.client.newCall(req).execute()
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 -> error("Go API Key 无效或已过期")
                    !resp.isSuccessful -> error("HTTP ${resp.code}: ${body.take(200)}")
                    else -> body
                }
            }
        }

    suspend fun goUsage(account: Account): Result<GoUsage> =
        get("https://opencode.ai/zen/go/v1/usage", mapOf("Authorization" to "Bearer ${account.goApiKey}"))
            .mapCatching { Parsers.parseGoUsage(it) }

    suspend fun zenBilling(account: Account): Result<ZenBilling> {
        if (!account.hasZen) return Result.failure(Exception("未配置 Workspace/Cookie"))
        return get("https://opencode.ai/workspace/${account.workspaceId}/billing", mapOf(
            "Cookie" to "auth=${account.authCookie}",
            "User-Agent" to ApiClient.UA,
        )).mapCatching { Parsers.parseZenBilling(it).getOrThrow() }
    }
}
```

注意：cost() 返回日期的时区问题——DeepSeek 返回的 date 是 UTC/北京日期字符串，直接用字符串比较即可（服务器端已按天聚合）。

- [ ] **Step 3: 编译验证**

```bash
./gradlew :app:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "feat: 网络层（OkHttp）+ DeepSeek/OpenCode 仓库"
```

## Task 3: 安全设置存储

**Files:**
- Create: `app/src/main/java/com/xieguiawu/apicheckers/data/SecureSettings.kt`
- Create: `app/src/test/java/com/xieguiawu/apicheckers/CryptoTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `Account`
- Produces:
  - `object SecureSettings { fun init(context: Context); fun getDeepSeekKey(): String; fun setDeepSeekKey(v: String); fun getPlatformToken(): String; fun setPlatformToken(v: String); fun getAccounts(): List<Account>; fun saveAccount(a: Account); fun deleteAccount(id: String); fun lastUpdate(key: String): Long; fun setLastUpdate(key: String, t: Long) }`
  - 内部：`AesGcmCipher { fun encrypt(plain: String): String; fun decrypt(cipher: String): String }`（可单独测试）

- [ ] **Step 1: 实现 AesGcmCipher（纯 JVM 可测）**

Android Keystore 的 `AndroidKeyStore` 提供者只在 Android 可用——把加解密逻辑做成接口 + 两个实现：
- `AesGcmCipher`（接口：`encrypt(plain): String`、`decrypt(cipher): String`，输出 Base64 编码）
- `AndroidKeystoreCipher`（使用 `KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")`，`KeyProperties.PURPOSE_ENCRYPT or PURPOSE_DECRYPT`，`KeyProperties.BLOCK_MODE_GCM`，`KeyProperties.ENCRYPTION_PADDING_NONE`，alias `api_checkers_master`；IV 前置 + Base64）
- `CryptoTest.kt` 用模拟实现（如固定 key 的 AES/GCM，`javax.crypto`）验证「加密→解密 = 原文」「错误密文抛异常」——AndroidKeystoreCipher 的 Android 集成在真机验证（关卡 11 精神：构建后人工冒烟）

`CryptoTest.kt`:
```kotlin
class CryptoTest {
    private class FakeCipher : AesGcmCipher { /* 用 javax.crypto AES/GCM 固定 key 实现，逻辑与 Android 版一致 */ }
    @Test fun `加密解密往返`() { ... }
    @Test fun `篡改密文抛异常`() { ... }
}
```

- [ ] **Step 2: 实现 SecureSettings**

```kotlin
package com.xieguiawu.apicheckers.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json

object SecureSettings {
    private const val PREFS = "api_checkers_settings"
    private lateinit var prefs: SharedPreferences
    private var cipher: AesGcmCipher? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cipher = runCatching { AndroidKeystoreCipher() }.getOrNull()
        // 兜底：Keystore 异常时明文存储（个人工具 app，避免锁死），但 UI 层提示
    }

    private fun enc(v: String): String = cipher?.encrypt(v) ?: v
    private fun dec(v: String): String = cipher?.decrypt(v) ?: v

    fun getDeepSeekKey(): String = dec(prefs.getString("deepseek_key", "") ?: "")
    fun setDeepSeekKey(v: String) { prefs.edit().putString("deepseek_key", enc(v)).apply() }
    fun getPlatformToken(): String = dec(prefs.getString("platform_token", "") ?: "")
    fun setPlatformToken(v: String) { prefs.edit().putString("platform_token", enc(v)).apply() }
    fun getAccounts(): List<Account> {
        val raw = prefs.getString("accounts_json", "[]") ?: "[]"
        return runCatching { json.decodeFromString<List<Account>>(dec(raw)) }.getOrDefault(emptyList())
    }
    fun saveAccount(a: Account) {
        val list = getAccounts().toMutableList()
        val idx = list.indexOfFirst { it.id == a.id }
        if (idx >= 0) list[idx] = a else list.add(a)
        prefs.edit().putString("accounts_json", enc(json.encodeToString(list))).apply()
    }
    fun deleteAccount(id: String) {
        val list = getAccounts().filterNot { it.id == id }
        prefs.edit().putString("accounts_json", enc(json.encodeToString(list))).apply()
    }
    fun lastUpdate(key: String): Long = prefs.getLong("last_update_$key", 0L)
    fun setLastUpdate(key: String, t: Long) { prefs.edit().putLong("last_update_$key", t).apply() }
}
```
（Account 需要 `@Serializable` 注解——Task 1 的 Models.kt 中给 Account 加 `@Serializable`）

- [ ] **Step 3: 编译 + 测试**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL + 全绿

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "feat: Keystore AES-GCM 加密设置存储"
```

## Task 4: 主题与总览页（Home）

**Files:**
- Create: `app/src/main/java/com/xieguiawu/apicheckers/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/xieguiawu/apicheckers/ui/HomeScreen.kt`
- Create: `app/src/main/java/com/xieguiawu/apicheckers/AppViewModel.kt`
- Modify: `app/src/main/java/com/xieguiawu/apicheckers/MainActivity.kt`（接入 Navigation）
- Modify: `app/src/main/res/values/themes.xml`（强制深色）

**Interfaces:**
- Consumes: Task 1-3 的全部（Models、Repositories、SecureSettings）
- Produces:
  - `class AppViewModel(app: Application) : AndroidViewModel`：`val uiState: StateFlow<UiState>`、`fun refreshAll()`、`fun refreshDeepSeek()`、`fun refreshAccount(id: String)`
  - `data class UiState(deepSeek: DeepSeekUi, accounts: List<AccountUi>, refreshing: Boolean, lastUpdated: Long)`
  - `data class DeepSeekUi(keyConfigured: Boolean, balance: DeepSeekBalance?, cost: DeepSeekCost?, error: String?)`
  - `data class AccountUi(account: Account, goUsage: GoUsage?, zenBilling: ZenBilling?, error: String?)`
  - HomeScreen 通过回调 `onOpenAccount(id: String)`、`onOpenSettings()` 导航

- [ ] **Step 1: Theme.kt（深色纯色，全局约束 3 的色板）**

```kotlin
package com.xieguiawu.apicheckers.ui.theme

import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF0E1116)
val Card = Color(0xFF161B22)
val TextMain = Color(0xFFE6E8EB)
val TextSub = Color(0xFF8B949E)
val Accent = Color(0xFF58A6FF)
val Warn = Color(0xFFD29922)
val Danger = Color(0xFFF85149)
val Ok = Color(0xFF3FB950)
val Divider = Color(0xFF21262D)

private val DarkColors = darkColorScheme(
    primary = Accent, background = Bg, surface = Card,
    onBackground = TextMain, onSurface = TextMain,
    onSurfaceVariant = TextSub, outline = Divider,
)

@Composable
fun ApiCheckersTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
```

`themes.xml`（强制深色，防启动白闪）:
```xml
<style name="Theme.ApiCheckers" parent="android:Theme.Material.NoActionBar">
    <item name="android:windowBackground">#0E1116</item>
    <item name="android:forceDarkAllowed">false</item>
</style>
```

- [ ] **Step 2: AppViewModel.kt**

```kotlin
package com.xieguiawu.apicheckers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xieguiawu.apicheckers.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DeepSeekUi(val keyConfigured: Boolean = false, val balance: DeepSeekBalance? = null,
                      val cost: DeepSeekCost? = null, val error: String? = null)
data class AccountUi(val account: Account, val goUsage: GoUsage? = null,
                     val zenBilling: ZenBilling? = null, val error: String? = null, val loading: Boolean = false)
data class UiState(val deepSeek: DeepSeekUi = DeepSeekUi(), val accounts: List<AccountUi> = emptyList(),
                   val refreshing: Boolean = false, val lastUpdated: Long = 0L)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val deepSeekRepo = DeepSeekRepo()
    private val openCodeRepo = OpenCodeRepo()
    private val _ui = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _ui.asStateFlow()

    init { SecureSettings.init(app); loadFromCache(); refreshAll() }

    private fun loadFromCache() {
        val dk = SecureSettings.getDeepSeekKey()
        val accounts = SecureSettings.getAccounts().map { AccountUi(it) }
        _ui.value = _ui.value.copy(deepSeek = DeepSeekUi(keyConfigured = dk.isNotBlank()), accounts = accounts)
    }

    fun refreshAll() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(refreshing = true)
            refreshDeepSeek()
            _ui.value.accounts.map { it.account.id }.forEach { refreshAccount(it) }
            _ui.value = _ui.value.copy(refreshing = false, lastUpdated = System.currentTimeMillis())
            SecureSettings.setLastUpdate("all", System.currentTimeMillis())
        }
    }

    fun refreshDeepSeek() {
        viewModelScope.launch {
            val key = SecureSettings.getDeepSeekKey()
            if (key.isBlank()) {
                _ui.value = _ui.value.copy(deepSeek = DeepSeekUi(keyConfigured = false, error = "未配置 DeepSeek API Key"))
                return@launch
            }
            _ui.value = _ui.value.copy(deepSeek = _ui.value.deepSeek.copy(keyConfigured = true, error = null))
            val bal = deepSeekRepo.balance(key)
            val cost = if (SecureSettings.getPlatformToken().isNotBlank())
                deepSeekRepo.cost(SecureSettings.getPlatformToken()) else null
            _ui.value = _ui.value.copy(deepSeek = DeepSeekUi(
                keyConfigured = true,
                balance = bal.getOrNull(),
                cost = cost?.getOrNull(),
                error = bal.exceptionOrNull()?.message ?: cost?.exceptionOrNull()?.message,
            ))
        }
    }

    fun refreshAccount(id: String) {
        viewModelScope.launch {
            val acc = SecureSettings.getAccounts().firstOrNull { it.id == id } ?: return@launch
            _ui.value = _ui.value.copy(accounts = _ui.value.accounts.map {
                if (it.account.id == id) it.copy(loading = true, error = null) else it
            })
            val go = openCodeRepo.goUsage(acc)
            val zen = if (acc.hasZen) openCodeRepo.zenBilling(acc) else null
            _ui.value = _ui.value.copy(accounts = _ui.value.accounts.map {
                if (it.account.id == id) AccountUi(acc, go.getOrNull(), zen?.getOrNull(),
                    listOfNotNull(go.exceptionOrNull()?.message, zen?.exceptionOrNull()?.message).joinToString("\n").ifEmpty { null })
                else it
            })
        }
    }
}
```

- [ ] **Step 3: HomeScreen.kt（极简总览）**

结构（Column + LazyColumn）：
1. 顶部：标题「API Checkers」+ 右上角设置图标（Icons.Default.Settings）+ 刷新时间（小字「更新于 HH:mm」）
2. DeepSeek 卡片（Material3 Card）：
   - 标题「DeepSeek」+ 状态点（有余额=Accent 点；错误=Danger 点）
   - 余额大字（`¥120.00`，货币符号按 currency 映射 CNY→¥/USD→$/EUR→€）
   - 副行：充值 ¥120.00 · 赠送 ¥0.00
   - 今日消费（如有 platform token）：`今 ¥1.20 · 7日 ¥3.00 · 30日 ¥5.50`
   - 未配置 key → 「点击右上角设置添加 API Key」
3. OpenCode 账号列表：每个账号一张卡片：
   - 标题：账号名（如「账号 1」）+ Go plan 的 rolling 用量条（Accent 色 LinearProgressIndicator + 百分比）
   - 副行：`R 42% · W 17% · M 8%`（M 为 rate-limited 时 Danger 色）
   - 有 Zen 数据时副行追加 `· Zen $20.00`
   - 点击卡片 → onOpenAccount(id)
4. 底部「添加账号」按钮（onOpenSettings）
5. PullToRefresh 或顶部刷新按钮（简单起见：下拉刷新用 Modifier.pullRefresh 依赖 accompanist——为避免额外依赖，用「点击刷新按钮」+ 自动刷新：首次进入 + 每 5 分钟轮询（LaunchedEffect + delay loop））

进度条组件（复用）:
```kotlin
@Composable
fun UsageBar(percent: Int, color: Color, label: String? = null) {
    // Row: label(可选) + LinearProgressIndicator(progress = percent/100f, color=color, trackColor=Divider)
}
```
颜色规则：percent < 70 → Accent；70-89 → Warn；≥90 → Danger（Go 窗口 rate-limited 强制 Danger）

- [ ] **Step 4: MainActivity 接入 Navigation**

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureSettings.init(applicationContext)
        setContent {
            ApiCheckersTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") { HomeScreen(onOpenAccount = { nav.navigate("account/$it") }, onOpenSettings = { nav.navigate("settings") }) }
                    composable("account/{id}") { backStackEntry ->
                        DetailScreen(id = backStackEntry.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() })
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}
```

- [ ] **Step 5: 编译验证**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL（DetailScreen/SettingsScreen 先给占位实现，Task 5/6 填充）

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat: 深色主题 + 总览页 + ViewModel"
```

## Task 5: 账号详情页（DetailScreen）

**Files:**
- Create: `app/src/main/java/com/xieguiawu/apicheckers/ui/DetailScreen.kt`

**Interfaces:**
- Consumes: `AppViewModel.refreshAccount(id)`、`UiState.accounts`
- Produces: 无（叶子 UI）

- [ ] **Step 1: 实现 DetailScreen.kt**

结构（LazyColumn + 卡片分区）：
1. 顶部：返回箭头 + 账号名 + 手动刷新按钮（调用 refreshAccount(id)）
2. **Go Plan 卡片**（标题「Go Plan · 订阅」）：
   - 三个窗口行，每行：窗口标签（Rolling 5h / Weekly 7d / Monthly 30d）+ UsageBar + 百分比 + 重置倒计时
   - 重置倒计时格式：`4小时20分后重置`（用 resetsAt 减当前时间；<1h 显示 `52分钟后重置`；已过期显示 `即将重置`）
   - status = rate-limited → 行尾红色「已限流」
3. **Zen Plan 卡片**（标题「Zen Plan · 按量」）：
   - 余额大字 `$19.99`（USD 固定符号 $）
   - 本月：`$0.00 / $50.00` + UsageBar（monthlyUsage/monthlyLimit，限额 0 时不显示条）
   - 自动充值行：`自动充值 关`（autoReload=false）或 `自动充值 开 · 低于 $5 充 $20`
   - 未配置 workspace/cookie → 灰色提示「未配置 Workspace ID / Cookie，去设置添加以查看 Zen」
4. 错误提示卡（error 非空时显示 Danger 色错误文案，error 为 null 时整卡隐藏）
5. 本页只服务 OpenCode 账号（DeepSeek 详情已并入总览页）

- [ ] **Step 2: 编译验证 + 提交**

```bash
./gradlew :app:assembleDebug && git add -A && git commit -m "feat: 账号详情页（Go 三窗口 + Zen 账单）"
```

## Task 6: 设置页（SettingsScreen）

**Files:**
- Create: `app/src/main/java/com/xieguiawu/apicheckers/ui/SettingsScreen.kt`

**Interfaces:**
- Consumes: `SecureSettings`（get/set 全接口）、`AppViewModel.loadFromCache()` 或 refresh 触发
- Produces: 无

- [ ] **Step 1: 实现 SettingsScreen.kt**

结构（LazyColumn）：
1. 顶部：返回箭头 + 「设置」
2. **DeepSeek 分区**：
   - OutlinedTextField「DeepSeek API Key」（PasswordVisualTransformation + 显隐切换）
   - OutlinedTextField「DeepSeek 平台 Token（可选）」（Password，说明文字：登录 platform.deepseek.com 后 DevTools → Network → 任意 api/v0 请求的 Authorization 头；用于查看消费明细，几天到几周过期）
   - 保存按钮（保存后调 viewModel.refreshDeepSeek()）
3. **OpenCode 账号分区**：
   - 账号列表：每项显示名称 + key 尾号（`sk-...4f3a`）+ 删除按钮
   - 「添加账号」按钮 → 展开编辑表单（或进入编辑模式）：
     - 名称（默认「账号 N」）
     - Go API Key（Password，必填）
     - Workspace ID（可选，`wrk_...`）
     - Auth Cookie（可选，Password；说明：浏览器 DevTools → Application → Cookies → opencode.ai → auth，值以 Fe26.2 开头）
   - 保存/取消
4. 帮助文本：说明每个字段从哪获取（小字 TextSub）

- [ ] **Step 2: 编译验证 + 提交**

```bash
./gradlew :app:assembleDebug && git add -A && git commit -m "feat: 设置页（DeepSeek 密钥 + OpenCode 账号管理）"
```

## Task 7: 构建、验证与文档

**Files:**
- Create: `README.md`、`README_zh.md`、`CONTEXT_FOR_NEXT_AGENT.md`
- Modify: 无

- [ ] **Step 1: 全量构建 + 测试**

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
Expected: BUILD SUCCESSFUL，所有测试绿，`app/build/outputs/apk/debug/app-debug.apk` 存在

- [ ] **Step 2: 静态安全检查**

```bash
grep -rniE 'sk-[a-zA-Z0-9]{16,}|Fe26\.2\*|Bearer [a-zA-Z0-9]{20,}' app/src README.md 2>/dev/null || echo "无密钥泄漏"
git diff --cached | grep -iE 'sk-|Fe26|nvapi' || echo "暂存区无密钥"
```

- [ ] **Step 3: 写 README.md + README_zh.md**

英文 README.md + 中文 README_zh.md，结构：功能特性（DeepSeek 余额/消费、OpenCode Go 三窗口、OpenCode Zen 账单、三账号区分）、截图占位、构建方法（./gradlew assembleDebug）、设置说明（四类凭据获取方式）、数据来源 API 表、免责声明（凭据仅存本机、Zen 数据来自页面解析可能随网页改版失效）。
两个文件顶部互相链接（`[**中文版**](README_zh.md)` / `[**English**](README.md)`）。

- [ ] **Step 4: 写 CONTEXT_FOR_NEXT_AGENT.md**（阶段 B 文档：项目状态、已完成工作、遗留问题——cookie 过期需手动更新、DeepSeek key 需用户提供、真机冒烟测试待做）

- [ ] **Step 5: 最终提交**

```bash
git add -A && git commit -m "docs: README 双语 + CONTEXT 文档"
```

## Acceptance（验收标准）

1. `./gradlew :app:testDebugUnitTest` 全绿（≥10 个测试，覆盖三个解析器 + 加密往返）
2. `./gradlew :app:assembleDebug` 产出 `app-debug.apk`，安装到华为手机（arm64）可运行
3. 总览页展示：DeepSeek 余额（¥，含充值/赠送拆分）+ OpenCode 各账号 Go 三窗口用量条 + Zen 余额（配置后）
4. 三个 OpenCode 账号在总览页分别成卡、详情页独立展示，互不混淆
5. 深色纯色（仅 Global Constraints 3 的色板），无彩色渐变
6. 设置页可增删账号、改 DeepSeek key、存 cookie；重启 app 数据不丢
7. 凭据不落盘明文（Keystore 加密；Keystore 失败时明文兜底但 UI 提示）
8. README.md + README_zh.md 双语存在且互相链接
