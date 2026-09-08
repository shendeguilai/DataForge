#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Compatibility parser for the V0.4 CSP Markdown dialect and format 1.

This adapter deliberately sits next to the original parser.  It accepts the
loose Markdown exported by problem sites, but keeps every fenced block as a
typed object so that the Word renderer never has to guess whether ``text`` is
code, prose, or input data.
"""

from __future__ import annotations

from dataclasses import replace
import re
from typing import Iterable

from converter import ConvertError, choose_option_shape, normalize_math_tex, parse_meta
from document_model import (
    Block,
    ChoiceItem,
    Diagnostic,
    DocumentModel,
    Module,
    scan_blocks,
    PROGRAM_LANGUAGES,
    _fence_close,
    _fence_open,
)


_SECTION_RE = re.compile(r"^##\s*第\s*(\d+)\s*题\s*$", re.M)
_HEADING_RE = re.compile(r"^\s*#{1,6}\s*(.*?)\s*$")
_LIST_RE = re.compile(r"^(?P<indent>[ \t]*)(?P<marker>(?:[-*+]\s+|\d+[.)]\s+))(?P<text>.*)$")
_NUMERIC_GROUP_RE = re.compile(r"^\s*(?:[-*]\s*)?(\d+)[.)]?\s*$")
_DUP_GROUP_RE = re.compile(r"^\s*[-*]\s*(\d+)\.\s*$", re.M)
# Most sources use ``A.``; accepting the common Chinese/parenthesized
# separators here costs nothing and makes the compatibility path tolerant of
# ``A、``/``A)`` lists exported by other Markdown editors.
_OPTION_START_RE = re.compile(
    r"^(?P<prefix>[ \t]*(?:[-*+]\s*)?)(?P<letter>[A-D])(?:\.\s*|[．、)）]\s*)"
)
_OPTION_INLINE_RE = re.compile(r"(?<!\S)([B-D])(?:\.\s*|[．、)）]\s*)")


def _mask_fenced_regions(text: str) -> str:
    """Return *text* with fenced regions replaced by spaces.

    The legacy format frequently puts the question options in a second list
    after a program or an input example.  Looking for ``A.``/``1.`` directly
    in the raw text can therefore mistake a line in a code/data block for an
    option or a new question.  Keeping newlines and character offsets intact
    lets callers use positions from the masked text to slice the original.

    ``scan_blocks`` is intentionally used as the single source of truth for
    fence syntax (backticks, tildes, indented list fences and unclosed
    fences).  This helper does not add diagnostics because the owning parse
    call already scans the same fragment with its diagnostic sink.
    """
    if not text:
        return text
    lines = text.splitlines(keepends=True)
    if not lines:
        return text
    offsets = [0]
    for line in lines:
        offsets.append(offsets[-1] + len(line))
    masked = list(text)
    for block in scan_blocks(text, 1, diagnostics=[]):
        if not block.fenced:
            continue
        start = max(0, min(len(lines), block.source_start_line - 1))
        end = max(start, min(len(lines), block.source_end_line))
        for index in range(start, end):
            begin, finish = offsets[index], offsets[index + 1]
            for position in range(begin, finish):
                if masked[position] not in "\r\n":
                    masked[position] = " "
    return "".join(masked)


def _cut_duplicate_tail(text: str) -> str:
    """Cut the detached answer list without seeing ``- 1.`` in a fence."""
    masked = _mask_fenced_regions(text)
    match = re.search(r"(?m)^\s*[-*]\s*1\.\s*$", masked)
    return text[:match.start()] if match else text


def _line_number(text: str, offset: int, start_line: int) -> int:
    return start_line + text[:offset].count("\n")


def _fragment_blocks(fragment: str, body_start_line: int, body: str, offset: int, diagnostics: list[Diagnostic]) -> list[Block]:
    return scan_blocks(fragment, _line_number(body, offset, body_start_line), diagnostics=diagnostics)


def parse_front_matter(markdown: str):
    """Return (metadata, format, end_offset), accepting a small YAML subset."""
    text = markdown.replace("\r\n", "\n").replace("\r", "\n")
    if text.startswith("\ufeff"):
        text = text[1:]
    lines = text.splitlines(keepends=True)
    if not lines:
        return {}, "legacy-v04", 0
    opening = _fence_open(lines[0])
    fenced = opening is not None and opening[2].lower() in {"", "yaml", "yml"}
    if not fenced and not re.match(r"^---[ \t]*\n", text):
        return {}, "legacy-v04", 0
    end = None
    for i, line in enumerate(lines[1:], 1):
        if (_fence_close(line, opening[1], opening[0]) if fenced
                else re.match(r"^---[ \t]*(?:\n|$)", line)):
            end = i
            break
    if end is None:
        return {}, "legacy-v04", 0
    values = {}
    allowed_keys = {"csp_format", "title", "year", "group", "numbering"}
    for line in lines[1:end]:
        if not line.strip():
            continue
        m = re.match(r"^[ \t]*([A-Za-z_][\w-]*)[ \t]*:[ \t]*(.*?)\s*$", line.rstrip("\n"))
        if not m:
            if fenced:
                return {}, "legacy-v04", 0
            continue
        value = m.group(2).strip().strip("'\"")
        key = m.group(1).lower().replace("-", "_")
        # A real program/data block must never be consumed as metadata.
        # Only a complete, explicitly declared, known-key header qualifies.
        if fenced and (key not in allowed_keys or key in values):
            return {}, "legacy-v04", 0
        values[key] = value
    if fenced and values.get("csp_format") != "1":
        return {}, "legacy-v04", 0
    detected = "standard-v1" if values.get("csp_format") == "1" else "legacy-v04"
    return values, detected, sum(len(x) for x in lines[:end + 1])


def split_sections(markdown: str):
    """Split top-level ``## 第 n 题`` headings, retaining source line starts."""
    text = markdown.replace("\r\n", "\n").replace("\r", "\n")
    # UTF-8 files without YAML front matter may still carry a BOM immediately
    # before their first question heading.  It is metadata, not part of the
    # Markdown heading, so remove it before the fence-aware scan.
    if text.startswith("\ufeff"):
        text = text[1:]
    matches = []
    in_fence = None
    offset = 0
    for line in text.splitlines(keepends=True):
        value = line.rstrip("\n")
        if in_fence is not None:
            if _fence_close(line, in_fence[1], in_fence[0]):
                in_fence = None
            offset += len(line)
            continue
        opening = _fence_open(line)
        if opening:
            in_fence = (opening[0], opening[1])
            offset += len(line)
            continue
        # CommonMark permits up to three leading spaces; exporters sometimes
        # retain a deeper list indentation when a complete paper is nested in
        # a quote/list.  Fences have already been excluded above, so accepting
        # horizontal indentation here is unambiguous and keeps those modules
        # addressable without changing their source line numbers.
        heading = re.match(r"^[ \t]*##\s*第\s*(\d+)\s*题\s*$", value)
        if heading:
            matches.append((offset, len(value), int(heading.group(1))))
        offset += len(line)
    if not matches:
        return text, []
    preamble = text[:matches[0][0]]
    sections = []
    for index, (start, heading_len, number) in enumerate(matches):
        end = matches[index + 1][0] if index + 1 < len(matches) else len(text)
        sections.append({
            "number": number,
            "body": text[start + heading_len:end],
            "body_start_line": text[:start + heading_len].count("\n") + 1,
            "heading_line": text[:start].count("\n") + 1,
        })
    return preamble, sections


