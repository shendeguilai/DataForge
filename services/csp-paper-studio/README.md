# CSP Paper Studio V0.4 service

`converter.py`、`studio_core.py`、`CSP_初赛模板.docx` 和 `samples/` 原样来自 CSP Paper Studio V0.4。
`service_app.py` 只负责输入限制、并发控制、临时文件清理和内部 HTTP 接口。`compatible_parser.py` / `compatible_converter.py` 是新增的兼容层：只有检测到标准格式或 `text`、`input`、`output`、数学等有类型区块时才启用，不改变 V0.4 官方样例的旧路径。

本地开发：

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.lock.txt
.venv/bin/python -m uvicorn service_app:app --host 127.0.0.1 --port 8765
```

Word 数学公式导出需要 Pandoc 3.8.2.1。

## 标准 Markdown（format 1）

页面的“下载标准模板”会提供完整 20 题骨架。文件顶部声明：

```yaml
---
csp_format: 1
title: 2026 CSP-S 试卷
year: 2026
group: S
numbering: auto
---
```

题目使用 `## 第 n 题`，并按 `### 类型`、`### 题干`、`### 程序`、`### 说明`、`### 判断题`、`### 单选题`、`### 小题` 分段。程序放在 ` ```cpp` 围栏；输入、输出和数据放在 ` ```text`、` ```plaintext`、` ```input` 或 ` ```output` 围栏；数学公式使用 `$...$`、`$$...$$`、`\(...\)`、`\[...\]` 或 ` ```math`。区块内的空格和换行会保留，未知围栏会原样保留并在分析中提示。

旧版没有元数据声明的 V0.4 Markdown 仍会走原解析器，保证官方四份样例的 Word 结构不变。服务不会执行 Markdown 中的代码，也不会保存 Markdown、编辑历史或导出的 Word 文件。
