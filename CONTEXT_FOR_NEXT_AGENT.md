# CONTEXT_FOR_NEXT_AGENT.md

## F-Droid 发布准备（2026-09-06 复核修正）
- 🔴 **原 yml 的 Builds 块不可构建**：既无 `subdir: app` 也无 `gradle: yes`。
  fdroidserver `metadata.py:307 build_method()` 在无 maven/gradle/output 字段时**回落 ant**
  ⇒ GitLab CI 必挂。已补 `subdir: app` + `gradle: - yes`。
- yml 已同步到 v1.1.0（versionCode 2），并补 AuthorEmail
- 新增 `scripts/validate-fdroid-metadata.sh`（校验 commit: 是真 tag / changelog 齐 / CurrentVersion 一致）
- 新增 `.github/workflows/ci.yml`（此前**完全无 CI**）。首跑即红：`lintDebug` 报
  `NewApi` error——`android:forceDarkAllowed` 放在 `values/themes.xml` 而 minSdk 26。
  已把整份 style 移到 `values-v29/themes.xml`（行为不变，26-28 本来就会忽略该属性）。
  现 CI 绿（run fd2d8bf3）。
- **智星云 galaxy provider 仍未 bump 版本**（HEAD 上 versionCode 还是 2）⇒ 不进 Builds。
  真机冒烟通过后：bump 到 versionCode 3 / versionName 1.2.0 → tag v1.2.0 →
  补 `changelogs/3.txt`（en+zh）→ yml 追加第三个 Build 块
- ⚠️ 本地 main 曾落后 origin 1 commit（Galaxy provider，+2883 行）——已 ff 拉回

## 最后一次完成的工作（2026-09-06）
- **provider=bai（白B.AI，对齐 Go v1.3.0 bai 能力集）**：`BaiAccount`（chat.b.ai sk- key，
  同一把 key 推理面+控制台通用）→ SecureSettings `bai_accounts_json` 加密存取 →
  设置页「白B.AI 账号」分区（添加/重命名/删除，API Key 密码遮罩）→ 首页 `BaiCard`
  （积分余额 + ≈$ 换算 + 本月消耗 + 过期提醒）→ `BaiDetailScreen` 四卡
  （积分/用量分析/免费通道四态/模型清单）→ `AppViewModel.refreshBaiNow`
  （模型+积分两路并行，探活与 stats best-effort）
  - 数据层：Models 增 BaiAccount/BaiPoints/BaiModel/BaiPlan/BaiProbe/BaiRecord/
    BaiUsageStats + aggregateBaiUsage 纯函数（口径与 Go 逐条一致：requests 降序、
    同数字典序、complete 由 repo 层决定）；Parsers 增 baiEnvelope（tRPC v11 信封，
    UNAUTHORIZED 归一 BaiAuthError）、parseBaiModels（one-api 信封去重排序）、
    parseBaiPoints（points_balance 缺失显式失败不显示 0）、parseBaiMonthlySpent、
    parseBaiRecords（data 缺席显式失败/空数组合法）+ sanitizeServerText（与 Go
    SanitizeText 同语义）；Repositories 增 BaiRepo（models/points/probeFreeFlash/stats，
    串行 10 页×100 封顶、has_more 自然终止、截断 complete=false）
  - 探活：refreshBaiNow 清单成功后探盯梢清单内存在模型（§12 自查：实现有调用点）；
    免费通道卡四态 = ✓存活/✓未探/⚠运行时故障+摘要/✗缺失
  - 测试：BaiRepoTest 12 个（MiniServer 单线程服务器双端口分流两域），
    全套 122 个 0 失败；lintDebug 绿（顺手修了 themes.xml forceDarkAllowed 基线 error，
    tools:targetApi="29"）；assembleDebug 出包 17.1MB
  - ⚠️ 真机冒烟未做（本机无 adb 设备）——用户手机录入 sk- key 后验证 bai 卡与详情页

