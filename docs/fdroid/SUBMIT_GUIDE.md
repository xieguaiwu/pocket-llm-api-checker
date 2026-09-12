# F-Droid 收录提交指引（API Checkers）

本目录包含提交流程所需的一切。你只需要一个 GitLab 账号，约 2 分钟完成。

## 前置条件（当前状态，2026-09-12）

| 条件 | 状态 |
|---|---|
| LICENSE（MIT） | ✅ 仓库根 |
| 依赖全 FOSS（纯 Kotlin/Compose，仅 google() + mavenCentral()） | ✅ |
| 无专有二进制入库 | ✅ |
| Gradle wrapper 已提交 | ✅ |
| git tag `v1.1.0` | ✅ 已打并推送 |
| fastlane 元数据（en-US + zh-CN） | ✅ 文案完整；截图是占位图，待真机替换 |
| 可复现构建验证（unsigned 双构建） | ✅ `b34c8279…6757`（tag v1.1.0，2026-09-12 实测） |
| 真机冒烟（录入真实凭据） | ❌ 未做（不阻塞提交；v1.2.0 发版前需要） |
| GitLab 账号 | ⏳ 注册中 |

## 已就绪的文件

| 文件 | 用途 |
|---|---|
| `com.xieguiawu.apicheckers.yml` | fdroiddata metadata（类别 `System`；Builds 列 v1.0.0 + v1.1.0）|
| `fdroiddata-mr-0001.patch` | 完整 commit 补丁（可直接 `git am`）|
| `../../fastlane/metadata/` | 双语商店文案（截图占位）|

## 提交方法（二选一）

### 方法 A：Web 界面（最简单，无需本地 GitLab 配置）

1. 打开 https://gitlab.com/fdroid/fdroiddata
2. 点右上角 **Fork**（fork 到你自己的账号）
3. 在你的 fork 里打开 **Web IDE**（或 "+" → "New file"）
4. 新建路径：`metadata/com.xieguiawu.apicheckers.yml`
5. 粘贴下方「metadata 内容」段的完整内容
6. 提交到新分支（如 `add-api-checkers`）
7. 回到 fork 页面，点 **Create merge request**（目标 = fdroid/fdroiddata master）
8. MR 标题：`Add API Checkers (com.xieguiawu.apicheckers)`
9. MR 描述：粘贴下方「MR 描述」段

### 方法 B：本地 git（需 GitLab 账号 SSH/HTTPS 认证）

```bash
git clone https://gitlab.com/fdroid/fdroiddata.git
cd fdroiddata
git checkout -b add-api-checkers
git am /path/to/docs/fdroid/fdroiddata-mr-0001.patch   # 或手动创建 metadata 文件
git remote add mine <你的-fork-地址>
git push mine add-api-checkers
# 在 GitLab 网页创建 MR: 你的 fork:add-api-checkers → fdroid/fdroiddata:master
```

## metadata 内容

> 与 `com.xieguiawu.apicheckers.yml` 逐字一致（改一处必改两处）。
> 校验：`bash scripts/validate-fdroid-metadata.sh docs/fdroid/com.xieguiawu.apicheckers.yml`