def _clean_heading_paragraph(block: Block, number: int | None = None, *, strip_bullets: bool = False) -> Block | None:
    if block.kind != "paragraph":
        return block
    kept = []
    for line in block.content.splitlines():
        value = line.strip()
        if not value:
            continue
        if value == "-" or re.fullmatch(r"[-*_](?:\s*[-*_]){2,}", value):
            continue
        if _HEADING_RE.match(value):
            heading = _HEADING_RE.match(value).group(1).strip()
            if re.match(r"^(?:一|二|三)[、.]", heading) or re.match(r"^第\s*\d+\s*题$", heading):
                continue
            # A canonical field heading belongs to the parser, not the Word
            # stem.  Unknown headings are kept as ordinary prose.
            if re.match(r"^(?:类型|题干|选项|程序|说明|判断题|单选题|选择题|小题)\s*[:：]?", heading):
                continue
        if re.match(r"^本题共\s*", value):
            continue
        if strip_bullets:
            value = re.sub(r"^[-*+]\s+", "", value)
        if number is not None:
            value = re.sub(rf"^第\s*{number}\s*题\s*", "", value, count=1).strip()
        if value:
            kept.append(value)
    if not kept:
        return None
    return replace(block, content=" ".join(kept))


def clean_blocks(blocks: Iterable[Block], number: int | None = None, *, strip_bullets: bool = False) -> list[Block]:
    result = []
    for block in blocks:
        cleaned = _clean_heading_paragraph(block, number, strip_bullets=strip_bullets)
        if cleaned is not None:
            result.append(cleaned)
    return result


