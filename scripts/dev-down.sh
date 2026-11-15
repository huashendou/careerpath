#!/usr/bin/env bash
# 停止 scripts/dev-up.sh 启动的三个进程。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS="$ROOT/.logs"

for name in java analysis vite; do
  pid_file="$LOGS/$name.pid"
  [[ -f "$pid_file" ]] || continue
  pid="$(cat "$pid_file")"
  if kill -0 "$pid" 2>/dev/null; then
    # Vite 用 npm 起的，杀 npm 进程后子进程（esbuild）可能残留，顺带清掉
    kill "$pid" 2>/dev/null && echo "[dev-down] 已停止 $name (pid $pid)"
  else
    echo "[dev-down] $name 已不在运行"
  fi
  rm -f "$pid_file"
done

# 兜底：确认端口已释放
for port in 8000 8080 5173; do
  if curl -s --noproxy '*' -o /dev/null --max-time 1 "http://127.0.0.1:$port/" 2>/dev/null; then
    echo "[dev-down] 警告：端口 $port 仍有响应，请手动检查残留进程" >&2
  fi
done
