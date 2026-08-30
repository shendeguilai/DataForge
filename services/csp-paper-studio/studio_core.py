# -*- coding: utf-8 -*-
import re
from pathlib import Path

from converter import (
    ConvertError,
    split_sections,
    parse_q1_15,
    parse_reading,
    parse_fill,
    parse_meta,
    clean_prose,
    choose_option_shape,
    normalize_code_lines,
    collect_math,
)


def split_source(md: str):
    """保留 MD 前言并拆出 ## 第 n 题 模块。"""
    matches = list(re.finditer(r'(?m)^## 第\s*(\d+)\s*题\s*$', md))
    if not matches:
        return md, []
    preamble = md[:matches[0].start()]
    modules = []
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(md)
        modules.append({
            'number': int(m.group(1)),
            'body': md[m.end():end],
        })
    return preamble, modules


def rebuild_source(preamble: str, modules):
    out = [preamble.rstrip()]
    for m in modules:
        out.append(f'\n\n## 第 {m["number"]} 题\n')
        out.append(m['body'].lstrip('\n'))
    return ''.join(out).rstrip() + '\n'


def code_meta(code: str):
    raw = code.rstrip('\n').splitlines()
    normalized = normalize_code_lines(code)
    nonempty = [x for x in raw if x.strip()]
    numbered = 0
    for x in nonempty:
        if re.match(r'^\s*\d{1,3}(?:\s+|$)', x):
            numbered += 1
    source_numbered = bool(nonempty and numbered / len(nonempty) >= 0.8)
    fullwidth = []
    fullwidth_chars = '；，：（）【】“”‘’'
    for i, line in enumerate(normalized, 1):
        found = sorted(set(ch for ch in line if ch in fullwidth_chars))
        if found:
            fullwidth.append({'line': i, 'chars': ''.join(found)})
    return {
        'line_count': len(normalized),
        'source_numbered': source_numbered,
        'normalized_lines': normalized,
        'fullwidth': fullwidth,
        'max_indent': max((len(x) - len(x.lstrip(' ')) for x in normalized if x.strip()), default=0),
    }


def mask_code_and_math(text: str):
    text = re.sub(r'```.*?```', lambda m: ' ' * len(m.group(0)), text, flags=re.S)
    text = re.sub(r'`[^`\n]*`', lambda m: ' ' * len(m.group(0)), text)
    text = re.sub(r'(?<!\\)\$[^$\n]+?(?<!\\)\$', lambda m: ' ' * len(m.group(0)), text)
    return text


def formula_warnings(text: str):
    """只做保守提示，不自动修改内容。"""
    masked = mask_code_and_math(text)
    warnings = []
    patterns = [
        (r'\b[A-Za-z][A-Za-z0-9]*\^\(?[A-Za-z0-9/+\-]+\)?', '疑似未放进 $...$ 的幂表达式'),
        (r'\b[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9]+\b', '疑似未放进 $...$ 的下标表达式'),
        (r'\\sum\b', '发现公式命令 \\sum 位于数学环境外'),
    ]
    for pat, label in patterns:
        for m in re.finditer(pat, masked):
            s = m.group(0).strip()
            if s and s not in [x.get('sample') for x in warnings]:
                warnings.append({'type': 'formula', 'message': label, 'sample': s})
                if len(warnings) >= 8:
                    return warnings
    return warnings


def option_payload(opts):
    return {'options': opts, 'layout': choose_option_shape(opts)}