## F-Droid 发布准备（2026-08-24，历史记录，部分内容已被上面修正）
- **状态**：Phase 1 完成——fastlane 元数据（en-US + zh-CN：short/full description、icon.png 从矢量精确渲染、2 张占位截图[真机截图待替换]、changelogs/1.txt）+ `scripts/verify-reproducible.sh`（双构建哈希对比）+ fdroiddata 草稿 `docs/fdroid/com.xieguiawu.apicheckers.yml`（含 NonFreeNet 声明，提交位置 metadata/com.xieguiawu.apicheckers.yml）
- **合规结论**：MIT / 纯 FOSS 依赖 / 单 INTERNET 权限 / 无广告统计 → 硬性要求全满足；缺 git tag 已补（v1.0.0）
- **待办**：①真机侧载 `app-release-unsigned.apk` 后截图替换 phoneScreenshots 占位图 ②用户 GitLab fork fdroiddata 提 MR ③发版纪律：bump versionCode/versionName → tag vX.Y.Z → 更新 changelogs/<versionCode>.txt（Tags 模式自动发现）
- **可复现性**：纯 Kotlin、isMinifyEnabled=false（无 R8）→ 预期可复现；`verify-reproducible.sh` 验证通过后可选走自有签名 + Binaries/AllowedAPKSigningKeys 拿 Verified 徽章（签名决策窗口在首次发布前，不可中途更换）
- 完整调查：`~/Desktop/go-projects/LLM-api-check/docs/plans/2026-08-24-fdroid-publishing-plan.md`

## 项目当前状态
API Checkers — 极简深色 Android app，查看 DeepSeek API、OpenCode（Zen + Go 两个 plan）、Qwen Token Plan（套餐模型 + 5小时/7天 配额窗口）与**智星云 AI Galaxy（GPU 算力云：余额 + 云主机实例状态）**用量，OpenCode/智星云支持多账号区分。**v1.1.0（versionCode 2）**：103 单测全绿（含 47 个智星云新用例），公开 repo：https://github.com/xieguaiwu/pocket-llm-api-checker。

## 最后一次完成的工作（2026-08-29 晚）
- **provider=galaxy 智星云 AI Galaxy**：与 Go 姊妹项目 `~/Desktop/go-projects/LLM-api-check` 逐条对等（契约唯一权威源：Go 仓库 docs/plans/2026-08-29-ai-galaxy-provider.md，2026-08-29 真实凭据实测通过）
  - 数据层：Models.kt（GalaxyAccount/GalaxyBalance/GalaxyStatusCount/GalaxyInstance/GalaxyCost/GalaxyCostEntry，JSON 序列化名与 Go json tag 逐条对齐）、Parsers.kt（galaxySign MD5 签名、状态码文案、ServerTime 到期折算、手机号脱敏、四类响应解析 + 今日/近7天双窗口聚合与 today_partial/week_partial）、Repositories.kt（GalaxyRepo：OpenAPI v2 签名 POST + 信封判错 + page_size 夹 100 + 翻页上限）、SecureSettings（galaxy_accounts_json 加密存储）、AppViewModel（GalaxyUi + 四类并行 refreshGalaxyNow + refreshAll 纳入）
  - UI：HomeScreen GalaxyCard（余额大字阈值变色 + 运行中/磁盘保留/启动错误 + 最近到期倒计时）、DetailScreen GalaxyDetailScreen（余额三列不互相折算 + VIP/脱敏手机 + 今日/近7天消耗 ≥ 下限标记 + 实例统计 + 时价/约可支撑 + 实例卡**到期倒计时恒显与状态徽章并存**）、SettingsScreen 智星云账号管理（AccessKey/SecretKey 两输入框，SecretKey 密码遮罩，首尾空白清理，删除/改名沿用既有交互）、MainActivity 新增 galaxy/{id} 路由
  - 🔴 安全红线落实：实例响应明文口令（Init_passwd/LastInitPasswd/RdpPasswd/VncPasswd）用显式白名单 DTO + ignoreUnknownKeys 丢弃，fixture 用 SECRET_PWD_* 哨兵 + 断言解析/序列化/toString 均不含（GalaxyParserTest.白名单解码弃口令）；不调 account/get_apikey_info；统计不展示 statusDefault（契约 §2.4 实测与列表不一致）
  - 测试：GalaxyParserTest(19，含乱序签名向量) + GalaxyRepoTest(15，MiniServer 服务端复算签名 + 独立 MD5 金标准) + GalaxyAccountTest(8，含 toString 脱敏) + GalaxyExpiryTest(4) + 字符串布尔不当真值(1) 共 **47 用例**，fixtures 4 个（`app/src/test/resources/fixtures/galaxy_*.json`，形状取契约 §2.6 实测快照，凭据与口令用假值）
  - 质量门：`./gradlew :app:testDebugUnitTest --rerun-tasks` **103 全绿**（基线 56 → 103）；`assembleDebug` 成功；`lintDebug` 无新增 error（唯一 error 为基线既有的 themes.xml forceDarkAllowed，未动）
  - 版本：versionCode/versionName 未 bump、fastlane 未动