def _option_records(text: str):
    """Extract A-D labels, including several labels on one Markdown line.

    Markdown lists permit an option's value to continue on indented lines::

        - A. first line
          second line

    The old V0.4 line parser only kept ``first line``.  Keep the structural
    indentation out of the value, but retain the newline (and any meaningful
    spaces after that indentation) so an option can be rendered without
    silently losing text.  Continuation scanning is intentionally performed
    only between two recognised option labels; fenced regions have already
    been masked, so labels in input/code cannot terminate an option.
    """
    records = []
    lines = text.splitlines(keepends=True)
    masked_lines = _mask_fenced_regions(text).splitlines(keepends=True)
    offset = 0
    for line_no, line in enumerate(lines):
        # Match against the masked line, but read option text from the source
        # line.  This keeps offsets stable and prevents a code/input line such
        # as ``A. 1`` from becoming the stem's first option.
        masked_plain = masked_lines[line_no].rstrip("\r\n") if line_no < len(masked_lines) else ""
        plain = line.rstrip("\r\n")
        match_plain = masked_plain
        first = _OPTION_START_RE.match(match_plain)
        if not first:
            offset += len(line)
            continue
        labels = [(first, first.start("letter"), first.end())]
        # A second label is only accepted after whitespace; this avoids
        # splitting decimal values or prose such as “A. 1.5”.
        for match in _OPTION_INLINE_RE.finditer(match_plain[first.end():]):
            labels.append((match, first.end() + match.start(1), first.end() + match.end()))
        positions = [x[1] for x in labels]
        ends = positions[1:] + [len(plain)]
        for (label, start, label_end), end in zip(labels, ends):
            letter = label.group("letter") if label is first else label.group(1)
            content_start = label_end
            content = plain[content_start:end].strip()
            records.append({
                "letter": letter,
                "text": content,
                "line": line_no,
                "offset": offset + start,
                "line_offset": start,
            })
        offset += len(line)

    if not records:
        return records

    # Expand only records whose next label is on a later source line.  When
    # several labels share a line, the existing same-line slicing remains the
    # most reliable interpretation and there is no intervening continuation
    # region to attach.
    for index, record in enumerate(records):
        line_index = record["line"]
        next_line = records[index + 1]["line"] if index + 1 < len(records) else len(lines)
        if next_line <= line_index + 1:
            continue

        continuation = []
        for candidate in lines[line_index + 1:next_line]:
            value = candidate.rstrip("\r\n")
            stripped = value.strip()
            if not stripped:
                # Blank lines are meaningful only between two continuation
                # lines.  Do not make a trailing empty line part of every
                # option merely because the list is separated by a blank.
                if continuation and continuation[-1] != "":
                    continuation.append("")
                continue

            # A heading or another non-option list item marks a new Markdown
            # construct rather than an option continuation.  This can happen
            # in legacy sections where the score line follows the options.
            if re.match(r"^[ \t]*#{1,6}(?:[ \t]|$)", value):
                break
            if re.match(r"^[ \t]*(?:[-*+]\s+|\d+[.)]\s+)", value):
                break
            if re.match(r"^[ \t]*本题共\s*", value):
                break

            # The normal Markdown spelling is indented continuation text.
            # Also accept a non-indented line when it is directly adjacent to
            # the option: exporters frequently emit wrapped lines without
            # preserving list indentation.  Once a blank separator occurred,
            # require indentation to avoid swallowing a following paragraph.
            indented = bool(re.match(r"^[ \t]+", value))
            if continuation and continuation[-1] == "" and not indented:
                break
            normalized = value.lstrip(" \t") if indented else value
            continuation.append(normalized)

        while continuation and continuation[-1] == "":
            continuation.pop()
        if continuation:
            base = record["text"]
            record["text"] = "\n".join(([base] if base else []) + continuation)
    return records


def _first_option_sequence(text: str):
    records = _option_records(text)
    for i, record in enumerate(records):
        if record["letter"] != "A":
            continue
        sequence = records[i:i + 4]
        if [x["letter"] for x in sequence] == list("ABCD"):
            return sequence
    return []


def _cut_before_record(text: str, record: dict) -> str:
    return text[:record["offset"]]


def _option_values(sequence):
    return [x["letter"] + ". " + x["text"] for x in sequence]


def _split_list_items(text: str, body_start_line: int):
    """Split top-level bullets/numbered items while retaining nested fences."""
    lines = text.splitlines(keepends=True)
    masked_lines = _mask_fenced_regions(text).splitlines(keepends=True)
    starts = []
    offset = 0
    for index, line in enumerate(lines):
        masked_line = masked_lines[index] if index < len(masked_lines) else ""
        m = _LIST_RE.match(masked_line.rstrip("\r\n"))
        if m:
            # Use the source text for the value check, while keeping the
            # marker shape/indent from the masked line.  A data line inside a
            # fenced input block is otherwise indistinguishable from a
            # nested Markdown list when only regular expressions are used.
            source_line = line.rstrip("\r\n")
            marker_end = m.end("marker")
            value = source_line[marker_end:].strip()
            if value and not re.match(r"^[A-D]\.\s*", value):
                starts.append((index, offset, m))
        offset += len(line)
    result = []
    pos = 0
    while pos < len(starts):
        index, offset, match = starts[pos]
        # A nested list belongs to the current item; only a marker with equal
        # or smaller indentation starts the next item.
        next_pos = pos + 1
        while next_pos < len(starts) and len(starts[next_pos][2].group("indent")) > len(match.group("indent")):
            next_pos += 1
        end_index = starts[next_pos][0] if next_pos < len(starts) else len(lines)
        raw = "".join(lines[index:end_index])
        first_line = lines[index]
        first_content = first_line[first_line.find(match.group("marker")) + len(match.group("marker")):]
        raw = first_content + "".join(lines[index + 1:end_index])
        result.append({
            "raw": raw,
            "offset": offset + len(match.group("indent")) + len(match.group("marker")),
            "line": body_start_line + index,
            "indent": len(match.group("indent")),
        })
        pos = next_pos
    return result


