"""把 data/*.json 导入数据库（幂等）。

用法：
    python seed.py             # 校验 + 导入
    python seed.py --dry-run   # 只校验，不写库

幂等策略：
- 维度/技能/岗位按业务 id upsert；
- 每个岗位的 requirements 先删后插（数量与顺序都可能变，逐条 diff 没有意义）；
- 种子里已不存在的维度/技能/岗位会被清理，避免残留脏数据影响统计。

校验不通过直接退出（退出码 1），不做「部分导入」——半套数据比没有数据更难排查。
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

from sqlalchemy import delete, select

from app.core.config import settings
from app.db.base import Base
from app.db.session import SessionLocal, engine
from app.models.entities import (
    AppMeta,
    Dimension,
    Job,
    JobRequirement,
    Skill,
)

DATA_DIR = Path(__file__).resolve().parent / settings.data_dir
SKILLS_FILE = DATA_DIR / "skills.json"
JOBS_FILE = DATA_DIR / "jobs.json"

CORE = "core"
PLUS = "plus"


def load_json(path: Path) -> dict:
    if not path.exists():
        raise SystemExit(f"找不到数据文件：{path}")
    with path.open(encoding="utf-8") as fp:
        return json.load(fp)


def validate(skills_doc: dict, jobs_doc: dict) -> tuple[list[str], list[str]]:
    """返回 (errors, warnings)。errors 非空即终止。"""
    errors: list[str] = []
    warnings: list[str] = []

    dimensions = skills_doc.get("dimensions") or []
    skills = skills_doc.get("skills") or []
    levels = skills_doc.get("levels") or []
    jobs = jobs_doc.get("jobs") or []

    if len(dimensions) != 6:
        errors.append(f"维度数应为 6，实际 {len(dimensions)}")
    if len(levels) < 2:
        errors.append("等级锚点缺失")

    dim_ids = [d["id"] for d in dimensions]
    if len(set(dim_ids)) != len(dim_ids):
        errors.append("维度 id 重复")

    skill_ids = [s["id"] for s in skills]
    duplicate_skills = [k for k, v in Counter(skill_ids).items() if v > 1]
    if duplicate_skills:
        errors.append(f"技能 id 重复：{duplicate_skills}")

    for skill in skills:
        if skill.get("dim") not in dim_ids:
            errors.append(f"技能 {skill['id']} 的维度 {skill.get('dim')} 不存在")

    # 同义词全局唯一（同一别名不能指向两个技能，否则解析必然误判）
    alias_owner: dict[str, str] = {}
    for skill in skills:
        for alias in skill.get("aliases") or []:
            key = alias.lower().strip()
            if key in alias_owner and alias_owner[key] != skill["id"]:
                errors.append(
                    f"同义词冲突：'{alias}' 同时属于 {alias_owner[key]} 与 {skill['id']}"
                )
            alias_owner[key] = skill["id"]

    skill_set = set(skill_ids)
    referenced: set[str] = set()

    for job in jobs:
        jid = job["id"]
        reqs = job.get("requirements") or []
        if not reqs:
            errors.append(f"岗位 {jid} 没有 requirements")
            continue

        total = sum(r["weight"] for r in reqs)
        if total != 100:
            errors.append(f"岗位 {jid} 权重合计 {total}，应为 100")

        seen: set[str] = set()
        for req in reqs:
            sid = req["skillId"]
            if sid not in skill_set:
                errors.append(f"岗位 {jid} 引用了不存在的技能 {sid}")
            if sid in seen:
                errors.append(f"岗位 {jid} 重复引用技能 {sid}")
            seen.add(sid)
            if req["weight"] <= 0:
                errors.append(f"岗位 {jid} 的技能 {sid} 权重非正数")
            if not 1 <= req["target"] <= 5:
                errors.append(f"岗位 {jid} 的技能 {sid} 目标等级 {req['target']} 越界")
            if req.get("type") not in (CORE, PLUS):
                errors.append(f"岗位 {jid} 的技能 {sid} type={req.get('type')} 非法")
            referenced.add(sid)

        # anyOf：成员必须存在，且不得同时是独立要求（否则同一技能被计两次）
        for req in reqs:
            for member in req.get("anyOf") or []:
                if member not in skill_set:
                    errors.append(f"岗位 {jid} 的 anyOf 成员 {member} 不存在")
                if member in seen:
                    errors.append(f"岗位 {jid} 的 anyOf 成员 {member} 同时是独立要求")
                # anyOf 成员虽然不单独计权，但模型里确实用到了它，不算「无人引用」
                referenced.add(member)

        core_weight = sum(r["weight"] for r in reqs if r["type"] == CORE)
        plus_weight = total - core_weight
        if core_weight == 0:
            warnings.append(f"岗位 {jid} 没有 core 要求，分析接口将返回 4001")

    unreferenced = sorted(skill_set - referenced)
    if unreferenced:
        warnings.append(f"{len(unreferenced)} 项技能未被任何岗位引用：{unreferenced}")

    return errors, warnings


def report(jobs: list[dict]) -> None:
    print("\n岗位权重构成")
    print(f"  {'岗位':<14}{'条目':>6}{'core':>8}{'plus':>8}{'合计':>8}")
    for job in jobs:
        reqs = job["requirements"]
        core = sum(r["weight"] for r in reqs if r["type"] == CORE)
        plus = sum(r["weight"] for r in reqs if r["type"] == PLUS)
        print(f"  {job['shortName']:<14}{len(reqs):>6}{core:>8}{plus:>8}{core + plus:>8}")


def run(dry_run: bool = False) -> int:
    skills_doc = load_json(SKILLS_FILE)
    jobs_doc = load_json(JOBS_FILE)

    errors, warnings = validate(skills_doc, jobs_doc)
    for warning in warnings:
        print(f"[警告] {warning}")
    if errors:
        print("\n数据校验失败：")
        for error in errors:
            print(f"  ✗ {error}")
        return 1
    print(f"校验通过：{len(skills_doc['skills'])} 技能 / "
          f"{len(skills_doc['dimensions'])} 维度 / {len(jobs_doc['jobs'])} 岗位")
    report(jobs_doc["jobs"])

    if dry_run:
        print("\n--dry-run：未写入数据库")
        return 0

    if not dry_run:
        Base.metadata.create_all(bind=engine)

    with SessionLocal() as db:
        # ---- 元信息 ----
        meta_row = db.get(AppMeta, 1)
        # dataVersion 是「给人看的标签」，不是内部版本号。
        # 优先取 jobs.json 的 displayBadge（示例数据 v0.1），没有则按约定拼装。
        version = skills_doc["meta"].get("version", "v0.1")
        badge = jobs_doc["meta"].get("displayBadge") or f"示例数据 {version}"
        payload = {
            "data_version": badge,
            # 用岗位模型的免责声明：自评页展示的是「权重与目标等级」这件事，
            # 技能词典那句讲的是同义词与维度归属，不对用户说。
            "disclaimer": jobs_doc["meta"].get("disclaimer")
            or skills_doc["meta"].get("disclaimer", ""),
            "scoring_version": jobs_doc["meta"].get("scoring", {}).get("version")
            or "v0.2-core-only",
            "levels": skills_doc["levels"],
        }
        if meta_row is None:
            db.add(AppMeta(id=1, **payload))
        else:
            for key, value in payload.items():
                setattr(meta_row, key, value)

        # ---- 维度 ----
        keep_dims = {d["id"] for d in skills_doc["dimensions"]}
        for order, dim in enumerate(skills_doc["dimensions"]):
            row = db.get(Dimension, dim["id"])
            values = {
                "name": dim["name"],
                "short": dim.get("short", ""),
                "description": dim.get("desc", ""),
                "why": dim.get("why", ""),
                "sort_order": order,
            }
            if row is None:
                db.add(Dimension(id=dim["id"], **values))
            else:
                for key, value in values.items():
                    setattr(row, key, value)
        db.flush()

        # ---- 技能 ----
        keep_skills = {s["id"] for s in skills_doc["skills"]}
        for order, skill in enumerate(skills_doc["skills"]):
            row = db.get(Skill, skill["id"])
            values = {
                "name": skill["name"],
                "dim_id": skill["dim"],
                "skill_group": skill.get("group", ""),
                "note": skill.get("note", ""),
                "aliases": skill.get("aliases") or [],
                "sort_order": order,
            }
            if row is None:
                db.add(Skill(id=skill["id"], **values))
            else:
                for key, value in values.items():
                    setattr(row, key, value)
        db.flush()

        # ---- 岗位 ----
        keep_jobs = {j["id"] for j in jobs_doc["jobs"]}
        for order, job in enumerate(jobs_doc["jobs"]):
            row = db.get(Job, job["id"])
            values = {
                "name": job["name"],
                "short_name": job["shortName"],
                "seniority": job.get("seniority", ""),
                "summary": job.get("summary", ""),
                "notes": job.get("notes", ""),
                "scoring_scope": job.get("scoringScope", ""),
                "data_version": jobs_doc["meta"].get("displayBadge", "示例数据 v0.1"),
                "sort_order": order,
            }
            if row is None:
                db.add(Job(id=job["id"], **values))
            else:
                for key, value in values.items():
                    setattr(row, key, value)
        db.flush()

        # ---- 要求：先删后插，保证与源文件完全一致 ----
        for job in jobs_doc["jobs"]:
            db.execute(delete(JobRequirement).where(JobRequirement.job_id == job["id"]))
            for order, req in enumerate(job["requirements"]):
                db.add(
                    JobRequirement(
                        job_id=job["id"],
                        skill_id=req["skillId"],
                        weight=req["weight"],
                        target=req["target"],
                        req_type=req["type"],
                        any_of=req.get("anyOf"),
                        any_of_note=req.get("anyOfNote"),
                        sort_order=order,
                    )
                )
        db.flush()

        # ---- 清理种子中已不存在的记录（顺序受外键约束）----
        stale_jobs = [j.id for j in db.execute(select(Job)).scalars() if j.id not in keep_jobs]
        for job_id in stale_jobs:
            db.execute(delete(JobRequirement).where(JobRequirement.job_id == job_id))
            db.execute(delete(Job).where(Job.id == job_id))
        stale_skills = [s.id for s in db.execute(select(Skill)).scalars() if s.id not in keep_skills]
        for skill_id in stale_skills:
            db.execute(delete(Skill).where(Skill.id == skill_id))
        stale_dims = [d.id for d in db.execute(select(Dimension)).scalars() if d.id not in keep_dims]
        for dim_id in stale_dims:
            db.execute(delete(Dimension).where(Dimension.id == dim_id))

        db.commit()

        counts = {
            "维度": db.execute(select(Dimension)).scalars().all().__len__(),
            "技能": db.execute(select(Skill)).scalars().all().__len__(),
            "岗位": db.execute(select(Job)).scalars().all().__len__(),
            "要求": db.execute(select(JobRequirement)).scalars().all().__len__(),
        }

    print("\n导入完成：", " / ".join(f"{k} {v}" for k, v in counts.items()))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="导入种子数据到数据库（幂等）")
    parser.add_argument("--dry-run", action="store_true", help="只校验，不写库")
    args = parser.parse_args()
    return run(dry_run=args.dry_run)


if __name__ == "__main__":
    sys.exit(main())
