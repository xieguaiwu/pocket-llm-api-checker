# CONTEXT_FOR_NEXT_AGENT.md

## F-Droid 发布准备（2026-08-24）
- **状态**：Phase 1 完成——fastlane 元数据（en-US + zh-CN：short/full description、icon.png 从矢量精确渲染、2 张占位截图[真机截图待替换]、changelogs/1.txt）+ `scripts/verify-reproducible.sh`（双构建哈希对比）+ fdroiddata 草稿 `docs/fdroid/com.xieguiawu.apicheckers.yml`（含 NonFreeNet 声明，提交位置 metadata/com.xieguiawu.apicheckers.yml）
- **合规结论**：MIT / 纯 FOSS 依赖 / 单 INTERNET 权限 / 无广告统计 → 硬性要求全满足；缺 git tag 已补（v1.0.0）
- **待办**：①真机侧载 `app-release-unsigned.apk` 后截图替换 phoneScreenshots 占位图 ②用户 GitLab fork fdroiddata 提 MR ③发版纪律：bump versionCode/versionName → tag vX.Y.Z → 更新 changelogs/<versionCode>.txt（Tags 模式自动发现）
- **可复现性**：纯 Kotlin、isMinifyEnabled=false（无 R8）→ 预期可复现；`verify-reproducible.sh` 验证通过后可选走自有签名 + Binaries/AllowedAPKSigningKeys 拿 Verified 徽章（签名决策窗口在首次发布前，不可中途更换）
- 完整调查：`~/Desktop/go-projects/LLM-api-check/docs/plans/2026-08-24-fdroid-publishing-plan.md`

## 项目当前状态
API Checkers — 极简深色 Android app，查看 DeepSeek API、OpenCode（Zen + Go 两个 plan）与 **Qwen Token Plan（套餐模型 + 5小时/7天 配额窗口）** 用量，OpenCode 支持 3 账号区分。**v1.1.0（versionCode 2）开发完成：56 单测全绿（含 32 个 Qwen 新用例），公开 repo：https://github.com/xieguaiwu/pocket-llm-api-checker，待真机冒烟测试。**

## 最后一次完成的工作（2026-08-29）
- **provider=qwen（commit 3eb9d17 + 收尾）**：与 Go 姊妹项目 `~/Desktop/go-projects/LLM-api-check` 逐条对等
  - 数据层：Models.kt（QwenAccount/QwenWindow/QwenUsage/QwenPlan + 区域归一化）、Parsers.kt（模型清单解析、信封 BFS + 内嵌 JSON 展开、qwenPercent 双域、SEC_TOKEN 提取）、Repositories.kt（QwenRepo：plan/usage + sec_token 三级降级 + 空信封重试 3 次 + 认证错误不重试 + 表单 RPC）、SecureSettings（qwen_accounts_json 加密存储）、ApiClient 新增桌面 BROWSER_UA
  - UI：HomeScreen QwenCard、DetailScreen QwenDetailScreen（窗口行**倒计时恒显 + 已限流徽章并存**，满足 index.md §六）、SettingsScreen Qwen 账号管理（名称/API Key/可选 Cookie/区域下拉，支持粘贴 `Cookie:` 前缀）
  - 测试：QwenAccountTest + QwenParserTest + QwenRepoTest 共 32 用例，fixtures 6 个（`app/src/test/resources/fixtures/qwen_*.json`）；`./gradlew :app:testDebugUnitTest --rerun-tasks` 56 全绿
  - 版本：versionCode 2 / versionName 1.1.0；fastlane changelogs/2.txt（en+zh）与 full_description 更新
  - 契约与实测矩阵同 Go 侧：`docs/plans/2026-08-29-qwen-provider.md`（在 Go 仓库）

## 历史工作（2026-08-24 F-Droid Phase 1）
- fastlane 元数据 + `scripts/verify-reproducible.sh`（双构建哈希对比）+ fdroiddata 草稿 `docs/fdroid/com.xieguiawu.apicheckers.yml`；git tag v1.0.0 已补
- 合规结论：MIT / 纯 FOSS / 单 INTERNET 权限 → 硬性要求全满足；发版纪律：bump versionCode/versionName → tag vX.Y.Z → changelogs/<versionCode>.txt
- 完整调查：`~/Desktop/go-projects/LLM-api-check/docs/plans/2026-08-24-fdroid-publishing-plan.md`

