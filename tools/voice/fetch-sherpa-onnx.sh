#!/usr/bin/env bash
# 取语音的运行库 sherpa-onnx(Apache-2.0,内含静态链接的 ONNX Runtime,MIT):
# 按 tools/voice/sources.lock 里的地址下载官方 AAR,核对 SHA-256,放到 app/libs/<文件名>,Gradle 从那里取。
# AAR 不进 git(app/libs/ 被忽略);没取过就编译,构建会停下并提示先运行本脚本。
#
# 用法:tools/voice/fetch-sherpa-onnx.sh [--check]
#   (无参数) 已有且校验值对上就什么都不做;没有或对不上就重新下载,下完再核对,对不上删掉并退出 1
#   --check  只核对 app/libs/ 里已有的文件,不下载;缺了或对不上退出 1
#
# 需要 curl、sha256sum 或 shasum。只接受 https:// 地址,跳转也只走 https。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOCK="$ROOT/tools/voice/sources.lock"
OUT_DIR="$ROOT/app/libs"

CHECK=0
case "${1:-}" in
	"") ;;
	--check) CHECK=1 ;;
	*) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

sha256() {
	if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
	else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

status=0
found=0
while read -r name version url sum; do
	case "$name" in ''|'#'*) continue ;; esac
	found=1
	case "$url" in https://*) ;; *) echo "only https:// sources allowed: $name $url" >&2; exit 1 ;; esac
	case "$sum" in [0-9a-f]*) [ "${#sum}" -eq 64 ] || { echo "bad sha256 for $name in $LOCK" >&2; exit 1; } ;; *) echo "bad sha256 for $name in $LOCK" >&2; exit 1 ;; esac
	file="$OUT_DIR/${url##*/}"
	if [ -f "$file" ] && [ "$(sha256 "$file")" = "$sum" ]; then
		echo "ok      $name $version  ${file#"$ROOT"/}"
		continue
	fi
	if [ "$CHECK" -eq 1 ]; then
		if [ -f "$file" ]; then echo "SHA-256 mismatch: ${file#"$ROOT"/} (expected $sum)" >&2
		else echo "missing: ${file#"$ROOT"/} (run tools/voice/fetch-sherpa-onnx.sh)" >&2; fi
		status=1
		continue
	fi
	mkdir -p "$OUT_DIR"
	echo "fetch   $name $version  $url"
	curl -fSL --retry 3 --proto '=https' --proto-redir '=https' -o "$file.tmp" "$url"
	actual="$(sha256 "$file.tmp")"
	if [ "$actual" != "$sum" ]; then
		echo "SHA-256 mismatch for $name: $actual (expected $sum)" >&2
		rm -f "$file.tmp"
		exit 1
	fi
	mv "$file.tmp" "$file"
	echo "ok      $name $version  ${file#"$ROOT"/}"
done < "$LOCK"
[ "$found" -eq 1 ] || { echo "no entries in $LOCK" >&2; exit 1; }
exit "$status"