def _line_start_offset(text: str, absolute_line: int, start_line: int) -> int:
    """Offset of a line in a section body whose first line is start_line."""
    index = max(0, absolute_line - start_line)
    if index == 0:
        return 0
    starts = [m.end() for m in re.finditer(r"\n", text)]
    return starts[index - 1] if index - 1 < len(starts) else len(text)


def _line_end_offset(text: str, absolute_line: int, start_line: int) -> int:
    start = _line_start_offset(text, absolute_line, start_line)
    end = text.find("\n", start)
    return len(text) if end < 0 else end + 1


def _parse_item(item: dict, body: str, body_start_line: int, diagnostics: list[Diagnostic], *, number: int | None = None, strip_bullets: bool = False) -> ChoiceItem:
    raw = item["raw"]
    sequence = _first_option_sequence(raw)
    if sequence:
        first_offset = sequence[0]["offset"]
        stem = raw[:first_offset]
        blocks = _fragment_blocks(stem, item["line"], raw, 0, diagnostics)
        options = _option_values(sequence)
    else:
        blocks = _fragment_blocks(raw, item["line"], raw, 0, diagnostics)
        options = []
    blocks = clean_blocks(blocks, number, strip_bullets=strip_bullets)
    return ChoiceItem(blocks=blocks, options=options)


def _duplicate_groups(text: str):
    masked = _mask_fenced_regions(text)
    match = _DUP_GROUP_RE.search(masked)
    if not match:
        return []
    tail = text[match.start():]
    tail_masked = masked[match.start():]
    starts = list(_DUP_GROUP_RE.finditer(tail_masked))
    output = []
    for index, current in enumerate(starts):
        end = starts[index + 1].start() if index + 1 < len(starts) else len(tail)
        group_text = tail[current.end():end]
        output.append((int(current.group(1)), _option_values(_first_option_sequence(group_text))))
    return output


def _find_heading(text: str, names: str, start: int = 0):
    # A prose line inside an unknown/input fence can look exactly like a
    # section heading.  Match on the fence-masked text and use only spaces or
    # tabs around the heading so masked newlines cannot be swallowed.
    masked = _mask_fenced_regions(text)
    return re.search(rf"(?mi)^[ \t]*(?:#{{1,6}}[ \t]*)?(?:{names})[ \t]*$", masked[start:])


def _named_sections(text: str):
    # The documented template uses ``###`` while several exporters emit
    # ``####`` for the same fields.  Treat both as named fields; top-level
    # ``## 第 n 题`` headings have already been removed by ``split_sections``.
    masked = _mask_fenced_regions(text)
    # Do not use ``\s*`` around the heading: ``\s`` includes newlines, and
    # the fence masker turns a code block into spaces.  A permissive pattern
    # would then let a heading match start several lines before the real
    # heading, truncating the preceding fenced block from its section value.
    matches = list(re.finditer(r"(?mi)^[ \t]*#{3,6}[ \t]*(.+?)[ \t]*$", masked))
    out = {}
    for index, match in enumerate(matches):
        # The match came from a same-length masked string, so its offsets can
        # safely recover the original heading text and body slice.
        title = text[match.start(1):match.end(1)].strip()
        title = re.sub(r"^类型\s*[:：]\s*", "类型：", title)
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        out.setdefault(title, text[match.end():end])
    return out


def _section_value(named: dict, *names: str):
    for key, value in named.items():
        normalized = re.sub(r"\s+", "", key).replace(":", "：")
        for name in names:
            if name in normalized:
                return value
    return None


def _parse_choice(number: int, body: str, body_start_line: int, standard: bool, diagnostics: list[Diagnostic]) -> Module:
    module = Module(number, "choice", body, body_start_line)
    named = _named_sections(body) if standard else {}
    source = _section_value(named, "题干") if standard else None
    option_source = _section_value(named, "选项") if standard else None
    source_offset = body.find(source) if source is not None else 0
    if source is None:
        source = body.split("本题共", 1)[0]
    # Some Markdown exporters put the score line before the options while
    # others put it after them.  Scan the complete section when no explicit
    # ``### 选项`` field exists; fenced code/data is masked by
    # ``_option_records`` so labels inside a block cannot win the search.
    options_text = option_source if option_source is not None else body
    sequence = _first_option_sequence(options_text)
    if not sequence:
        raise ConvertError(f"第 {number} 题没有找到连续的 A-D 选项。")
    opts = _option_values(sequence)
    if option_source is None:
        # In legacy Markdown, the A label may be on the same line as the end
        # of the stem.  Cutting by its absolute offset preserves that prefix.
        absolute = sequence[0]["offset"]
        source = body[:absolute]
    stem_blocks = _fragment_blocks(source, body_start_line, body, source_offset, diagnostics)
    stem_blocks = clean_blocks(stem_blocks, number)
    if not stem_blocks:
        raise ConvertError(f"第 {number} 题没有找到题干。")
    module.blocks = stem_blocks
    module.options = opts
    return module