def analyze_one(number: int, body: str, group='J'):
    item = {
        'number': number,
        'kind': 'choice' if number <= 15 else ('reading' if number <= 18 else 'fill'),
        'status': 'ok',
        'errors': [],
        'warnings': [],
        'raw': body,
        'preview': None,
        'formula_count': len(collect_math(body)),
    }
    item['warnings'].extend(formula_warnings(body))
    try:
        if number <= 15:
            blocks, opts = parse_q1_15(body, number)
            code_infos = []
            preview_blocks = []
            for kind, value in blocks:
                if kind == 'code':
                    cm = code_meta(value)
                    code_infos.append(cm)
                    preview_blocks.append({'type': 'code', 'code': cm})
                else:
                    preview_blocks.append({'type': 'text', 'text': value})
            item['preview'] = {
                'type': 'choice',
                'blocks': preview_blocks,
                **option_payload(opts),
            }
            item['code'] = code_infos
        elif number <= 18:
            code, judges, choices, note = parse_reading(body, number)
            cm = code_meta(code)
            item['preview'] = {
                'type': 'reading',
                'section_index': number - 15,
                'note': note,
                'code': cm,
                'judges': judges,
                'choices': [
                    {'text': q, **option_payload(opts)} for q, opts in choices
                ],
                'global_numbering': group == 'J',
            }
            item['code'] = [cm]
        else:
            pre, code, groups = parse_fill(body, number)
            cm = code_meta(code)
            item['preview'] = {
                'type': 'fill',
                'section_index': number - 18,
                'prose': clean_prose(pre),
                'code': cm,
                'groups': [
                    {'text': q, **option_payload(opts)} for q, opts in groups
                ],
                'global_numbering': group == 'J',
            }
            item['code'] = [cm]

        for c in item.get('code', []):
            if c['fullwidth']:
                samples = ', '.join(f"第{x['line']}行 {x['chars']}" for x in c['fullwidth'][:4])
                item['warnings'].append({
                    'type': 'code',
                    'message': '代码中检测到全角标点，请确认是否为题库原文',
                    'sample': samples,
                })
            if c['line_count'] > 70:
                item['warnings'].append({
                    'type': 'code', 'message': '代码较长，导出 Word 时可能跨页较多',
                    'sample': f"{c['line_count']} 行"
                })
    except ConvertError as e:
        item['errors'].append(str(e))
    except Exception as e:
        item['errors'].append(f'解析器异常：{e}')

    if item['errors']:
        item['status'] = 'error'
    elif item['warnings']:
        item['status'] = 'warning'
    return item


def analyze_markdown(md: str):
    preamble, modules = split_source(md)
    year, group, level = parse_meta(md)
    by_no = {m['number']: m for m in modules}
    result = []
    for n in sorted(by_no):
        result.append(analyze_one(n, by_no[n]['body'], group))

    # 给 J 组预览补上与最终 Word 一致的全局题号。
    # 这样切到阅读程序/完善程序模块时，看到的就是 16、17、18...，
    # 而不是仅用于模块内部的临时编号。
    if group == 'J':
        cur = 16
        for item in result:
            p = item.get('preview') or {}
            if item['kind'] == 'reading' and p:
                p['judge_numbers'] = list(range(cur, cur + len(p.get('judges', []))))
                cur += len(p.get('judges', []))
                p['choice_numbers'] = list(range(cur, cur + len(p.get('choices', []))))
                cur += len(p.get('choices', []))
            elif item['kind'] == 'fill' and p:
                p['group_numbers'] = list(range(cur, cur + len(p.get('groups', []))))
                cur += len(p.get('groups', []))

    missing = [n for n in range(1, 21) if n not in by_no]
    summary_errors = []
    if missing:
        summary_errors.append('缺少题目模块：' + '、'.join(map(str, missing)))
    dup = [n for n in set(m['number'] for m in modules) if sum(x['number'] == n for x in modules) > 1]
    if dup:
        summary_errors.append('存在重复题号：' + '、'.join(map(str, sorted(dup))))
    return {
        'meta': {'year': year, 'group': group, 'level': level},
        'preamble': preamble,
        'modules': result,
        'summary_errors': summary_errors,
        'counts': {
            'ok': sum(x['status'] == 'ok' for x in result),
            'warning': sum(x['status'] == 'warning' for x in result),
            'error': sum(x['status'] == 'error' for x in result),
        },
    }
