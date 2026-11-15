#!/usr/bin/env bash
# 职航 CareerPath —— 停止 scripts/dev-up.sh 起的三个进程。
#
# 先按 .logs/*.pid 精确停；再按端口兜底。
# 兜底是必要的：vite 实际是 npm 拉起的 node 子进程，父进程被 kill 后
# node 可能仍然占着 5173，下次 dev-up 会报「端口已占用」。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS="$ROOT/.logs"

for name in java analysis vite; do
  pid_file="$LOGS/$name.pid"
  [[ -f "$pid_file" ]] || continue
  pid="$(cat "$pid_file")"
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null && echo "[dev-down] 已停止 $name (pid $pid)"
  else
    echo "[dev-down] $name 已不在运行（清掉残留 pid 文件）"
  fi
  rm -f "$pid_file"
done

# 端口兜底：只在能拿到 netstat + taskkill 的环境里做（Windows / Git Bash）
if command -v netstat > /dev/null 2>&1 && command -v taskkill > /dev/null 2>&1; then
  for port in 8000 8080 5173; do
    pid="$(netstat -ano | grep ":$port " | grep LISTENING | awk '{print $5}' | head -1)"
    if [[ -n "${pid:-}" ]]; then
      if taskkill //F //PID "$pid" > /dev/null 2>&1; then
        echo "[dev-down] 端口 $port 仍被占用，已强制结束 pid $pid"
      else
        echo "[dev-down] 警告：端口 $port 被 pid $pid 占用且无法结束，请手动处理" >&2
      fi
    fi
  done
fi

echo "[dev-down] 完成"