## 遗留问题 / 待办
- [ ] **Qwen 配额窗口未端到端实跑**：契约经未登录请求实测（回 NotLogined），sec_token 抓取与真 Cookie 组合待真机验证；**用户需在设置页给 Qwen 账号填控制台 Cookie**（从 bailian.console.aliyun.com 的 Token Plan 页 DevTools 拷贝）
- [ ] **国际区域（ap-southeast-1）未实跑**：代码按公开契约实现，启用前先验 `/tool/user/info.json` 的 sec_token 字段名
- [ ] **真机冒烟测试**：APK 装到华为手机验证（`app/build/outputs/apk/debug/app-debug.apk`）；重点验证 Keystore 加密、三类账号卡片、Zen billing 解析（真实 cookie）
- [ ] **用户配置**：DeepSeek API key 本机已失效（需用户提供新 key）；opencode 三账号 key/workspace/cookie 需设置页添加；F-Droid Phase 2 待真机截图替换占位图 + GitLab fork fdroiddata 提 MR
- [ ] 剩余 P2（不阻塞）：release minify（P2-5/16）、重试拦截器（P2-18）、workspaceId URL 编码（P2-8）等
- [ ] Zen billing 解析依赖网页结构，若 opencode 改版需更新 Parsers.parseZenBilling；Qwen RPC 信封变化时改 `qwenFindObject` 目标键

## 技术要点（下一位 Agent 必读）
- **数据源**：Go usage = `GET https://opencode.ai/zen/go/v1/usage`（API key）；Zen billing = `GET https://opencode.ai/workspace/{id}/billing`（cookie，解析 SolidJS SSR，锚点 `customerID:"cus_`，balance 单位为 1e-8 USD）；**Qwen 模型 = `https://token-plan.<region>.maas.aliyuncs.com/compatible-mode/v1/models`（API key，密钥与区域绑定）；Qwen 配额 = `POST https://bailian-cs.console.aliyun.com/data/api.json`（控制台 Cookie + sec_token，信封 `data.errorCode` 判错，负载 BFS 查找）**
- **DeepSeek**：余额 = `api.deepseek.com/user/balance`（API key）；消费 = `platform.deepseek.com/api/v0/usage/cost?month=&year=`（浏览器 token，code 40003 = 失效，拉本月+上月聚合 30 天）
- **Qwen 三个坑**（详见 Go 仓库 docs/plans/2026-08-29-qwen-provider.md）：①cornerstoneParam 绝不硬编码 switchAgent（→ NotAuthorised）②抓 SEC_TOKEN 必须带 Sec-Fetch-* 导航头 + 桌面 UA（ApiClient.BROWSER_UA）③登录失效仍 HTTP 200，错误在信封里
- **安全**：SecureSettings（Keystore AES-GCM + SharedPreferences），加密失败降级明文 + securityWarning 提示；allowBackup=false；Qwen 控制台 Cookie 含阿里云登录会话，按敏感凭据对待
- **构建**：JDK 21 + Gradle 8.9 wrapper + AGP 8.5.2 + Kotlin 2.0.21（compose compiler 插件） + compileSdk 35；测试 `./gradlew :app:testDebugUnitTest`（56 个）；可复现验证 `scripts/verify-reproducible.sh`（需干净树 + ANDROID_HOME）
- **UI**：9 色纯色板（见 Theme.kt），无 Material 默认色泄漏；单 ViewModel（Activity 级共享）；Qwen 详情窗口行 = CountdownText 恒显 + 已限流徽章并存（§六）

## 知识图谱
- graphify-out/: 存在（2026-08-15 构建：199 nodes / 305 edges / 18 communities；God Nodes: AppViewModel 15、SecureSettings 15、HomeScreen 8、shouldAutoRefreshWhileHeld 8；无 import cycle）
- 图谱不入库（.gitignore）；**v1.1.0 代码变更后已重建**（见上）

## 最后更新时间
2026-08-29 03:10
