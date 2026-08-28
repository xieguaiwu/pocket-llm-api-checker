# API Checkers

[**English**](README.md) | [**中文版**](#)

一个极简深色模式的 Android 应用，在手机上快速查看 **DeepSeek API**、**OpenCode（Zen + Go 两个 plan）** 与 **Qwen Token Plan（阿里云百炼）** 的使用情况。

![深色极简界面](docs/screenshot-placeholder.png)

## 功能

- **DeepSeek**：余额（总额/充值/赠送，多币种）+ 官方消费统计（今天 / 近7天 / 近30天 + 每日明细，需可选配置平台 Token）
- **OpenCode Go plan（订阅制）**：滚动 5 小时 / 每周 / 每月 用量百分比 + 重置倒计时——走官方 `/zen/go/v1/usage` API
- **OpenCode Zen plan（按量付费）**：余额、本月花费/限额、自动充值设置——从 workspace 账单页解析
- **Qwen Token Plan（订阅制）**：模型清单（compatible-mode API）+ 5小时/7天配额窗口（带重置倒计时与限流状态，走百炼控制台 RPC）——配额窗口需可选配置控制台 Cookie，见下注
- **多账号区分**：可随时添加/重命名/删除 DeepSeek、OpenCode 与 Qwen 账号；首页独立卡片 + 独立详情页
- **下拉刷新**：下拉保持 2.5 秒自动全部刷新（或下拉过阈值松手即刷新）；另有每 5 分钟自动轮询
- **极简**：单一深色调色板（9 色），无渐变无装饰
- **安全默认**：所有 key / cookie / token 经 Android Keystore AES-GCM 加密后落盘

## 数据来源

| 数据 | 端点 | 认证 |
|---|---|---|
| DeepSeek 余额 | `GET https://api.deepseek.com/user/balance` | API key |
| DeepSeek 消费 | `GET https://platform.deepseek.com/api/v0/usage/cost` | 浏览器登录 token（可选） |
| OpenCode Go 用量 | `GET https://opencode.ai/zen/go/v1/usage` | Go API key |
| OpenCode Zen 账单 | `GET https://opencode.ai/workspace/{id}/billing` | workspace ID + 浏览器 `auth` cookie（可选） |
| Qwen 模型清单 | `GET https://token-plan.<region>.maas.aliyuncs.com/compatible-mode/v1/models` | Token Plan 订阅密钥（`sk-sp-` 开头，与区域绑定） |
| Qwen 配额/档位 | 百炼控制台 RPC `POST …/data/api.json`（大陆）/ Qwen Cloud RPC（国际） | 控制台 cookie（可选；缺失时仅显示模型清单） |

> **Qwen 说明**：配额接口只认百炼控制台会话——拿 API Key 打过去返回 `BailianGateway.Login.NotLogined`。在设置页粘贴控制台 Cookie（可直接粘贴整条 `Cookie: …` 请求头）后即可看到 5小时/7天配额窗口；无 Cookie 时仍显示模型清单与套餐档位。
>
> Zen 暂无公开 API（[opencode#10448](https://github.com/anomalyco/opencode/issues/10448)），应用解析账单页 SSR 数据（技术同 MIT 开源项目 [pi-go-bars](https://github.com/4cya/pi-go-bars)）。若网页结构变化，应用会提示「页面结构已变化」——等待应用更新或稍后再试。

## 设置方法

1. 安装 APK（`app/build/outputs/apk/debug/app-debug.apk`——arm64，适配非纯血鸿蒙的华为手机）
2. 打开**设置**（右上角齿轮）
3. 输入 DeepSeek API Key
4. 逐个添加 OpenCode 账号：
   - **Go API Key**：OpenCode Zen 控制台获取（订阅 key）
   - **Workspace ID**（可选，用于 Zen）：URL `https://opencode.ai/workspace/wrk_XXXX/go` 中复制
   - **Auth Cookie**（可选，用于 Zen）：浏览器 DevTools → Application → Cookies → `opencode.ai` → `auth`（以 `Fe26.2` 开头）
5. 逐个添加 Qwen 账号：
   - **API Key**（可选）：百炼控制台 Token Plan 订阅密钥（`sk-sp-` 开头，与区域绑定）
   - **控制台 Cookie**（可选，用于配额窗口）：浏览器 DevTools → Application → Cookies → `bailian.console.aliyun.com`，整段 `Cookie: …` 请求头直接粘贴即可
   - **区域**：中国大陆（默认）/ 国际
6. 返回首页——自动刷新全部数据

## 构建

```bash
./gradlew :app:testDebugUnitTest   # 56 个单元测试
./gradlew :app:assembleDebug       # APK 输出到 app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17+、Android SDK（platform 35 + build-tools 35.0.0）、Android 8.0+ 设备（minSdk 26）。

## 安全说明

- 凭据不离开设备，经 Keystore AES-GCM 加密后存于应用私有存储
- `allowBackup=false`——无法通过备份导出
- 平台 Token 与 auth cookie 会过期（几天到几周），报错时从浏览器重新复制
- Keystore 异常时降级明文存储，并在设置页显示安全警告

## 免责声明

个人工具，与 DeepSeek / OpenCode / 阿里云无关联。Zen 账单解析依赖网页结构，OpenCode 改版可能失效。

## F-Droid

F-Droid 收录进行中（草稿见 `docs/fdroid/com.xieguiawu.apicheckers.yml`）。
应用带有 `NonFreeNet` 反特性标记：它是 DeepSeek、opencode.ai 与阿里云
专有服务的客户端。无广告、无追踪、无遥测。

商店元数据位于 `fastlane/metadata/android/`（en-US + zh-CN）。
发版流程：在 `app/build.gradle.kts` 递增 `versionCode`/`versionName` →
打 `v<versionName>` tag → 更新 `fastlane/metadata/android/*/changelogs/<versionCode>.txt`。
可复现性用 `scripts/verify-reproducible.sh` 验证。
