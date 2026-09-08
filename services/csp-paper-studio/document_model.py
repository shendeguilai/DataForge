#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Lossless Markdown blocks used by the CSP Paper Studio compatibility layer.

The V0.4 parser intentionally remains untouched.  This module is a small,
conservative scanner: it never executes Markdown and it never interprets a
delimiter inside a fenced block as a second block.  The parser above it can
therefore decide where a block belongs without losing whitespace or source
locations.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import re
from typing import Iterable


PROGRAM_LANGUAGES = {
    "c", "cc", "c++", "cpp", "cxx", "h", "hpp", "java", "kotlin",
    "go", "golang", "rust", "rs", "pascal", "pas", "python", "py",
    "javascript", "js", "typescript", "ts", "csharp", "cs", "bash",
    "shell", "sh",
}
PREFORMATTED_LANGUAGES = {
    "text": "data", "plain": "data", "plaintext": "data", "txt": "data", "data": "data",
    "input": "input", "stdin": "input", "console": "data",
    "output": "output", "stdout": "output",
}
MATH_LANGUAGES = {"math", "latex", "tex", "katex", "asciimath"}


@dataclass(frozen=True)
class Diagnostic:
    severity: str
    code: str
    line: int
    message: str
    block_id: int | None = None

    def as_dict(self) -> dict:
        return {
            "severity": self.severity,
            "code": self.code,
            "line": self.line,
            "message": self.message,
        }


@dataclass
class Block:
    kind: str
    role: str
    language: str = ""
    content: str = ""
    source_start_line: int = 1
    source_end_line: int = 1
    fenced: bool = False
    info: str = ""
    block_id: int = -1
    # Keep the delimiter kind for routing.  The public JSON model does not
    # need to expose it, but the legacy fast path must distinguish its plain
    # ```cpp opener from an otherwise equivalent ~~~cpp block.
    fence_marker: str = ""

    def as_dict(self) -> dict:
        value = {
            "type": "text" if self.kind == "paragraph" else self.kind,
            "role": self.role,
            "language": self.language,
            "content": self.content,
            "source_start_line": self.source_start_line,
            "source_end_line": self.source_end_line,
        }
        # Older clients use ``text`` and ``code`` fields.  The compatibility
        # service includes both names so an upgraded page can render a block
        # while an old page can still show its text.
        if self.kind == "paragraph":
            value["text"] = self.content
        if self.kind == "code":
            value["code_text"] = self.content
        return value


@dataclass
class ChoiceItem:
    blocks: list[Block] = field(default_factory=list)
    options: list[str] = field(default_factory=list)

    @property
    def text(self) -> str:
        return " ".join(x.content.strip() for x in self.blocks if x.kind == "paragraph").strip()


@dataclass
class Module:
    number: int
    kind: str
    body: str
    body_start_line: int
    blocks: list[Block] = field(default_factory=list)
    options: list[str] = field(default_factory=list)
    code: Block | None = None
    note_blocks: list[Block] = field(default_factory=list)
    judges: list[ChoiceItem] = field(default_factory=list)
    choices: list[ChoiceItem] = field(default_factory=list)
    groups: list[ChoiceItem] = field(default_factory=list)
    prose_blocks: list[Block] = field(default_factory=list)
    errors: list[Diagnostic] = field(default_factory=list)
    warnings: list[Diagnostic] = field(default_factory=list)
    formula_count: int = 0

    def all_rendered_blocks(self) -> list[Block]:
        result: list[Block] = []
        result.extend(self.blocks)
        result.extend(self.note_blocks)
        result.extend(self.prose_blocks)
        if self.code is not None:
            result.append(self.code)
        for item in [*self.judges, *self.choices, *self.groups]:
            result.extend(item.blocks)
        seen: set[int] = set()
        unique: list[Block] = []
        for block in result:
            identity = id(block)
            if identity in seen:
                continue
            seen.add(identity)
            unique.append(block)
        return unique


@dataclass
class DocumentModel:
    markdown: str
    preamble: str
    metadata: dict
    detected_format: str
    modules: list[Module]
    diagnostics: list[Diagnostic] = field(default_factory=list)
    source_blocks: int = 0
    rendered_blocks: int = 0
    dropped_blocks: int = 0

    @property
    def errors(self) -> list[Diagnostic]:
        return [x for x in self.diagnostics if x.severity == "error"]

    @property
    def warnings(self) -> list[Diagnostic]:
        return [x for x in self.diagnostics if x.severity == "warning"]

    def diagnostics_dict(self) -> list[dict]:
        return [x.as_dict() for x in self.diagnostics]

    def coverage(self) -> dict:
        return {
            "source_blocks": self.source_blocks,
            "rendered_blocks": self.rendered_blocks,
            "dropped_blocks": self.dropped_blocks,
        }


def _line_indent(line: str) -> int:
    # Tabs count as one indentation column for the purpose of stripping a
    # list's common prefix.  The original tabs inside a code block remain.
    return len(line) - len(line.lstrip(" \t"))


def _strip_opening_indent(line: str, indent: int) -> str:
    if indent <= 0:
        return line
    remaining = indent
    pos = 0
    while pos < len(line) and remaining and line[pos] in " \t":
        pos += 1
        remaining -= 1
    return line[pos:]


