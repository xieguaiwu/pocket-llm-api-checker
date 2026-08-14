# API Checkers

[**中文版**](README_zh.md) | [**English**](#)

A minimal dark-mode Android app to quickly check your **DeepSeek API** and **OpenCode** (Zen + Go plans) usage on your phone.

![dark minimal UI](docs/screenshot-placeholder.png)

## Features

- **DeepSeek**: balance (total / topped-up / granted, multi-currency) + official spend stats (today / 7-day / 30-day + daily breakdown, requires optional platform token)
- **OpenCode Go plan** (subscription): rolling 5h / weekly / monthly usage percentages with reset countdowns — via the official `/zen/go/v1/usage` API
- **OpenCode Zen plan** (pay-as-you-go): balance, monthly spend / limit, auto-reload settings — parsed from the workspace billing page
- **Multiple OpenCode accounts**: add up to 3 (or more) accounts with distinct names, rename or delete them anytime; each gets its own card on the home screen and a dedicated detail page
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

> Zen billing has no public API yet ([opencode#10448](https://github.com/anomalyco/opencode/issues/10448)); the app parses the billing page's SSR data (same technique as the MIT-licensed [pi-go-bars](https://github.com/4cya/pi-go-bars)). If the page structure changes, the app shows a "parser outdated" error — update the app or check back later.

## Setup

1. Install the APK (`app/build/outputs/apk/debug/app-debug.apk` — arm64, works on non-Harmony Huawei phones)
2. Open **Settings** (gear icon)
3. Enter your DeepSeek API key
4. Add each OpenCode account:
   - **Go API Key**: from your OpenCode Zen dashboard (subscription key)
   - **Workspace ID** (optional, for Zen): from the URL `https://opencode.ai/workspace/wrk_XXXX/go`
   - **Auth Cookie** (optional, for Zen): browser DevTools → Application → Cookies → `opencode.ai` → `auth` (starts with `Fe26.2`)
5. Go back — everything refreshes automatically

## Build

```bash
./gradlew :app:testDebugUnitTest   # 18 unit tests
./gradlew :app:assembleDebug       # APK at app/build/outputs/apk/debug/app-debug.apk
```

Requires: JDK 17+, Android SDK (platform 35, build-tools 35.0.0), Android device with Android 8.0+ (minSdk 26).

## Security Notes

- Credentials never leave the device; they are encrypted (Keystore AES-GCM) in app-private storage
- `allowBackup=false` — no backup extraction
- The platform token and auth cookie expire (days to weeks); refresh them from the browser when errors appear
- If the Keystore fails, the app falls back to plaintext storage and shows a warning in Settings

## Disclaimer

Personal tool. Not affiliated with DeepSeek or OpenCode. Zen billing parsing may break if OpenCode changes their web page.
