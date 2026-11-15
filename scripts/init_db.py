"""执行 docs/schema.sql 建库建表。

口令从项目根 `.env` 读取，不在命令行里出现；脚本可反复执行（全部 DDL 都是 IF NOT EXISTS）。
用法：analysis-service/.venv/Scripts/python.exe scripts/init_db.py
"""

from __future__ import annotations

import sys
from pathlib import Path

import pymysql

ROOT = Path(__file__).resolve().parents[1]


def read_env() -> dict[str, str]:
    env: dict[str, str] = {}
    path = ROOT / ".env"
    if not path.is_file():
        sys.exit(f"缺少 {path}，请先复制 .env.example 并填写")
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        env[key.strip()] = value.strip()
    return env


def split_statements(sql: str) -> list[str]:
    """按分号切语句，跳过注释行。

    没有存储过程与自定义分隔符，简单切分足够；用 sqlite3 那类 execute 多次的方式反而更脆。
    """
    lines = [ln for ln in sql.splitlines() if not ln.strip().startswith("--")]
    return [stmt.strip() for stmt in "\n".join(lines).split(";") if stmt.strip()]


def main() -> int:
    env = read_env()
    conn = pymysql.connect(
        host=env.get("DB_HOST", "127.0.0.1"),
        port=int(env.get("DB_PORT", "3306")),
        user=env.get("DB_USERNAME", "root"),
        password=env.get("DB_PASSWORD", ""),
        charset="utf8mb4",
        autocommit=True,
    )
    sql = (ROOT / "docs" / "schema.sql").read_text(encoding="utf-8")
    statements = split_statements(sql)

    with conn.cursor() as cur:
        for stmt in statements:
            cur.execute(stmt)
        cur.execute("USE career_path")
        cur.execute("SHOW TABLES")
        tables = [row[0] for row in cur.fetchall()]
        cur.execute(
            "SELECT table_name, table_comment FROM information_schema.tables "
            "WHERE table_schema = 'career_path' ORDER BY table_name"
        )
        rows = cur.fetchall()

    conn.close()
    print(f"执行 {len(statements)} 条语句，库 career_path 现有 {len(tables)} 张表：")
    for name, comment in rows:
        print(f"  {name:28s} {comment or ''}")
    return 0 if len(tables) == 15 else 1


if __name__ == "__main__":
    raise SystemExit(main())