def _fence_open(line: str):
    # CommonMark permits up to three spaces before a fence.  Exporters often
    # indent it by one or more list levels (four, eight, ... spaces), so the
    # compatibility scanner accepts any leading whitespace.  A line that
    # starts with a fence is unambiguously intended as a fenced block here;
    # code/data inside an already-open block is never passed through this
    # function.
    match = re.match(r"^(?P<indent>[ \t]*)(?P<mark>`{3,}|~{3,})[ \t]*(?P<info>[^\r\n]*)\r?\n?$", line)
    if not match:
        return None
    info = match.group("info").strip()
    return match.group("indent"), match.group("mark"), info


def _fence_close(line: str, marker: str, indent: str) -> bool:
    # A closing fence must use the same marker character and be at least as
    # long as the opener.  Text after it is deliberately not accepted, which
    # prevents a prose line containing backticks from terminating a block.
    pattern = rf"^[ \t]{{0,{max(4, len(indent))}}}{re.escape(marker[0])}{{{len(marker)},}}[ \t]*\r?\n?$"
    return re.match(pattern, line) is not None


def _classify(info: str):
    token = (info.split(None, 1)[0] if info else "").strip().lower()
    if token in PROGRAM_LANGUAGES:
        return "code", "program", token
    if token in PREFORMATTED_LANGUAGES:
        return "preformatted", PREFORMATTED_LANGUAGES[token], token
    if token in MATH_LANGUAGES:
        return "math", "expression", token
    return "preformatted", "data", token


def scan_blocks(text: str, start_line: int = 1, *, diagnostics: list[Diagnostic] | None = None) -> list[Block]:
    """Scan Markdown into lossless paragraph/fenced blocks.

    ``content`` keeps every character inside a fence (apart from the common
    list indentation and line-ending normalization).  Paragraphs are grouped
    by blank lines; their internal line breaks are kept for the parser to
    decide whether they are prose or a list item.
    """
    diagnostics = diagnostics if diagnostics is not None else []
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    if normalized.startswith("\ufeff"):
        # A BOM is an encoding marker, not part of the first Markdown line.
        # Removing it here also lets a BOM-prefixed fence be recognized when
        # the scanner is used directly (rather than through split_sections).
        normalized = normalized[1:]
    lines = normalized.splitlines(keepends=True)
    blocks: list[Block] = []
    paragraph_lines: list[str] = []
    paragraph_start = 0
    next_id = 0

    def flush_paragraph(end_index: int):
        nonlocal paragraph_lines, paragraph_start, next_id
        if not paragraph_lines:
            return
        content = "".join(paragraph_lines).strip("\n")
        if content.strip():
            block = Block(
                kind="paragraph", role="prose", content=content,
                source_start_line=start_line + paragraph_start,
                source_end_line=start_line + end_index - 1,
                block_id=next_id,
            )
            blocks.append(block)
            next_id += 1
        paragraph_lines = []

    i = 0
    while i < len(lines):
        opening = _fence_open(lines[i])
        if opening:
            flush_paragraph(i)
            indent, marker, info = opening
            content_lines: list[str] = []
            opening_line = start_line + i
            close_index: int | None = None
            j = i + 1
            while j < len(lines):
                if _fence_close(lines[j], marker, indent):
                    close_index = j
                    break
                content_lines.append(_strip_opening_indent(lines[j], len(indent)))
                j += 1
            if close_index is None:
                # Keep the content instead of discarding it.  The diagnostic
                # blocks export until the user closes the fence.
                close_index = len(lines) - 1 if lines else i
                diagnostics.append(Diagnostic(
                    "error", "UNCLOSED_FENCE", opening_line,
                    "代码/预格式围栏未闭合，请补充对应的结束围栏。", next_id,
                ))
            kind, role, language = _classify(info)
            content = "".join(content_lines)
            if content.endswith("\n"):
                content = content[:-1]
            if not content.strip():
                diagnostics.append(Diagnostic(
                    "warning", "EMPTY_FENCE", opening_line,
                    "围栏内容为空；如果这里应有输入样例，请将数据放在开始与结束围栏之间。", next_id,
                ))
            block = Block(
                kind=kind, role=role, language=language, content=content,
                source_start_line=opening_line,
                source_end_line=start_line + close_index,
                fenced=True, info=info, block_id=next_id, fence_marker=marker,
            )
            blocks.append(block)
            if language not in PROGRAM_LANGUAGES | set(PREFORMATTED_LANGUAGES) | MATH_LANGUAGES:
                diagnostics.append(Diagnostic(
                    "warning", "UNKNOWN_FENCE", opening_line,
                    f"未知围栏语言“{info or '未声明'}”，已按原文预格式保留。", next_id,
                ))
            next_id += 1
            i = close_index + 1
            paragraph_start = i
            if close_index >= len(lines) - 1 and j >= len(lines):
                break
            continue

        if not lines[i].strip():
            flush_paragraph(i)
            paragraph_start = i + 1
        else:
            if not paragraph_lines:
                paragraph_start = i
            paragraph_lines.append(lines[i])
        i += 1
    flush_paragraph(len(lines))
    return blocks


def iter_fenced_blocks(blocks: Iterable[Block]):
    return (block for block in blocks if block.fenced)