def _parse_reading(number: int, body: str, body_start_line: int, standard: bool, diagnostics: list[Diagnostic]) -> Module:
    module = Module(number, "reading", body, body_start_line)
    blocks = scan_blocks(body, body_start_line, diagnostics=diagnostics)
    code = next((x for x in blocks if x.kind == "code"), None)
    if code is None:
        raise ConvertError(f"第 {number} 题没有识别到程序代码块。")
    module.code = code
    named = _named_sections(body) if standard else {}
    if standard:
        note_source = _section_value(named, "说明") or ""
        judge_source = _section_value(named, "判断题") or ""
        choice_source = _section_value(named, "单选题", "选择题") or ""
        note_offset = body.find(note_source) if note_source else 0
        judge_offset = body.find(judge_source) if judge_source else 0
        choice_offset = body.find(choice_source) if choice_source else 0
    else:
        # The detached answer list starts at ``- 1.``.  Use the fence-aware
        # cutter so a program/data line with that spelling remains part of
        # the question body.
        predup = _cut_duplicate_tail(body)
        code_end = _line_end_offset(body, code.source_end_line, body_start_line)
        jh_base = code_end
        jh = _find_heading(predup, r"判断题", jh_base)
        if not jh:
            raise ConvertError(f"第 {number} 题没有识别到独立的“判断题”标题。")
        jh_abs_start = jh_base + jh.start()
        jh_abs_end = jh_base + jh.end()
        sh_base = jh_abs_end
        sh = _find_heading(predup, r"(?:单选题|选择题)", sh_base)
        if not sh:
            raise ConvertError(f"第 {number} 题没有识别到独立的“单选题”标题。")
        sh_abs_start = sh_base + sh.start()
        sh_abs_end = sh_base + sh.end()
        j_start = jh_abs_end
        j_end = sh_abs_start
        s_start = sh_abs_end
        judge_source = predup[j_start:j_end]
        choice_source = predup[s_start:]
        note_source = predup[code_end:jh_abs_start]
        note_offset, judge_offset, choice_offset = code_end, j_start, s_start

    module.note_blocks = clean_blocks(
        _fragment_blocks(note_source, body_start_line, body, note_offset, diagnostics)
    )
    judge_items = _split_list_items(judge_source, _line_number(body, judge_offset, body_start_line))
    choice_items = _split_list_items(choice_source, _line_number(body, choice_offset, body_start_line))
    if not judge_items:
        raise ConvertError(f"第 {number} 题没有解析到判断题。")
    if not choice_items:
        raise ConvertError(f"第 {number} 题没有解析到单选题。")
    module.judges = [_parse_item(x, body, body_start_line, diagnostics) for x in judge_items]
    module.choices = [_parse_item(x, body, body_start_line, diagnostics) for x in choice_items]

    duplicates = _duplicate_groups(body)
    expected_items = len(module.judges) + len(module.choices)
    if duplicates and [item[0] for item in duplicates] != list(range(1, expected_items + 1)):
        module.errors.append(Diagnostic(
            "error", "SUBQUESTION_MISMATCH",
            _line_number(body, choice_offset, body_start_line),
            f"第 {number} 题识别到 {expected_items} 个小题，但独立选项列表的数量或编号不匹配。"
            "请检查输入样例的围栏是否吞入下一小题；已阻止导出，避免选项错配。",
        ))
    for index, choice in enumerate(module.choices):
        if module.errors:
            break
        if choice.options:
            continue
        dup_index = len(module.judges) + index
        if dup_index < len(duplicates) and len(duplicates[dup_index][1]) == 4:
            choice.options = duplicates[dup_index][1]
    if not module.errors and any(len(x.options) != 4 for x in module.choices):
        raise ConvertError(f"第 {number} 题有单选题缺少完整的 A-D 选项。")
    module.blocks = [module.code, *module.note_blocks]
    for item in [*module.judges, *module.choices]:
        module.blocks.extend(item.blocks)
    return module


def _parse_fill(number: int, body: str, body_start_line: int, standard: bool, diagnostics: list[Diagnostic]) -> Module:
    module = Module(number, "fill", body, body_start_line)
    blocks = scan_blocks(body, body_start_line, diagnostics=diagnostics)
    code = next((x for x in blocks if x.kind == "code"), None)
    if code is None:
        raise ConvertError(f"第 {number} 题没有识别到程序代码块。")
    module.code = code
    code_pos = body.find(code.content)
    code_start = _line_start_offset(body, code.source_start_line, body_start_line)
    pre = body[:code_start]
    module.prose_blocks = clean_blocks(
        _fragment_blocks(pre, body_start_line, body, 0, diagnostics), number, strip_bullets=True
    )
    named = _named_sections(body) if standard else {}
    if standard:
        group_source = _section_value(named, "小题") or ""
        group_offset = body.find(group_source) if group_source else code_pos + len(code.content)
    else:
        post_start = _line_end_offset(body, code.source_end_line, body_start_line)
        post = body[post_start:]
        post = _cut_duplicate_tail(post)
        group_source = post
        group_offset = post_start
    items = _split_list_items(group_source, _line_number(body, group_offset, body_start_line))
    # Legacy exports use “- ①处应填” rather than numbered Markdown.  The
    # generic list splitter already retains those bullets; filter explanatory
    # lines only when they are not a fill marker.
    items = [x for x in items if re.search(r"(?:处应填|填空|\b[①②③④⑤]\b)", x["raw"]) or standard]
    duplicates = _duplicate_groups(body)
    if not items and len(duplicates) >= 5:
        items = [{"raw": f"{mark} 处应填（ ）", "line": body_start_line, "offset": 0}
                 for mark in "①②③④⑤"]
    module.groups = [_parse_item(x, body, body_start_line, diagnostics, strip_bullets=True) for x in items[:5]]
    for index, group in enumerate(module.groups):
        if not group.options and index < len(duplicates) and len(duplicates[index][1]) == 4:
            group.options = duplicates[index][1]
    if len(module.groups) != 5:
        raise ConvertError(f"第 {number} 题应有 5 个填空小题，实际解析到 {len(module.groups)} 个。")
    if any(len(x.options) != 4 for x in module.groups):
        raise ConvertError(f"第 {number} 题有填空小题缺少完整的 A-D 选项。")
    module.blocks = [*module.prose_blocks, module.code]
    for group in module.groups:
        module.blocks.extend(group.blocks)
    return module