- **审查修复（oracle/momus 双审后补）**：GalaxyAccount.toString 对 secretKey 打码；galaxyRawBool 只认 JSON 布尔（has_more:"false" 字符串不再误判翻页，与 Go 对齐）；信封 success 同口径收紧；错误消息不再携带响应体片段（防敏感 data 进 UI）；实例列表 LazyColumn 稳定 key；详情页补「无活跃实例」空态；GalaxyCard 未配置分支也显示错误、磁盘保留 0 不占位；签名乱序输入向量 + 字符串布尔回归测试

## 历史工作（2026-08-29 上午）
- **provider=qwen（commit 3eb9d17 + 收尾）**：与 Go 姊妹项目逐条对等（数据层 + UI + 32 单测 + 6 fixtures，56 全绿）；versionCode 2 / versionName 1.1.0；fastlane changelogs/2.txt 更新

## 历史工作（2026-08-24 F-Droid Phase 1）
- fastlane 元数据 + `scripts/verify-reproducible.sh`（双构建哈希对比）+ fdroiddata 草稿 `docs/fdroid/com.xieguiawu.apicheckers.yml`；git tag v1.0.0 已补
- 合规结论：MIT / 纯 FOSS / 单 INTERNET 权限 → 硬性要求全满足；发版纪律：bump versionCode/versionName → tag vX.Y.Z → changelogs/<versionCode>.txt
- 完整调查：`~/Desktop/go-projects/LLM-api-check/docs/plans/2026-08-24-fdroid-publishing-plan.md`

## 遗留问题 / 待办
- [ ] **智星云未端到端实跑（Android 侧）**：Go 侧已用真实 AK/SK 真机联调通过，Android 侧代码路径与 Go 逐条对齐但未真机冒烟；**用户需在设置页录入真实 AccessKey/SecretKey**（控制台「开放API → AccessKey管理」，需先实名认证）并验证四卡渲染与倒计时
- [ ] **Qwen 配额窗口未端到端实跑**：契约经未登录请求实测（回 NotLogined），sec_token 抓取与真 Cookie 组合待真机验证；**用户需在设置页给 Qwen 账号填控制台 Cookie**（从 bailian.console.aliyun.com 的 Token Plan 页 DevTools 拷贝）
- [ ] **国际区域（ap-southeast-1）未实跑**：代码按公开契约实现，启用前先验 `/tool/user/info.json` 的 sec_token 字段名
- [ ] **真机冒烟测试**：APK 装到华为手机验证（`app/build/outputs/apk/debug/app-debug.apk`）；重点验证 Keystore 加密、四类账号卡片、Zen billing 解析（真实 cookie）、智星云实例卡倒计时
- [ ] **用户配置**：DeepSeek API key 本机已失效（需用户提供新 key）；opencode 三账号 key/workspace/cookie 需设置页添加；F-Droid Phase 2 待真机截图替换占位图 + GitLab fork fdroiddata 提 MR
- [ ] 剩余 P2（不阻塞）：release minify（P2-5/16）、重试拦截器（P2-18）、workspaceId URL 编码（P2-8）等
- [ ] Zen billing 解析依赖网页结构，若 opencode 改版需更新 Parsers.parseZenBilling；Qwen RPC 信封变化时改 `qwenFindObject` 目标键
- [ ] 本机 lint 基线 error（themes.xml `android:forceDarkAllowed` NewApi）为历史遗留，发布前顺手修（`values-v29` 分拆或移除）→ 已由 2026-09-06 CI 修复（values-v29 分拆）

