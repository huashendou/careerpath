#!/usr/bin/env bash
# 职航 CareerPath —— 一键起三进程：Java(8080) / 分析服务(8000) / 前端(5173)
#
# 有三件事必须做对，否则会出现「脚本说没起来，但服务其实是好的」：
#   1. Java 必须显式 --server.port=8080。环境里若残留 SERVER_PORT 一类变量，
#      不指定端口会被随机分到 5 万以上，后面所有探活都会打错地方。
#   2. 探 /internal/health 必须带 X-Internal-Token。分析服务的内部接口一律校验
#      共享密钥，不带就是 401，只会在那里空转到超时。
#   3. curl 一律加 --noproxy '*'。本机装了 HTTP 代理，不绕过就会拿到 502。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS="$ROOT/.logs"
ENV_FILE="$ROOT/.env"
JAR="$ROOT/backend-java/target/careerpath-api-0.1.0-SNAPSHOT.jar"
PY="$ROOT/analysis-service/.venv/Scripts/python.exe"
[[ -x "$PY" ]] || PY="$ROOT/analysis-service/.venv/bin/python"

mkdir -p "$LOGS"
die() { echo "[dev-up] 失败：$*" >&2; exit 1; }

# Maven 装在用户目录下、不保证每个 shell 的 PATH 都带它（docs/P0-重构方案.md 第 22 行）。
# 顺序：环境变量 MVN > PATH 上的 mvn > 项目约定的安装路径。
resolve_mvn() {
  if [[ -n "${MVN:-}" ]]; then echo "$MVN"; return; fi
  if command -v mvn > /dev/null 2>&1; then command -v mvn; return; fi
  local candidate="$HOME/.local/share/maven/apache-maven-3.9.16/bin/mvn"
  [[ -x "$candidate" ]] && echo "$candidate"
}

# 内部令牌只从根 .env 读：Java 与分析服务共用这一份，避免两边漂移成 401。
read_internal_token() {
  [[ -f "$ENV_FILE" ]] || return 0
  grep -E '^INTERNAL_TOKEN=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r'
}

http_code() { # http_code <url> [额外 curl 参数...]
  local url="$1"; shift
  curl -s --noproxy '*' -o /dev/null -w '%{http_code}' --max-time 2 "$@" "$url" 2>/dev/null
}

wait_for() { # wait_for <名称> <pid> <url> [额外 curl 参数...]
  local name="$1" pid="$2" url="$3"; shift 3
  local i
  for i in $(seq 1 60); do
    if [[ "$(http_code "$url" "$@")" == "200" ]]; then
      echo "[dev-up] $name 就绪（约 ${i}s）"
      return 0
    fi
    # 进程已经死了就别再等满 60 秒，直接把日志尾部抛出来
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "[dev-up] $name 进程已退出，日志尾部：" >&2
      tail -20 "$LOGS/$name.log" >&2 2>/dev/null
      return 1
    fi
    sleep 1
  done
  echo "[dev-up] $name 60s 未就绪：$url" >&2
  tail -20 "$LOGS/$name.log" >&2 2>/dev/null
  return 1
}

# ---------------------------------------------------------------- 0. 前置检查
command -v java > /dev/null || die "PATH 上找不到 java，请确认 JDK 在 PATH 里"
command -v node > /dev/null || die "PATH 上找不到 node"
[[ -x "$PY" ]] || die "找不到 analysis-service/.venv，先执行：
    cd analysis-service && python -m venv .venv && .venv/Scripts/pip install -r requirements.txt"

# ---------------------------------------------------------------- 1. Java 打包
# jar 不存在，或源码/pom 比 jar 新时才重打，避免每次起服务都等一次 Maven。
# 判定只看普通文件（-type f）：目录的 mtime 会因增删文件而变化，
# 用目录参与比较会让脚本每次都误判成「需要重打」。
if [[ ! -f "$JAR" ]] || [[ -n "$(find "$ROOT/backend-java/src" "$ROOT/backend-java/pom.xml" -type f -newer "$JAR" -print -quit 2>/dev/null)" ]]; then
  MVN_BIN="$(resolve_mvn)"
  [[ -n "$MVN_BIN" ]] || die "找不到 mvn：请把 Maven 装到 \$HOME/.local/share/maven/apache-maven-3.9.16，或用 MVN=/path/to/mvn 指定"
  echo "[dev-up] Maven 打包中…（$MVN_BIN）"
  ( cd "$ROOT/backend-java" && "$MVN_BIN" -q -DskipTests package ) || die "Maven 打包失败"
fi

# ---------------------------------------------------------------- 2. 起三个进程
# 用 ( cd … && exec … ) & 而不是先 cd 再 &：exec 让子 shell 被目标进程替换，
# $! 拿到的就是服务本身的 pid，dev-down 才能准确停掉它。
( cd "$ROOT" && exec java -jar "$JAR" --server.port=8080 ) > "$LOGS/java.log" 2>&1 &
echo $! > "$LOGS/java.pid"
echo "[dev-up] java     启动 pid=$(cat "$LOGS/java.pid") → .logs/java.log"

( cd "$ROOT/analysis-service" && exec "$PY" -m uvicorn app.main:app --host 127.0.0.1 --port 8000 ) > "$LOGS/analysis.log" 2>&1 &
echo $! > "$LOGS/analysis.pid"
echo "[dev-up] analysis 启动 pid=$(cat "$LOGS/analysis.pid") → .logs/analysis.log"

( cd "$ROOT/frontend" && exec npm run dev ) > "$LOGS/vite.log" 2>&1 &
echo $! > "$LOGS/vite.pid"
echo "[dev-up] vite     启动 pid=$(cat "$LOGS/vite.pid") → .logs/vite.log"

# ---------------------------------------------------------------- 3. 等就绪
TOKEN="$(read_internal_token)"
if [[ -z "$TOKEN" ]]; then
  echo "[dev-up] 警告：根 .env 里没读到 INTERNAL_TOKEN，分析服务探活会一直 401" >&2
fi

wait_for java     "$(cat "$LOGS/java.pid")"     "http://127.0.0.1:8080/api/health"     || die "Java 未就绪，服务可能已部分启动，先 bash scripts/dev-down.sh"
wait_for analysis "$(cat "$LOGS/analysis.pid")" "http://127.0.0.1:8000/internal/health" -H "X-Internal-Token: $TOKEN" || die "分析服务未就绪，先 bash scripts/dev-down.sh"
wait_for vite     "$(cat "$LOGS/vite.pid")"     "http://127.0.0.1:5173/"               || die "前端未就绪，先 bash scripts/dev-down.sh"

# ---------------------------------------------------------------- 4. 汇总
echo
echo "[dev-up] 三进程已就绪："
echo "  前端    http://localhost:5173        （/api 代理到 8080）"
echo "  Java    http://localhost:8080/api/health"
echo "  分析    http://localhost:8000/internal/health（需 X-Internal-Token，不对外）"
echo "[dev-up] 停止：bash scripts/dev-down.sh"
