#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json
import logging
import re
import shutil
import tempfile
import threading
import time
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse
from starlette.background import BackgroundTask
from starlette.concurrency import run_in_threadpool

import converter as converter_module
from converter import ConvertError, convert
from studio_core import analyze_markdown


ROOT = Path(__file__).resolve().parent
TEMPLATE = ROOT / 'CSP_初赛模板.docx'
PANDOC_PATH = shutil.which('pandoc')
MAX_MARKDOWN_BYTES = 2 * 1024 * 1024
# JSON 会转义换行和控制字符；先给传输体留足空间，再对解码后的 Markdown
# 做严格的 2 MiB 校验，避免合法文件因 JSON 编码膨胀被误拒绝。
MAX_BODY_BYTES = MAX_MARKDOWN_BYTES * 6 + 16 * 1024
MAX_FILENAME_CHARS = 200
MAX_WORD_BYTES = 25 * 1024 * 1024
VALID_NUMBERING = {'auto', 'global', 'local'}
SAMPLES = {
    '2022j': ROOT / 'samples' / '2022_CSP-J.md',
    '2023j': ROOT / 'samples' / '2023_CSP-J.md',
    '2024j': ROOT / 'samples' / '2024_CSP-J.md',
    '2025s': ROOT / 'samples' / '2025_CSP-S.md',
}

LOGGER = logging.getLogger('uvicorn.error')
LOGGER.setLevel(logging.INFO)
# 原版转换器会把包含用户文件名的临时路径打印到标准输出。集成服务保留
# 转换逻辑，但关闭这类明细日志，只由中间件记录接口、状态与耗时。
converter_module.info = lambda _message: None
EXPORT_SLOTS = threading.BoundedSemaphore(value=2)


class ExportBusy(RuntimeError):
    pass


app = FastAPI(
    title='CSP Paper Studio Service',
    version='0.4',
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


@app.middleware('http')
async def request_metrics(request: Request, call_next):
    started = time.monotonic()
    status = 500
    try:
        response = await call_next(request)
        status = response.status_code
        return response
    except Exception as exc:
        LOGGER.error(
            'request method=%s path=%s status=%s duration_ms=%.1f error=%s',
            request.method,
            request.url.path,
            status,
            (time.monotonic() - started) * 1000,
            type(exc).__name__,
        )
        raise
    finally:
        if status != 500:
            LOGGER.info(
                'request method=%s path=%s status=%s duration_ms=%.1f',
                request.method,
                request.url.path,
                status,
                (time.monotonic() - started) * 1000,
            )


@app.exception_handler(HTTPException)
async def http_error(_request: Request, exc: HTTPException):
    return JSONResponse({'error': str(exc.detail)}, status_code=exc.status_code)


@app.get('/health')
def health():
    template_ready = TEMPLATE.is_file()
    if not template_ready or PANDOC_PATH is None:
        missing = 'Word 模板不存在' if not template_ready else 'Pandoc 不可用'
        return JSONResponse(
            {'status': 'DOWN', 'version': '0.4', 'error': missing},
            status_code=503,
        )
    return {'status': 'UP', 'version': '0.4'}


@app.get('/api/sample/{name}')
def sample(name: str):
    path = SAMPLES.get(name.lower())
    if path is None or not path.is_file():
        raise HTTPException(404, '示例不存在')
    return JSONResponse({
        'name': path.name,
        'markdown': path.read_text(encoding='utf-8-sig'),
    }, headers={'Cache-Control': 'no-store'})


async def read_payload(request: Request):
    body = await request.body()
    if len(body) > MAX_BODY_BYTES:
        raise HTTPException(413, 'Markdown 不能超过 2 MiB')
    try:
        payload = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise HTTPException(400, '请求内容不是有效的 JSON') from None
    if not isinstance(payload, dict):
        raise HTTPException(400, '请求内容必须是 JSON 对象')
    return payload


def require_markdown(payload):
    markdown = payload.get('markdown', '')
    if not isinstance(markdown, str) or not markdown.strip():
        raise HTTPException(400, 'Markdown 不能为空')
    if len(markdown.encode('utf-8')) > MAX_MARKDOWN_BYTES:
        raise HTTPException(413, 'Markdown 不能超过 2 MiB')
    return markdown


def safe_stem(filename):
    if not isinstance(filename, str):
        raise HTTPException(400, '文件名格式不正确')
    if len(filename) > MAX_FILENAME_CHARS:
        raise HTTPException(400, '文件名不能超过 200 个字符')
    basename = re.split(r'[/\\]', filename)[-1].strip()
    stem = Path(basename).stem.strip()
    stem = re.sub(r'[\x00-\x1f<>:"/\\|?*]+', '_', stem).strip(' .')
    return stem or 'CSP试卷'


@app.post('/api/analyze')
async def analyze(request: Request):
    payload = await read_payload(request)
    markdown = require_markdown(payload)
    result = await run_in_threadpool(analyze_markdown, markdown)
    return JSONResponse(result, headers={'Cache-Control': 'no-store'})


def build_word(markdown: str, filename: str, numbering: str):
    if not EXPORT_SLOTS.acquire(blocking=False):
        raise ExportBusy()
    temp_dir = Path(tempfile.mkdtemp(prefix='csp_studio_'))
    source = temp_dir / f'{filename}.md'
    output = temp_dir / f'{filename}.docx'
    try:
        source.write_text(markdown, encoding='utf-8')
        convert(source, output, TEMPLATE, numbering)
        if output.stat().st_size > MAX_WORD_BYTES:
            raise ConvertError('生成的 Word 文件超过 25 MiB')
        return temp_dir, output
    except Exception:
        shutil.rmtree(temp_dir, ignore_errors=True)
        raise
    finally:
        EXPORT_SLOTS.release()


@app.post('/api/export')
async def export(request: Request):
    payload = await read_payload(request)
    markdown = require_markdown(payload)
    filename = safe_stem(payload.get('filename', 'CSP试卷.md'))
    numbering = payload.get('numbering', 'auto')
    if numbering not in VALID_NUMBERING:
        raise HTTPException(400, '题号模式必须是 auto、global 或 local')

    try:
        temp_dir, output = await run_in_threadpool(
            build_word, markdown, filename, numbering
        )
    except ExportBusy:
        raise HTTPException(429, '当前 Word 导出任务较多，请稍后重试') from None
    except ConvertError as exc:
        return JSONResponse({'error': str(exc)}, status_code=422)
    except Exception as exc:
        LOGGER.error('export failed error=%s', type(exc).__name__)
        return JSONResponse({'error': 'Word 导出异常，请稍后重试'}, status_code=500)

    return FileResponse(
        output,
        media_type='application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        filename=f'{filename}.docx',
        headers={'Cache-Control': 'no-store'},
        background=BackgroundTask(shutil.rmtree, temp_dir, ignore_errors=True),
    )
