#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Analysis response for the lossless CSP Paper Studio service."""

from __future__ import annotations

from converter import choose_option_shape
from compatible_parser import has_lossless_features, parse_document
from studio_core import analyze_markdown as analyze_v04, code_meta


def _block_json(block):
    value = block.as_dict()
    if block.kind == "code":
        value["code"] = code_meta(block.content)
    return value


def _item_text(item):
    return item.text


def _module_response(module, group):
    errors = [x.message for x in module.errors]
    warnings = [x.as_dict() | {"type": "parser"} for x in module.warnings]
    code_infos = [code_meta(module.code.content)] if module.code is not None else []
    common = {
        "number": module.number,
        "kind": module.kind,
        "status": "error" if errors else "warning" if warnings else "ok",
        "errors": errors,
        "warnings": warnings,
        "raw": module.body,
        "formula_count": module.formula_count,
        "code": code_infos,
    }
    if module.kind == "choice":
        blocks = [_block_json(x) for x in module.blocks]
        common["preview"] = {
            "type": "choice",
            "blocks": blocks,
            "options": module.options,
            "layout": choose_option_shape(module.options),
        }
    elif module.kind == "reading":
        choices = []
        for item in module.choices:
            choices.append({
                "text": _item_text(item),
                "blocks": [_block_json(x) for x in item.blocks],
                "options": item.options,
                "layout": choose_option_shape(item.options),
            })
        judges = [_item_text(x) for x in module.judges]
        common["preview"] = {
            "type": "reading",
            "section_index": module.number - 15,
            "note": " ".join(x.content for x in module.note_blocks if x.kind == "paragraph").strip(),
            "note_blocks": [_block_json(x) for x in module.note_blocks],
            "code": code_infos[0] if code_infos else {"line_count": 0, "normalized_lines": []},
            "judges": judges,
            "judge_blocks": [[_block_json(x) for x in item.blocks] for item in module.judges],
            "choices": choices,
            "global_numbering": group == "J",
        }
    else:
        groups = []
        for item in module.groups:
            groups.append({
                "text": _item_text(item),
                "blocks": [_block_json(x) for x in item.blocks],
                "options": item.options,
                "layout": choose_option_shape(item.options),
            })
        prose = [x.content for x in module.prose_blocks if x.kind == "paragraph"]
        common["preview"] = {
            "type": "fill",
            "section_index": module.number - 18,
            "prose": prose,
            "prose_blocks": [_block_json(x) for x in module.prose_blocks],
            "code": code_infos[0] if code_infos else {"line_count": 0, "normalized_lines": []},
            "groups": groups,
            "global_numbering": group == "J",
        }
    return common


def _augment_legacy(markdown: str):
    """Keep the exact V0.4 analysis while exposing additive schema fields."""
    result = analyze_v04(markdown)
    model = parse_document(markdown)
    result["schema_version"] = 2
    result["detected_format"] = "legacy-v04"
    result["coverage"] = model.coverage()
    result["diagnostics"] = model.diagnostics_dict()
    if has_lossless_features(markdown):
        result["migration_hint"] = "当前文档仍按旧版兼容格式处理；可下载标准模板迁移到 csp_format: 1。"
    # The old preview is intentionally not replaced: the four official
    # samples are regression fixtures for its paragraph/table structure.
    return result


def analyze_markdown(markdown: str):
    if not has_lossless_features(markdown):
        return _augment_legacy(markdown)
    model = parse_document(markdown)
    modules = [_module_response(x, model.metadata.get("group", "J")) for x in model.modules]
    if model.metadata.get("group") == "J":
        current = 16
        for module in modules:
            preview = module.get("preview") or {}
            if module["kind"] == "reading":
                preview["judge_numbers"] = list(range(current, current + len(preview.get("judges", []))))
                current += len(preview.get("judges", []))
                preview["choice_numbers"] = list(range(current, current + len(preview.get("choices", []))))
                current += len(preview.get("choices", []))
            elif module["kind"] == "fill":
                preview["group_numbers"] = list(range(current, current + len(preview.get("groups", []))))
                current += len(preview.get("groups", []))
    summary_errors = [x.message for x in model.errors if x.code not in {"PARSE_ERROR"}]
    summary_errors.extend(x["errors"][0] for x in modules if x["errors"])
    result = {
        "schema_version": 2,
        "detected_format": model.detected_format,
        "meta": {
            "year": model.metadata.get("year", "20XX"),
            "group": model.metadata.get("group", "J"),
            "level": model.metadata.get("level", "入门级"),
        },
        "preamble": model.preamble,
        "modules": modules,
        "summary_errors": summary_errors,
        "counts": {
            "ok": sum(x["status"] == "ok" for x in modules),
            "warning": sum(x["status"] == "warning" for x in modules),
            "error": sum(x["status"] == "error" for x in modules),
        },
        "coverage": model.coverage(),
        "diagnostics": model.diagnostics_dict(),
    }
    if model.detected_format == "legacy-v04":
        result["migration_hint"] = "当前文档仍按旧版兼容格式处理；可下载标准模板迁移到 csp_format: 1。"
    return result
