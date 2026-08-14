# CONTEXT_FOR_NEXT_AGENT.md

## 项目当前状态
API Checkers — 极简深色 Android app，查看 DeepSeek API 与 OpenCode（Zen + Go 两个 plan）用量，OpenCode 支持 3 账号区分。**开发完成，待真机冒烟测试。**

## 最后一次完成的工作（2026-08-14）
- 全功能实现：数据层（解析器/仓库/加密存储）+ UI 层（总览/详情/设置）+ 18 个单元测试全绿 + `app-debug.apk` 构建成功（16.7MB）
- momus 审查 2 轮：第 1 轮 1 P0 + 10 P1 + 20 P2 → 全部修复；第 2 轮复验「可发布」（securityWarning UI 已闭环）
- 文档：README.md + README_zh.md 双语、CONTEXT 本文档

## 遗留问题 / 待办
- [ ] **真机冒烟测试**：APK 需装到华为手机验证（用户可自行安装 `app/build/outputs/apk/debug/app-debug.apk`）；重点验证 Keystore 加密、三账号卡片、Zen billing 解析（真实 cookie）
- [ ] **用户配置**：DeepSeek API key 本机已失效（用户需提供新 key 在 app 设置页输入）；opencode-go 三账号的 key/workspace/cookie 需用户在设置页添加
- [ ] 剩余 P2（不阻塞）：release minify（P2-5/16）、重试拦截器（P2-18）、workspaceId URL 编码（P2-8）等
- [ ] Zen billing 解析依赖网页结构，若 opencode 改版需更新 Parsers.parseZenBilling

## 技术要点（下一位 Agent 必读）
- **数据源**：Go usage = `GET https://opencode.ai/zen/go/v1/usage`（API key）；Zen billing = `GET https://opencode.ai/workspace/{id}/billing`（cookie，解析 SolidJS SSR，锚点 `customerID:"cus_`，balance 单位为 1e-8 USD）
- **DeepSeek**：余额 = `api.deepseek.com/user/balance`（API key）；消费 = `platform.deepseek.com/api/v0/usage/cost?month=&year=`（浏览器 token，code 40003 = 失效，拉本月+上月聚合 30 天）
- **安全**：SecureSettings（Keystore AES-GCM + SharedPreferences），加密失败降级明文 + securityWarning 提示；allowBackup=false
- **构建**：JDK 21 + Gradle 8.9 wrapper + AGP 8.5.2 + Kotlin 2.0.21（compose compiler 插件） + compileSdk 35；测试 `./gradlew :app:testDebugUnitTest`（18 个）
- **UI**：9 色纯色板（见 Theme.kt），无 Material 默认色泄漏；单 ViewModel（Activity 级共享）

## 知识图谱
- graphify-out/: 不存在（项目未建；如需可 `graphify update . --no-llm`）

## 最后更新时间
2026-08-14 22:40
