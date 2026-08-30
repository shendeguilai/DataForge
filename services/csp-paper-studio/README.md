# CSP Paper Studio V0.4 service

`converter.py`、`studio_core.py`、`CSP_初赛模板.docx` 和 `samples/` 原样来自 CSP Paper Studio V0.4。
`service_app.py` 只负责输入限制、并发控制、临时文件清理和内部 HTTP 接口，不修改解析或 Word 生成规则。

本地开发：

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.lock.txt
.venv/bin/python -m uvicorn service_app:app --host 127.0.0.1 --port 8765
```

Word 数学公式导出需要 Pandoc 3.8.2.1。