def _formula_tokens(text: str):
    # Inline code is prose in the Markdown model, but its dollar signs are
    # literal (for example ```price=$5```); mask it before looking for TeX.
    # Newlines and offsets are retained so diagnostics can still point to the
    # original source block.
    math_source = re.sub(
        r"`[^`\n]*`",
        lambda match: "".join("\n" if ch == "\n" else " " for ch in match.group(0)),
        text,
    )
    # Parse all delimiter forms in one pass.  Running one regex per form can
    # accidentally discover a second, spurious inline formula inside
    # ``$$display $ math$$`` because the single-dollar pass starts halfway
    # through a display delimiter.
    token_re = re.compile(
        r"(?s)(?<!\\)\$\$(.+?)(?<!\\)\$\$"
        r"|(?<!\\)\\\[(.+?)(?<!\\)\\\]"
        r"|(?<!\\)\$([^$\n]+?)(?<!\\)\$"
        r"|(?<!\\)\\\(([^\n]+?)(?<!\\)\\\)"
    )
    tokens = []
    for match in token_re.finditer(math_source):
        raw = next((group for group in match.groups() if group is not None), "")
        tokens.append(normalize_math_tex(raw))
    return [x for x in tokens if x]


def strip_math_delimiters(value: str) -> str:
    r"""Remove one optional outer delimiter pair from a math-fence body.

    The canonical template documents raw TeX inside `````math```, but users
    often paste a display expression including ``$$...$$`` or ``\[...\]``.
    Treating that wrapper as part of the TeX would make Pandoc reject the
    expression (and the renderer would double-wrap it), so normalize only one
    matching outer pair.  Inner dollar signs remain untouched.
    """
    value = str(value).strip()
    pairs = (("$$", "$$"), (r"\[", r"\]"), ("$", "$"), (r"\(", r"\)"))
    for opening, closing in pairs:
        if value.startswith(opening) and value.endswith(closing) and len(value) >= len(opening) + len(closing):
            return value[len(opening):len(value) - len(closing)].strip()
    return value


def formulas_in_blocks(blocks: Iterable[Block]):
    out, seen = [], set()
    for block in blocks:
        if block.kind == "code" or block.kind == "preformatted":
            continue
        values = [block.content] if block.kind == "math" else [block.content]
        for value in values:
            formulas = [normalize_math_tex(strip_math_delimiters(value))] if block.kind == "math" else _formula_tokens(value)
            for formula in formulas:
                if formula and formula not in seen:
                    seen.add(formula)
                    out.append(formula)
    return out


def unclosed_math_diagnostics(blocks: Iterable[Block]):
    """Report explicit math delimiters that have no matching close.

    Markdown prose may contain ordinary dollar signs, so this deliberately
    reports only an *unbalanced* delimiter.  Complete formulas are skipped by
    the scanner, and code/preformatted blocks are never inspected.  Pandoc
    remains the authority for TeX validity during export; this early check
    gives the editor a useful source-line diagnostic before that step.
    """
    diagnostics: list[Diagnostic] = []
    for block in blocks:
        if block.kind in {"code", "preformatted"}:
            continue
        value = block.content
        if block.kind == "math":
            value = strip_math_delimiters(value)
        # Inline code is literal text, including any dollar signs it carries.
        value = re.sub(
            r"`[^`\n]*`",
            lambda match: "".join("\n" if char == "\n" else " " for char in match.group(0)),
            value,
        )
        stack: list[tuple[str, int]] = []
        index = 0
        while index < len(value):
            if value[index] == "\\" and index + 1 < len(value):
                if value[index:index + 2] in {r"\[", r"\]", r"\(", r"\)"}:
                    token = value[index:index + 2]
                    if token == r"\]" and stack and stack[-1][0] == r"\[":
                        stack.pop()
                    elif token == r"\)" and stack and stack[-1][0] == r"\(":
                        stack.pop()
                    elif token in {r"\[", r"\("}:
                        stack.append((token, index))
                    index += 2
                    continue
                # An escaped dollar/backslash is literal.
                index += 2
                continue
            token = "$$" if value.startswith("$$", index) else "$" if value[index] == "$" else ""
            if token:
                if stack and stack[-1][0] == token:
                    stack.pop()
                else:
                    stack.append((token, index))
                index += len(token)
                continue
            index += 1
        for token, position in stack:
            line = block.source_start_line + value[:position].count("\n")
            diagnostics.append(Diagnostic(
                "warning", "MATH_PARSE_ERROR", line,
                f"数学分隔符“{token}”未闭合，已保留原文。", block.block_id,
            ))
    return diagnostics


