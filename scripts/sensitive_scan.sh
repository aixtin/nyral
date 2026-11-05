#!/usr/bin/env bash
# 发布前敏感扫描（开源平台发布检查清单的可执行版）。
# 退出码: 0=通过（含豁免项提示）, 1=硬敏感命中未豁免。
# 用法: bash scripts/sensitive_scan.sh
# 说明: 品牌词表在运行时拼接，避免本脚本自身命中第三方品牌词条款（自查不误报）。
set -u
cd "$(git rev-parse --show-toplevel)"

# 豁免名单（人工确认保留的命中项，逐条注明依据）：
#  - COLLABORATION.md  合作声明本体，项目所有者确认故意保留并公开（2026-10-31 确认，2026-11-05 复核）
#  - README.md         其中的协作披露句（"assisted by Marvis ... See COLLABORATION.md"）
#                      与合作声明属同一份已确认公开披露，一并豁免
EXEMPT=( "COLLABORATION.md" "README.md" )

is_exempt() {
  local f="$1"
  for e in "${EXEMPT[@]}"; do
    [[ "$f" == "$e" ]] && return 0
  done
  return 1
}

# 品牌词表运行时拼接，避免脚本自身触发第三方品牌词检查。
# 范围限定：只查「AI 助手身份类」名称；模型供应商名（在 README/PRIVACY 等功能文档
# 中作为受支持服务如实列出）属产品事实描述，不在此列。
B1="$(printf 'Mar%s' 'vis')"; B2="$(printf 'Droid%s' 'Agent')"
BRAND="${B1}|${B2}"

HARD='ghp_[A-Za-z0-9]{20,}|glpat_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9]{20,}|BEGIN (RSA|OPENSSH|EC|DSA) PRIVATE KEY'
HARD="$HARD|$BRAND"
# 公网 VPS 段与 WG 网段（通用正则，不含真实内部地址）；192.168.x 局域网示例入软敏感
HARD="$HARD|\b(8\.137\.6[0-9]{1,2}\.[0-9]{1,3}|10\.10\.10\.[0-9]{1,3})\b"

SOFT='\b188\b|atin\.asia|droidagent\.local|/opt/marvis-memory|连接卡|vps_key|192\.168\.[0-9]{1,3}\.[0-9]{1,3}'

fail=0
echo "== 硬敏感扫描（0 命中才放行）"
while IFS= read -r line; do
  f="${line%%:*}"
  if is_exempt "$f"; then
    echo "  [豁免] $line"
  else
    echo "  [命中] $line"; fail=1
  fi
done < <(git grep -nE "$HARD" -- . ':(exclude)scripts/sensitive_scan.sh' 2>/dev/null)

echo "== 软敏感扫描（人工判断语境）"
git grep -nE "$SOFT" -- . ':(exclude)scripts/sensitive_scan.sh' 2>/dev/null | head -20 || true

if [[ $fail -ne 0 ]]; then echo "结论: 未通过，处理命中项后再发布"; exit 1; fi
echo "结论: 通过（豁免项 ${#EXEMPT[@]} 条，均已人工确认）"
