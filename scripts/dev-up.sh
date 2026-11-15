#!/usr/bin/env bash
# 一键启动三进程（Git Bash / WSL 均可）。
#
#   analysis-service  127.0.0.1:8000  FastAPI  /internal
#   backend-java      127.0.0.1:8080  Spring Boot /api
#   frontend          127.0.0.1:5173  Vite（/api 代理到 8080）
#
# 日志：.logs/analysis.log、.logs/java.log、.logs/vite.log
# 停止：scripts/dev-down.sh（或直接关掉本终端）

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
LOGS="$ROOT/.logs"
mkdir -p "$LOGS"

# ---------- 0. 前置检查 ----------
if [[ ! -f .env ]]; then
  echo "[dev-up] 缺少 .env：先 cp .env.example .env 并填数据库口令" >&2
  exit 1
fi
INTERNAL_TOKEN="$(grep -E '^INTERNAL_TOKEN=' .env | cut -d= -f2- || true)"
INTERNAL_TOKEN="${INTERNAL_TOKEN:-careerpath-local-key}"

# ---------- 1. 分析服务 ----------
if [[ ! -x analysis-service/.venv/Scripts/python.exe && ! -x analysis-service/.venv/bin/python ]]; then
  echo "[dev-up] 首次运行：创建 Python 虚拟环境并安装依赖（约 1 分钟）"
  ( cd analysis-service && py -m venv .venv 2>/dev/null || python3 -m venv .venv )
  if [[ -x analysis-service/.venv/Scripts/python.exe ]]; then PY=analysis-service/.venv/Scripts/python.exe; else PY=analysis-service/.venv/bin/python; fi
  "$PY" -m pip install -q -i https://mirrors.aliyun.com/pypi/simple/ -r analysis-service/requirements.txt
fi

if [[ -x analysis-service/.venv/Scripts/python.exe ]]; then
  PY="$ROOT/analysis-service/.venv/Scripts/python.exe"
else
  PY="$ROOT/analysis-service/.venv/bin/python"
fi

echo "[dev-up] 启动 analysis-service :8000"
( cd analysis-service && "$PY" -m uvicorn app.main:app --host 127.0.0.1 --port 8000 > "$LOGS/analysis.log" 2>&1 & echo $! > "$LOGS/analysis.pid" )

# ---------- 2. Java 主服务 ----------
JAR="$(ls backend-java/target/careerpath-api-*.jar 2>/dev/null | grep -v original | head -1 || true)"
if [[ -z "$JAR" ]]; then
  echo "[dev-up] 未找到 jar，先构建 backend-java（首次会下载依赖，请耐心等待）"
  ( cd backend-java && JAVA_HOME="${JAVA_HOME:-/c/Program Files/Java/jdk-25.0.2}" \
      /c/Users/24693/.local/share/maven/apache-maven-3.9.16/bin/mvn -B -q -DskipTests package ) || {
      echo "[dev-up] 构建失败，请查看上面的输出" >&2; exit 1; }
  JAR="$(ls backend-java/target/careerpath-api-*.jar | grep -v original | head -1)"
fi

echo "[dev-up] 启动 backend-java :8080"
( cd backend-java && java -jar "$ROOT/$JAR" --server.port=8080 > "$LOGS/java.log" 2>&1 & echo $! > "$LOGS/java.pid" )

# ---------- 3. 前端 ----------
if [[ ! -d frontend/node_modules ]]; then
  echo "[dev-up] 安装前端依赖"
  ( cd frontend && npm install --no-audit --no-fund --registry=https://registry.npmmirror.com )
fi

echo "[dev-up] 启动 frontend :5173"
( cd frontend && npm run dev > "$LOGS/vite.log" 2>&1 & echo $! > "$LOGS/vite.pid" )

# ---------- 4. 等待就绪 ----------
wait_for() {
  local name="$1" url="$2" expect="$3" tries=60
  for _ in $(seq 1 "$tries"); do
    if curl -s --noproxy '*' -o /tmp/cp_health.json -w '%{http_code}' "$url" 2>/dev/null | grep -q "$expect"; then
      echo "[dev-up] ✓ $name 就绪"
      return 0
    fi
    sleep 1
  done
  echo "[dev-up] ✗ $name 超时未就绪（$url），见 .logs/${name//-/_}.log" >&2
  return 1
}

echo
wait_for "backend-java" "http://127.0.0.1:8080/api/health" "200"
wait_for "analysis-service" "http://127.0.0.1:8000/internal/health" "200" || true
wait_for "frontend" "http://127.0.0.1:5173/" "200" || true

echo
echo "──────────── 三进程状态 ────────────"
echo "  Java     http://127.0.0.1:8080/api/health"
echo "  分析服务  http://127.0.0.1:8000/internal/health  （需请求头 X-Internal-Token: $INTERNAL_TOKEN）"
echo "  前端      http://127.0.0.1:5173/"
echo "  日志      .logs/*.log    停止：scripts/dev-down.sh"
echo "────────────────────────────────────"