def bare_math_diagnostics(blocks: Iterable[Block]):
    diagnostics = []
    patterns = [
        (r"(?<![\w$])\d+\^[A-Za-z0-9]+", "疑似未放进 $...$ 的幂表达式"),
        (r"(?<![\w$])[A-Za-z][A-Za-z0-9]*\^\(?[A-Za-z0-9/+\-]+\)?", "疑似未放进 $...$ 的幂表达式"),
        (r"(?<![\w$])[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9]+\b", "疑似未放进 $...$ 的下标表达式"),
        (r"\\(?:sum|frac|sqrt|log)\b", "发现公式命令位于数学环境外"),
    ]
    for block in blocks:
        if block.kind in {"code", "preformatted", "math"}:
            continue
        # Explicitly delimited formulas are valid, and inline code/previews
        # are literal text.  Mask both before checking for the conservative
        # bare-math patterns so ``$a = 2^60$`` and `` `a_i` `` do not produce
        # misleading warnings.
        masked = re.sub(
            r"`[^`\n]*`|(?<!\\)\$\$.*?(?<!\\)\$\$|(?<!\\)\\\[.*?\\\]|(?<!\\)\$[^$\n]+?(?<!\\)\$|\\\([^\n]*?\\\)",
            lambda match: "".join("\n" if ch == "\n" else " " for ch in match.group(0)),
            block.content,
            flags=re.S,
        )
        for pattern, message in patterns:
            for match in re.finditer(pattern, masked):
                diagnostics.append(Diagnostic(
                    "warning", "BARE_MATH", block.source_start_line + masked[:match.start()].count("\n"),
                    f"{message}：{match.group(0)}", block.block_id,
                ))
    return diagnostics


def _module_formula_count(module: Module):
    return len(formulas_in_blocks(module.blocks))