## 技术要点（下一位 Agent 必读）
- **数据源**：Go usage = `GET https://opencode.ai/zen/go/v1/usage`（API key）；Zen billing = `GET https://opencode.ai/workspace/{id}/billing`（cookie，解析 SolidJS SSR，锚点 `customerID:"cus_`，balance 单位为 1e-8 USD）；**Qwen 模型 = `https://token-plan.<region>.maas.aliyuncs.com/compatible-mode/v1/models`（API key，密钥与区域绑定）；Qwen 配额 = `POST https://bailian-cs.console.aliyun.com/data/api.json`（控制台 Cookie + sec_token，信封 `data.errorCode` 判错，负载 BFS 查找）**
- **智星云 = `POST https://app.ai-galaxy.cn/openapi/v2`**：统一表单 POST，公共参数 apikey/timestamp/nonce + `sign = md5(字典序 k=v 串 &secret=SK)`（小写 hex，空值/ sign/secret 不参与）；**HTTP 恒 200、错误在信封 {success,code:"4000",message}**；page_size ≤100 自行夹住；四个端点：account/get_main_account_info、instance/get_instance_status_count、instance/get_instance_list、billing/get_balance_change_list
- **白B.AI = 两域两网关**：模型/探活 `api.b.ai/v1/models|/v1/chat/completions`（one-api 系，Bearer sk-；探活 max_tokens=8）；积分/明细 `chat.b.ai/trpc/lambda/usage.points|usage.summary|usage.records`（tRPC v11 信封，input 走 URL query，records 翻页 has_more/page/pageSize）。1 积分 = 1e-6 USD；过期告警阈值 100 万积分（口径 2026-09-06 用户定）。契约 Go 仓 docs/plans/2026-09-04-bai-provider.md
- **DeepSeek**：余额 = `api.deepseek.com/user/balance`（API key）；消费 = `platform.deepseek.com/api/v0/usage/cost?month=&year=`（浏览器 token，code 40003 = 失效，拉本月+上月聚合 30 天）
- **Qwen 三个坑**（详见 Go 仓库 docs/plans/2026-08-29-qwen-provider.md）：①cornerstoneParam 绝不硬编码 switchAgent（→ NotAuthorised）②抓 SEC_TOKEN 必须带 Sec-Fetch-* 导航头 + 桌面 UA（ApiClient.BROWSER_UA）③登录失效仍 HTTP 200，错误在信封里
- **智星云四个坑**（详见 Go 仓库 docs/plans/2026-08-29-ai-galaxy-provider.md）：①实例列表响应含 Init_passwd/LastInitPasswd/RdpPasswd/VncPasswd 明文口令——白名单 DTO 解码（ignoreUnknownKeys），任何数据类/序列化/日志不得透传 ②不调 account/get_apikey_info（回吐 SecretKey）③统计端点 statusDefault 与列表条数实测不一致，统计行只展示 statusAll/statusRunning/statusKeeppedDisk/statusCreateError/statusRunningError ④到期倒计时恒显：ServerTime 折算 dueAt（remaining = Due_time − ServerTime），异常徽章与倒计时并存
- **安全**：SecureSettings（Keystore AES-GCM + SharedPreferences），加密失败降级明文 + securityWarning 提示；allowBackup=false；Qwen 控制台 Cookie 与智星云 AK/SK 均按敏感凭据对待
- **构建**：JDK 21 + Gradle 8.9 wrapper + AGP 8.5.2 + Kotlin 2.0.21（compose compiler 插件） + compileSdk 35；测试 `./gradlew :app:testDebugUnitTest`（100 个）；可复现验证 `scripts/verify-reproducible.sh`（需干净树 + ANDROID_HOME）
- **UI**：9 色纯色板（见 Theme.kt），无 Material 默认色泄漏；单 ViewModel（Activity 级共享）；Qwen 详情窗口行与智星云实例卡 = 倒计时恒显 + 异常徽章并存（§六）

## 知识图谱
- graphify-out/: 存在（**2026-08-29 晚间 galaxy provider 后重建**；图谱不入库（.gitignore））

## 最后更新时间
2026-09-06 14:4x