"""ORM 模型：9 张表。

| 表 | 用途 |
|---|---|
| `dimensions` | 能力维度（固定 6 个） |
| `skills` | 技能词典（61 项），别名只服务后端解析 |
| `jobs` | 岗位模型（3 个） |
| `job_requirements` | 岗位对技能的要求（86 条），(job_id, skill_id) 唯一 |
| `app_meta` | 单行：数据版本、免责声明、等级锚点 |
| `users` | 账号 |
| `profiles` | 技能画像，一人最多 10 份 |
| `profile_skills` | 画像内的逐项自评（子表，随画像级联删除） |
| `reports` | 分析报告快照 |

设计要点：
1. 技能/维度/岗位用业务 id 做主键（如 `js-core`），seed 可幂等 upsert，不依赖自增号。
2. `job_requirements` 的 (job_id, skill_id) 唯一约束从数据库层杜绝同一技能被重复计权。
3. `any_of` 只描述「可由哪些技能替代满足」，不表示这些技能是独立要求。
4. 画像用子表而非 JSON：看板 6.2 C 要求「profiles 删除时 profile_skills 一并清理」，
   子表能用外键级联保证；JSON 只能靠应用层自觉。
5. 报告存完整 payload 快照，权重调整后历史报告不被改写。
"""

from __future__ import annotations

from decimal import Decimal

from sqlalchemy import (
    JSON,
    ForeignKey,
    Integer,
    Numeric,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, TimestampMixin


class Dimension(Base):
    """能力维度（固定 6 个）。"""

    __tablename__ = "dimensions"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    name: Mapped[str] = mapped_column(String(32), nullable=False)
    short: Mapped[str] = mapped_column(String(16), nullable=False, default="")
    # 列名避开 MySQL 关键字 desc；属性名也避开 SQLAlchemy 的 ColumnOperators.desc()
    description: Mapped[str] = mapped_column("dim_desc", Text, nullable=False, default="")
    why: Mapped[str] = mapped_column(Text, nullable=False, default="")
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    skills: Mapped[list[Skill]] = relationship(back_populates="dimension")


class Skill(Base):
    """技能词典项。aliases 供简历解析用，不进技能列表接口的响应。"""

    __tablename__ = "skills"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(64), nullable=False)
    dim_id: Mapped[str] = mapped_column(
        ForeignKey("dimensions.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    skill_group: Mapped[str] = mapped_column(String(32), nullable=False, default="")
    note: Mapped[str] = mapped_column(Text, nullable=False, default="")
    aliases: Mapped[list] = mapped_column(JSON, nullable=False, default=list)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    dimension: Mapped[Dimension] = relationship(back_populates="skills")


class Job(Base):
    """岗位模型。coreWeightSum / plusWeightSum 不落库，由 requirements 现算，避免两份真相。"""

    __tablename__ = "jobs"

    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    name: Mapped[str] = mapped_column(String(64), nullable=False)
    short_name: Mapped[str] = mapped_column(String(32), nullable=False)
    seniority: Mapped[str] = mapped_column(String(32), nullable=False, default="")
    summary: Mapped[str] = mapped_column(Text, nullable=False, default="")
    notes: Mapped[str] = mapped_column(Text, nullable=False, default="")
    scoring_scope: Mapped[str] = mapped_column(Text, nullable=False, default="")
    data_version: Mapped[str] = mapped_column(String(32), nullable=False, default="示例数据 v0.1")
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    requirements: Mapped[list[JobRequirement]] = relationship(
        back_populates="job", cascade="all, delete-orphan", order_by="JobRequirement.sort_order"
    )


class JobRequirement(Base):
    """岗位对某项技能的要求。type 只有 core / plus 两种取值。"""

    __tablename__ = "job_requirements"
    __table_args__ = (UniqueConstraint("job_id", "skill_id", name="uq_job_skill"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    job_id: Mapped[str] = mapped_column(
        ForeignKey("jobs.id", ondelete="CASCADE"), nullable=False, index=True
    )
    skill_id: Mapped[str] = mapped_column(
        ForeignKey("skills.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    weight: Mapped[int] = mapped_column(Integer, nullable=False)
    target: Mapped[int] = mapped_column(Integer, nullable=False)
    req_type: Mapped[str] = mapped_column(String(8), nullable=False, default="core")
    any_of: Mapped[list | None] = mapped_column(JSON, nullable=True)
    any_of_note: Mapped[str | None] = mapped_column(Text, nullable=True)
    sort_order: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    job: Mapped[Job] = relationship(back_populates="requirements")
    skill: Mapped[Skill] = relationship()


class AppMeta(Base):
    """单行表：数据版本、免责声明、等级锚点。

    这些内容会随数据版本变化、但不需要查询排序。放库里而不是写死在代码，
    是为了避免 seed 数据与常量两处漂移——自评页与报告页永远看到同一份文案。
    """

    __tablename__ = "app_meta"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    data_version: Mapped[str] = mapped_column(String(32), nullable=False)
    disclaimer: Mapped[str] = mapped_column(Text, nullable=False)
    scoring_version: Mapped[str] = mapped_column(String(32), nullable=False, default="v0.2-core-only")
    levels: Mapped[list] = mapped_column(JSON, nullable=False)


class User(TimestampMixin, Base):
    """账号。password_hash 用 bcrypt，任何接口都不得返回该字段。"""

    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    username: Mapped[str] = mapped_column(String(64), nullable=False, unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255), nullable=False)
    role: Mapped[str] = mapped_column(String(16), nullable=False, default="student")


class Profile(TimestampMixin, Base):
    """技能画像。一人最多 10 份（上限由应用层校验，见 api/profiles.py）。"""

    __tablename__ = "profiles"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(64), nullable=False)

    skills: Mapped[list[ProfileSkill]] = relationship(
        back_populates="profile", cascade="all, delete-orphan", order_by="ProfileSkill.id"
    )


class ProfileSkill(Base):
    """画像中的一条自评。level 已夹取到 0–5，写入前由 clamp_level 收敛。"""

    __tablename__ = "profile_skills"
    __table_args__ = (UniqueConstraint("profile_id", "skill_id", name="uq_profile_skill"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    profile_id: Mapped[int] = mapped_column(
        ForeignKey("profiles.id", ondelete="CASCADE"), nullable=False, index=True
    )
    skill_id: Mapped[str] = mapped_column(
        ForeignKey("skills.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    level: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    profile: Mapped[Profile] = relationship(back_populates="skills")
    skill: Mapped[Skill] = relationship()


class Report(TimestampMixin, Base):
    """一次分析报告。payload 存完整 MatchResult 快照。"""

    __tablename__ = "reports"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    job_id: Mapped[str] = mapped_column(
        ForeignKey("jobs.id", ondelete="RESTRICT"), nullable=False, index=True
    )
    profile_id: Mapped[int | None] = mapped_column(
        ForeignKey("profiles.id", ondelete="SET NULL"), nullable=True
    )
    match_score: Mapped[Decimal | None] = mapped_column(Numeric(7, 4), nullable=True)
    payload: Mapped[dict] = mapped_column(JSON, nullable=False)
