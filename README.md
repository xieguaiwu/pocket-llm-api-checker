# API Checkers

[**中文版**](README_zh.md) | [**English**](#)

A minimal dark-mode Android app to quickly check your **DeepSeek API**, **OpenCode** (Zen + Go plans) and **Qwen Token Plan** (Alibaba Cloud Bailian) usage on your phone.

![dark minimal UI](docs/screenshot-placeholder.png)

## Features

- **DeepSeek**: balance (total / topped-up / granted, multi-currency) + official spend stats (today / 7-day / 30-day + daily breakdown, requires optional platform token)
- **OpenCode Go plan** (subscription): rolling 5h / weekly / monthly usage percentages with reset countdowns — via the official `/zen/go/v1/usage` API
- **OpenCode Zen plan** (pay-as-you-go): balance, monthly spend / limit, auto-reload settings — parsed from the workspace billing page
- **Qwen Token Plan** (subscription): model list (via the compatible-mode API) + 5-hour / 7-day quota windows with reset countdowns and rate-limit status (via the Bailian console RPC) — see the note on the console cookie below
- **Multiple accounts**: add, rename or delete DeepSeek / OpenCode / Qwen accounts anytime; each gets its own card on the home screen and a dedicated detail page
- **Pull-to-refresh**: pull down and hold for 2.5s to auto-refresh everything (or just pull and release past the threshold); plus auto-refresh every 5 minutes
- **Extremely minimal**: single dark color palette (9 colors), no gradients, no clutter
- **Secure by default**: all keys/cookies/tokens encrypted with Android Keystore AES-GCM before storage

## Data Sources

| Data | Endpoint | Auth |
|---|---|---|
| DeepSeek balance | `GET https://api.deepseek.com/user/balance` | API key |
| DeepSeek spend | `GET https://platform.deepseek.com/api/v0/usage/cost` | browser session token (optional) |
| OpenCode Go usage | `GET https://opencode.ai/zen/go/v1/usage` | Go API key |
| OpenCode Zen billing | `GET https://opencode.ai/workspace/{id}/billing` | workspace ID + browser `auth` cookie (optional) |
| Qwen models | `GET https://token-plan.<region>.maas.aliyuncs.com/compatible-mode/v1/models` | Token Plan subscription key (`sk-sp-…`, region-bound) |
| Qwen quota / tier | Bailian console RPC `POST …/data/api.json` (CN) / Qwen Cloud RPC (intl) | console cookie (optional; without it only the model list is shown) |

> **Qwen note**: the quota endpoint only accepts the Bailian console session — sending the API key there returns `BailianGateway.Login.NotLogined`. Paste your console cookie (any full `Cookie: …` request header works) in Settings to see the 5-hour / 7-day quota windows. Without the cookie, the app still shows the model list and plan tier.
>
> Zen billing has no public API yet ([opencode#10448](https://github.com/anomalyco/opencode/issues/10448)); the app parses the billing page's SSR data (same technique as the MIT-licensed [pi-go-bars](https://github.com/4cya/pi-go-bars)). If the page structure changes, the app shows a "parser outdated" error — update the app or check back later.

## Setup

1. Install the APK (`app/build/outputs/apk/debug/app-debug.apk` — arm64, works on non-Harmony Huawei phones)
2. Open **Settings** (gear icon)
3. Enter your DeepSeek API key
4. Add each OpenCode account:
   - **Go API Key**: from your OpenCode Zen dashboard (subscription key)
   - **Workspace ID** (optional, for Zen): from the URL `https://opencode.ai/workspace/wrk_XXXX/go`
   - **Auth Cookie** (optional, for Zen): browser DevTools → Application → Cookies → `opencode.ai` → `auth` (starts with `Fe26.2`)
5. Add each Qwen account:
   - **API Key** (optional): Bailian console Token Plan subscription key (`sk-sp-` prefix; bound to its region)
   - **Console Cookie** (optional, for quota windows): browser DevTools → Application → Cookies → `bailian.console.aliyun.com` — pasting the whole `Cookie: …` request header works
   - **Region**: mainland China (default) or international
6. Go back — everything refreshes automatically

## Build

```bash
./gradlew :app:testDebugUnitTest   # 56 unit tests
./gradlew :app:assembleDebug       # APK at app/build/outputs/apk/debug/app-debug.apk
```

Requires: JDK 17+, Android SDK (platform 35, build-tools 35.0.0), Android device with Android 8.0+ (minSdk 26).

## Security Notes

- Credentials never leave the device; they are encrypted (Keystore AES-GCM) in app-private storage
- `allowBackup=false` — no backup extraction
- The platform token and auth cookie expire (days to weeks); refresh them from the browser when errors appear
- If the Keystore fails, the app falls back to plaintext storage and shows a warning in Settings

## Disclaimer

Personal tool. Not affiliated with DeepSeek, OpenCode or Alibaba Cloud. Zen billing parsing may break if OpenCode changes their web page.

## F-Droid

F-Droid inclusion is in progress (see `docs/fdroid/com.xieguiawu.apicheckers.yml`).
The app carries a `NonFreeNet` anti-feature: it is a client for the proprietary
DeepSeek, opencode.ai and Alibaba Cloud services. No ads, no tracking, no telemetry.

Store metadata lives under `fastlane/metadata/android/` (en-US + zh-CN).
Release workflow: bump `versionCode`/`versionName` in `app/build.gradle.kts`,
tag `v<versionName>`, update `fastlane/metadata/android/*/changelogs/<versionCode>.txt`.
Verify reproducibility with `scripts/verify-reproducible.sh`.
