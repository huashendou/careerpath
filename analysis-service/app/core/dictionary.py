"""技能词典：进程内只读缓存。

为什么读 JSON 而不是读 MySQL：
1. 词典是**静态种子**（6 类 / 61 技能），随代码一起版本化，改动要过评审；
2. Java 用同一个文件灌库，两边不存在第二份真相；
3. 本服务因此不依赖数据库——库挂了仍能解析 JD，健康检查也不会因为连不上库而变红。
"""

from __future__ import annotations

import json
import logging
import threading
from dataclasses import dataclass, field
from pathlib import Path

from app.core.config import settings
from app.core.errors import DictionaryNotReady

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class Skill:
    """词典中的一项技能。`dim` 是它唯一的所属维度——一个技能只归一维，雷达图才不会重复计数。"""

    id: str
    name: str
    dim: str
    group: str
    aliases: tuple[str, ...]
    note: str


@dataclass(frozen=True)
class Category:
    id: str
    name: str
    short: str
    desc: str
    why: str


@dataclass(frozen=True)
class Dictionary:
    version: str
    disclaimer: str
    categories: tuple[Category, ...]
    skills: tuple[Skill, ...]
    levels: tuple[dict, ...]
    by_id: dict[str, Skill] = field(default_factory=dict)

    @property
    def category_ids(self) -> list[str]:
        return [c.id for c in self.categories]

    def get(self, skill_id: str) -> Skill | None:
        return self.by_id.get(skill_id)


class DictionaryStore:
    """线程安全的懒加载容器。首次访问时读盘，之后常驻内存。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._data: Dictionary | None = None
        self._source: Path | None = None

    @property
    def loaded(self) -> bool:
        return self._data is not None

    def get(self) -> Dictionary:
        if self._data is not None:
            return self._data
        with self._lock:
            if self._data is None:
                self._data = self._load(settings.skills_path)
        return self._data

    def reload(self) -> Dictionary:
        with self._lock:
            self._data = self._load(settings.skills_path)
        return self._data

    def _load(self, path: Path) -> Dictionary:
        if not path.is_file():
            raise DictionaryNotReady(f"技能词典文件不存在：{path}")

        raw = json.loads(path.read_text(encoding="utf-8"))
        categories = tuple(
            Category(
                id=item["id"],
                name=item["name"],
                short=item.get("short", ""),
                desc=item.get("desc", ""),
                why=item.get("why", ""),
            )
            for item in raw.get("dimensions", [])
        )
        skills = tuple(
            Skill(
                id=item["id"],
                name=item["name"],
                dim=item["dim"],
                group=item.get("group", ""),
                aliases=tuple(a for a in item.get("aliases", []) if a),
                note=item.get("note", ""),
            )
            for item in raw.get("skills", [])
        )
        by_id = {s.id: s for s in skills}

        # 启动即校验：技能指向不存在的维度、或 id 重复，都是数据错误，宁可起不来也不要线上算出错分。
        category_ids = {c.id for c in categories}
        unknown_dims = {s.dim for s in skills} - category_ids
        if unknown_dims:
            raise DictionaryNotReady(f"技能引用了不存在的维度：{sorted(unknown_dims)}")
        if len(by_id) != len(skills):
            raise DictionaryNotReady("技能 id 存在重复，词典不合法")

        self._source = path
        logger.info(
            "技能词典已加载：%s 类 / %s 技能（%s）",
            len(categories),
            len(skills),
            path.name,
        )
        meta = raw.get("meta", {})
        return Dictionary(
            version=str(meta.get("version", "")),
            disclaimer=str(meta.get("disclaimer", "")),
            categories=categories,
            skills=skills,
            levels=tuple(raw.get("levels", [])),
            by_id=by_id,
        )


store = DictionaryStore()