```yaml
# F-Droid 收录 metadata
# 提交位置：gitlab.com/fdroid/fdroiddata → metadata/com.xieguiawu.apicheckers.yml
# 流程：fork fdroiddata → 新建该文件 → MR（GitLab CI 自动 lint + build 验证）
# 说明：Summary/Description 由仓库内 fastlane 元数据自动带入，此处不重复。
#       NonFreeNet：应用依赖 DeepSeek/opencode.ai/阿里云百炼/智星云 专有网络服务（不阻止收录）。
# 校验：bash scripts/validate-fdroid-metadata.sh docs/fdroid/com.xieguiawu.apicheckers.yml
# 可复现性（2026-09-12 于 tag v1.1.0 实测，双构建 unsigned 比对；签名 APK 逐构建不同）：
#   v1.1.0 -> b34c8279a7d5ec5c3a4bc5c752eabd218f451fbefafe92fe664f7fe701667757
# 注意：Builds 只列已打 tag 的版本。HEAD 上的智星云 + 白B.AI provider 尚未 bump 版本，
#       发版（versionCode 3 / tag v1.2.0）后再追加第三个 Build 块。

Categories:
  - System
License: MIT
AuthorName: xieguaiwu
AuthorEmail: xieguaiwu@users.noreply.github.com
SourceCode: https://github.com/xieguaiwu/pocket-llm-api-checker
IssueTracker: https://github.com/xieguaiwu/pocket-llm-api-checker/issues
Changelog: https://github.com/xieguaiwu/pocket-llm-api-checker/releases

AutoName: API Checkers

RepoType: git
Repo: https://github.com/xieguaiwu/pocket-llm-api-checker

Builds:
  - versionName: 1.0.0
    versionCode: 1
    commit: v1.0.0
    subdir: app
    gradle:
      - yes

  - versionName: 1.1.0
    versionCode: 2
    commit: v1.1.0
    subdir: app
    gradle:
      - yes

AntiFeatures:
  - NonFreeNet

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.1.0
CurrentVersionCode: 2
```

## MR 描述

```markdown
## Summary
Add API Checkers (com.xieguiawu.apicheckers) — a minimal dark-mode Android
app to check DeepSeek, OpenCode (Zen + Go plans) and Qwen Token Plan
(Alibaba Cloud Bailian) usage: balances, quotas and reset countdowns.

## Details
- MIT licensed
- NonFreeNet declared: a client for proprietary services (DeepSeek,
  opencode.ai, Alibaba Cloud Bailian, AI Galaxy). No ads, no tracking,
  no telemetry.
- Single INTERNET permission; API keys/cookies encrypted with Android
  Keystore AES-GCM; allowBackup=false; cleartext traffic disabled
- Some panels read official web pages (OpenCode workspace billing, Bailian
  console); the description notes these may change over time
- Reproducible build verified at tag v1.1.0 (two clean builds → identical
  unsigned APK SHA-256
  `b34c8279a7d5ec5c3a4bc5c752eabd218f451fbefafe92fe664f7fe701667757`)
- Fastlane metadata (en-US / zh-CN)
- Category System (validated against config/categories.yml)

## Build
- `gradle: yes`, `subdir: app`, commit v1.1.0 (clean tree, wrapper committed)
- Two Builds entries (v1.0.0 + v1.1.0) so the initial import carries history
```

## 评审关注点（reviewer 可能问）

- **NonFreeNet**：四个数据源均为专有服务（DeepSeek / opencode.ai / 阿里云百炼 /
  智星云），应用是纯客户端，无广告、无追踪、无遥测——已声明
- **页面解析**：OpenCode Zen billing 与 Qwen 配额依赖官方网页结构，
  full_description 已声明「页面变化可能导致失效」
- **可复现性**：unsigned 双构建一致 `b34c8279…6757`（tag v1.1.0，2026-09-12 实测）
- **类别**：`System`（用量查询工具），官方 categories.yml 有效
- **签名**：当前走 F-Droid 官方签名（无自有 keystore）

## 提交前自检清单

- [ ] `git ls-remote --tags origin` 含 v1.0.0 / v1.1.0
- [ ] `fastlane/metadata/android/{en-US,zh-CN}/changelogs/{1,2}.txt` 齐全
- [ ] `bash scripts/validate-fdroid-metadata.sh docs/fdroid/com.xieguiawu.apicheckers.yml` 通过
- [ ] （建议，非阻塞）真机截图替换 `fastlane/.../phoneScreenshots/` 占位图

MR 合并后 24-48 小时出现在 F-Droid 主仓库（签名步骤人工介入）。

> 后续：智星云 + 白B.AI provider 已合并但版本未 bump——真机冒烟后发 v1.2.0
> （versionCode 3 + `changelogs/3.txt` + yml 追加第三个 Build 块 + 更新 fastlane
> 文案补 galaxy/bai），Tags 模式会自动发现新 tag。
