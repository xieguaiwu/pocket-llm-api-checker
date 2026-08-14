# API Checkers

[**English**](README.md) | [**中文版**](#)

一个极简深色模式的 Android 应用，在手机上快速查看 **DeepSeek API** 与 **OpenCode（Zen + Go 两个 plan）** 的使用情况。

![深色极简界面](docs/screenshot-placeholder.png)

## 功能

- **DeepSeek**：余额（总额/充值/赠送，多币种）+ 官方消费统计（今天 / 近7天 / 近30天 + 每日明细，需可选配置平台 Token）
- **OpenCode Go plan（订阅制）**：滚动 5 小时 / 每周 / 每月 用量百分比 + 重置倒计时——走官方 `/zen/go/v1/usage` API
- **OpenCode Zen plan（按量付费）**：余额、本月花费/限额、自动充值设置——从 workspace 账单页解析
- **多账号区分**：可添加最多 3 个（或更多）OpenCode 账号，可随时重命名/删除；首页独立卡片 + 独立详情页
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

> Zen 暂无公开 API（[opencode#10448](https://github.com/anomalyco/opencode/issues/10448)），应用解析账单页 SSR 数据（技术同 MIT 开源项目 [pi-go-bars](https://github.com/4cya/pi-go-bars)）。若网页结构变化，应用会提示「页面结构已变化」——等待应用更新或稍后再试。

## 设置方法

1. 安装 APK（`app/build/outputs/apk/debug/app-debug.apk`——arm64，适配非纯血鸿蒙的华为手机）
2. 打开**设置**（右上角齿轮）
3. 输入 DeepSeek API Key
4. 逐个添加 OpenCode 账号：
   - **Go API Key**：OpenCode Zen 控制台获取（订阅 key）
   - **Workspace ID**（可选，用于 Zen）：URL `https://opencode.ai/workspace/wrk_XXXX/go` 中复制
   - **Auth Cookie**（可选，用于 Zen）：浏览器 DevTools → Application → Cookies → `opencode.ai` → `auth`（以 `Fe26.2` 开头）
5. 返回首页——自动刷新全部数据

## 构建

```bash
./gradlew :app:testDebugUnitTest   # 18 个单元测试
./gradlew :app:assembleDebug       # APK 输出到 app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17+、Android SDK（platform 35 + build-tools 35.0.0）、Android 8.0+ 设备（minSdk 26）。

## 安全说明

- 凭据不离开设备，经 Keystore AES-GCM 加密后存于应用私有存储
- `allowBackup=false`——无法通过备份导出
- 平台 Token 与 auth cookie 会过期（几天到几周），报错时从浏览器重新复制
- Keystore 异常时降级明文存储，并在设置页显示安全警告

## 免责声明

个人工具，与 DeepSeek / OpenCode 无关联。Zen 账单解析依赖网页结构，OpenCode 改版可能失效。
