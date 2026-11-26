#!/usr/bin/env bash
# 出未签名 IPA。
#
# 前提：iOS 侧是**远程客户端**，不再打包 CPython ——
# Foundation.Process 在 iOS 上 unavailable，沙盒也不允许 fork/exec。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS="$ROOT/ios"

echo "==> 校验工程文件完整性"
python3 "$ROOT/scripts/check_ios_project.py" "$ROOT"

echo "==> xcodebuild archive（不签名）"
cd "$IOS"
xcodebuild -project SigninHub.xcodeproj -scheme SigninHub -configuration Release -sdk iphoneos -destination "generic/platform=iOS" -archivePath "$ROOT/build/SigninHub.xcarchive" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" archive 2>&1 | tee /tmp/xcodebuild.log | tail -40

APP="$ROOT/build/SigninHub.xcarchive/Products/Applications/SigninHub.app"
test -d "$APP" || { echo "归档里没有 SigninHub.app"; exit 1; }

echo "==> 打 IPA"
rm -rf "$ROOT/build/Payload"
mkdir -p "$ROOT/build/Payload"
cp -R "$APP" "$ROOT/build/Payload/"
cd "$ROOT/build"
zip -qry "$ROOT/build/SigninHub-unsigned.ipa" Payload
ls -lh "$ROOT/build/SigninHub-unsigned.ipa"