def parse_document(markdown: str) -> DocumentModel:
    metadata, detected, metadata_end = parse_front_matter(markdown)
    preamble, sections = split_sections(markdown)
    diagnostics: list[Diagnostic] = []
    fenced_metadata = bool(metadata_end and _fence_open(preamble.splitlines(keepends=True)[0])) if preamble else False
    if fenced_metadata:
        diagnostics.append(Diagnostic(
            "warning", "FENCED_METADATA", 1,
            "文件开头的代码围栏已识别为试卷格式声明；推荐改用 --- 包住格式声明。",
        ))
    if detected == "legacy-v04":
        year, group, level = parse_meta(markdown)
        metadata = {"year": year, "group": group, "level": level, **metadata}
    else:
        title = metadata.get("title", "")
        year_match = re.search(r"20\d{2}", str(metadata.get("year") or title))
        year = year_match.group(0) if year_match else "20XX"
        group = str(metadata.get("group", "J")).upper()
        level = "提高级" if group == "S" else "入门级"
        metadata.update({"year": year, "group": group, "level": level})
        if group not in {"J", "S"}:
            diagnostics.append(Diagnostic("error", "INVALID_GROUP", 1, "group 只能是 J 或 S。"))
    if not sections:
        diagnostics.append(Diagnostic("error", "MISSING_SECTION", 1, "没有识别到“## 第 n 题”模块。"))

    numbers = [x["number"] for x in sections]
    for number in sorted(set(numbers)):
        if numbers.count(number) > 1:
            line = next(x["heading_line"] for x in sections if x["number"] == number)
            diagnostics.append(Diagnostic("error", "DUPLICATE_QUESTION", line, f"题号 {number} 重复。"))
    modules: list[Module] = []
    for section in sections:
        number = section["number"]
        try:
            if number <= 15:
                module = _parse_choice(number, section["body"], section["body_start_line"], detected == "standard-v1", diagnostics)
            elif number <= 18:
                module = _parse_reading(number, section["body"], section["body_start_line"], detected == "standard-v1", diagnostics)
            else:
                module = _parse_fill(number, section["body"], section["body_start_line"], detected == "standard-v1", diagnostics)
        except ConvertError as exc:
            kind = "choice" if number <= 15 else "reading" if number <= 18 else "fill"
            module = Module(number, kind, section["body"], section["body_start_line"])
            module.errors.append(Diagnostic("error", "PARSE_ERROR", section["heading_line"], str(exc)))
        modules.append(module)

    missing = [x for x in range(1, 21) if x not in numbers]
    if missing:
        diagnostics.append(Diagnostic("error", "MISSING_QUESTION", 1, "缺少题目模块：" + "、".join(map(str, missing))))

    # Bare math checks and per-module formula counts use only content blocks;
    # dollars in code/input can therefore never become formulas accidentally.
    for module in modules:
        local = module.all_rendered_blocks()
        # ``clean_blocks`` intentionally joins prose lines for the V0.4-style
        # Word paragraph.  Diagnostics must use a fresh scan of the original
        # section so a formula on the fourth source line is not reported as
        # line two after that presentation cleanup.
        raw_local = scan_blocks(module.body, module.body_start_line, diagnostics=[])
        option_texts = list(module.options)
        for item in [*module.judges, *module.choices, *module.groups]:
            option_texts.extend(item.options)
        option_blocks = [Block("paragraph", "prose", content=value, source_start_line=module.body_start_line, source_end_line=module.body_start_line) for value in option_texts]
        module.formula_count = len(formulas_in_blocks([*local, *option_blocks]))
        module.warnings.extend(unclosed_math_diagnostics(raw_local))
        module.warnings.extend(bare_math_diagnostics(raw_local))
        diagnostics.extend(module.errors)
        diagnostics.extend(module.warnings)

    # Count the actual source blocks once.  Every fenced block must occur in a
    # rendered module; a paragraph containing a title/score is harmless and is
    # intentionally not considered dropped.
    all_blocks: list[Block] = []
    # Header fields are metadata, not printable question blocks.  Preserve
    # the original preamble for editing, and retain original line offsets.
    pre_content = preamble[metadata_end:]
    pre_line = 1 + preamble[:metadata_end].count("\n")
    pre_blocks = scan_blocks(pre_content, pre_line, diagnostics=diagnostics)
    all_blocks.extend(pre_blocks)
    for section in sections:
        all_blocks.extend(scan_blocks(section["body"], section["body_start_line"], diagnostics=diagnostics))
    rendered = module_blocks = [x for module in modules for x in module.all_rendered_blocks()]
    rendered_keys = {(x.source_start_line, x.source_end_line, x.kind, x.content) for x in rendered}
    dropped = 0
    for block in all_blocks:
        key = (block.source_start_line, block.source_end_line, block.kind, block.content)
        if block.fenced and key not in rendered_keys:
            dropped += 1
            diagnostics.append(Diagnostic("error", "UNRENDERED_BLOCK", block.source_start_line, "区块无法安全归属题目或渲染，已阻止导出。", block.block_id))
    # Attach fence diagnostics to the owning module as well as the document so
    # the sidebar can mark the affected question without losing the global
    # diagnostic list.
    for diagnostic in diagnostics:
        if diagnostic.code not in {"UNKNOWN_FENCE", "UNCLOSED_FENCE", "EMPTY_FENCE"}:
            continue
        owner = None
        for index, section in enumerate(sections):
            section_end = sections[index + 1]["heading_line"] - 1 if index + 1 < len(sections) else 10**9
            if section["heading_line"] <= diagnostic.line <= section_end:
                owner = next((x for x in modules if x.number == section["number"] and x.body_start_line == section["body_start_line"]), None)
                break
        if owner is not None and not any(x.code == diagnostic.code and x.line == diagnostic.line for x in owner.warnings + owner.errors):
            (owner.errors if diagnostic.severity == "error" else owner.warnings).append(diagnostic)
    source_count = len(all_blocks)
    unique_diagnostics = []
    seen_diagnostics = set()
    for diagnostic in diagnostics:
        key = (diagnostic.severity, diagnostic.code, diagnostic.line, diagnostic.message)
        if key in seen_diagnostics:
            continue
        seen_diagnostics.add(key)
        unique_diagnostics.append(diagnostic)
    return DocumentModel(
        markdown=markdown,
        preamble=preamble,
        metadata=metadata,
        detected_format=detected,
        modules=modules,
        diagnostics=unique_diagnostics,
        source_blocks=source_count,
        rendered_blocks=source_count - dropped,
        dropped_blocks=dropped,
    )


def has_lossless_features(markdown: str) -> bool:
    """Whether the service must use the compatibility renderer.

    Unlabelled fences are common in the old official samples and are left on
    the V0.4 fast path.  Explicit text/input/output/math fences, unknown
    fences, or format-1 metadata opt into the lossless parser.
    """
    _, detected, _ = parse_front_matter(markdown)
    if detected == "standard-v1":
        return True
    blocks = scan_blocks(markdown, 1, diagnostics=[])
    # Full-width punctuation is common in Chinese editors but is not part of
    # the original V0.4 option regex.  Route those documents through the
    # lossless adapter while leaving the official ASCII ``A.`` samples on the
    # byte-compatible fast path.
    masked = _mask_fenced_regions(markdown)
    if re.search(r"(?m)^[ \t]*(?:[-*+]\s*)?[A-D]．\s*", masked):
        return True
    for block in blocks:
        if not block.fenced:
            continue
        # The original V0.4 regular expressions only recognize a plain
        # `````cpp`` opener.  Any other supported program alias (c++, cxx,
        # java, ...), tilde fence, or opener with an info suffix therefore
        # needs the lossless renderer even though it is still a code block.
        info = block.info.strip()
        if block.kind != "code" or info != "cpp" or block.fence_marker.startswith("~"):
            return True
    return False
