#!/usr/bin/env bash
# 可复现构建验证：同一 commit 干净树构建两次，比较 release APK 的 SHA-256。
# 用法：scripts/verify-reproducible.sh
# 要求：ANDROID_HOME 已设置、git 工作区干净（在 tag 处）。
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "错误：未设置 ANDROID_HOME" >&2; exit 1
fi
if ! git diff --quiet --exit-code; then
  echo "错误：工作区不干净，请在 tag 处干净树运行" >&2; exit 1
fi

# F-Droid buildserver 会设置 SOURCE_DATE_EPOCH；本地验证保持同位
export SOURCE_DATE_EPOCH="$(git log -1 --format=%ct)"
echo "commit: $(git rev-parse --short HEAD)  SOURCE_DATE_EPOCH: $SOURCE_DATE_EPOCH"

for i in 1 2; do
  echo "== 构建 $i/2 =="
  ./gradlew clean assembleRelease --no-daemon > "/tmp/rb-build-$i.log" 2>&1
  find app/build/outputs/apk -name '*.apk' | sort | xargs sha256sum > "/tmp/rb-hash-$i.txt"
done

if diff -u "/tmp/rb-hash-1.txt" "/tmp/rb-hash-2.txt"; then
  echo "OK：两次构建 APK 哈希一致（可复现）"
  cat "/tmp/rb-hash-1.txt"
else
  echo "FAIL：两次构建不一致，见上 diff。常见原因：构建缓存残留/时间戳/非确定性生成物。" >&2
  exit 1
fi
