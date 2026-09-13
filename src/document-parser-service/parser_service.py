import base64
import io
import os
import re
import tempfile
from typing import Any

import httpx
from fastapi import FastAPI
from pydantic import BaseModel, Field
from secret_utils import read_secret

app = FastAPI(title="YlCloud Document Parser Service")


class DocumentBlock(BaseModel):
    orderIndex: int
    type: str
    text: str
    pageNo: int | None = None
    level: int | None = None
    headingPath: list[str] = Field(default_factory=list)
    bbox: str | None = None
    confidence: float | None = None
    source: str | None = None


class ParseRequest(BaseModel):
    fileUuid: str | None = None
    fileHash: str | None = None
    fileName: str | None = None
    fileType: str | None = None
    objectUrl: str | None = None
    maxPages: int | None = 100
    parserVersion: str | None = "structured-v1"


class PageParseRequest(ParseRequest):
    pageNo: int | None = 1


class ParseResponse(BaseModel):
    success: bool
    parser: str = "ocr-layout"
    parserVersion: str = "structured-v1"
    fullText: str | None = None
    blocks: list[DocumentBlock] = Field(default_factory=list)
    errorMessage: str | None = None
    warnings: list[str] = Field(default_factory=list)


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "service": "document-parser-service",
        "ocrEnabled": True,
        "vlmEnabled": _vlm_configured(),
        "vlmApiStyle": _vlm_api_style(),
        "tesseractAvailable": _module_available("pytesseract"),
        "pymupdfAvailable": _module_available("fitz"),
        "pypdfAvailable": _module_available("pypdf"),
    }


@app.post("/parse/ocr", response_model=ParseResponse)
async def parse_ocr(request: ParseRequest) -> ParseResponse:
    try:
        content = await _load_content(request)
        if not content:
            return _failed(request, "No document content supplied. Provide objectUrl or mount object fetch support.")
        file_name = (request.fileName or request.fileUuid or "").lower()
        if file_name.endswith(".pdf") or (request.fileType or "").lower().endswith("pdf"):
            return _parse_pdf(content, request)
        if file_name.endswith((".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp")):
            return _parse_image(content, request)
        return _parse_plain_text(content, request)
    except Exception as exc:
        return _failed(request, str(exc))


@app.post("/parse/layout", response_model=ParseResponse)
async def parse_layout(request: ParseRequest) -> ParseResponse:
    try:
        content = await _load_content(request)
        if not content:
            return _failed(request, "No document content supplied. Provide objectUrl or mount object fetch support.")
        file_name = (request.fileName or request.fileUuid or "").lower()
        if file_name.endswith(".pdf") or (request.fileType or "").lower().endswith("pdf"):
            return _parse_pdf_layout(content, request)
        if file_name.endswith((".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp")):
            return _parse_image(content, request)
        return _parse_plain_text(content, request)
    except Exception as exc:
        return _failed(request, str(exc))


@app.post("/parse/page", response_model=ParseResponse)
async def parse_page(request: PageParseRequest) -> ParseResponse:
    if not _vlm_configured():
        return ParseResponse(
            success=False,
            parser="vlm-page",
            parserVersion=request.parserVersion or "structured-v1",
            errorMessage="VLM parser is disabled: OPENAI_COMPATIBLE_BASE_URL and OPENAI_COMPATIBLE_API_KEY are not configured",
        )
    try:
        content = await _load_content(request)
        if not content:
            return _failed(request, "No document content supplied for VLM parsing")
        image_data_url = _page_image_data_url(content, request)
        data = await _call_openai_compatible_vlm(request, image_data_url)
        return _parse_vlm_json_response(request, data)
    except Exception as exc:
        return ParseResponse(
            success=False,
            parser="vlm-page",
            parserVersion=request.parserVersion or "structured-v1",
            errorMessage=str(exc),
        )


async def _load_content(request: ParseRequest) -> bytes | None:
    if request.objectUrl:
        async with httpx.AsyncClient(timeout=120) as client:
            response = await client.get(request.objectUrl)
            response.raise_for_status()
            return response.content
    inline = os.getenv("DOCUMENT_PARSER_INLINE_BASE64")
    if inline:
        return base64.b64decode(inline)
    return None


def _parse_pdf_layout(content: bytes, request: ParseRequest) -> ParseResponse:
    warnings: list[str] = []
    blocks = _extract_pdf_layout_blocks(content, request, warnings)
    if _is_good_blocks(blocks):
        return _response(request, "pdf-layout", blocks, warnings)
    text_blocks = _extract_pdf_text(content, request, warnings)
    if _is_good_blocks(text_blocks):
        return _response(request, "pdf-text", text_blocks, warnings)
    ocr_blocks = _ocr_pdf_pages(content, request, warnings)
    if _is_good_blocks(ocr_blocks):
        return _response(request, "ocr-layout", ocr_blocks, warnings)
    return _failed(request, "PDF layout/OCR produced no reliable text", warnings)


def _extract_pdf_layout_blocks(content: bytes, request: ParseRequest, warnings: list[str]) -> list[DocumentBlock]:
    try:
        import fitz
    except Exception as exc:
        warnings.append(f"PyMuPDF unavailable: {exc}")
        return []
    blocks: list[DocumentBlock] = []
    index = 0
    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as tmp:
        tmp.write(content)
        tmp_path = tmp.name
    try:
        doc = fitz.open(tmp_path)
        max_pages = min(len(doc), request.maxPages or 100)
        for page_index in range(max_pages):
            page = doc.load_page(page_index)
            layout = page.get_text("dict")
            page_blocks: list[DocumentBlock] = []
            for raw_block in layout.get("blocks", []):
                if raw_block.get("type") != 0:
                    continue
                lines: list[str] = []
                conf_values: list[float] = []
                for line in raw_block.get("lines", []):
                    spans = line.get("spans", [])
                    line_text = "".join(span.get("text", "") for span in spans).strip()
                    if line_text:
                        lines.append(line_text)
                    for span in spans:
                        if span.get("size"):
                            conf_values.append(float(span.get("size")))
                text = "\n".join(lines).strip()
                if not text:
                    continue
                bbox = raw_block.get("bbox")
                avg_font_size = sum(conf_values) / len(conf_values) if conf_values else None
                block_type = _detect_layout_block_type(text, bbox, page.rect.height, avg_font_size)
                page_blocks.append(DocumentBlock(
                    orderIndex=index,
                    type=block_type,
                    text=text,
                    pageNo=page_index + 1,
                    level=1 if block_type == "heading" else None,
                    bbox=_bbox_to_string(bbox),
                    confidence=1.0,
                    source="layout",
                ))
                index += 1
            blocks.extend(_merge_heading_paths(page_blocks))
        return blocks
    except Exception as exc:
        warnings.append(f"pdf layout extraction failed: {exc}")
        return []
    finally:
        try:
            os.remove(tmp_path)
        except OSError:
            pass


def _detect_layout_block_type(text: str, bbox: Any, page_height: float, avg_font_size: float | None = None) -> str:
    value = text.strip()
    if not value:
        return "paragraph"
    if "|" in value and value.count("|") >= 2:
        return "table"
    if value.startswith("```") or re.search(r"\b(class|interface|def|function|select|insert|update|public|private)\b", value, re.I):
        return "code"
    y0 = bbox[1] if bbox and len(bbox) > 1 else None
    y1 = bbox[3] if bbox and len(bbox) > 3 else None
    if y0 is not None and y0 < page_height * 0.08:
        return "header"
    if y1 is not None and y1 > page_height * 0.92:
        return "footer"
    if len(value) <= 100 and avg_font_size is not None and avg_font_size >= 15:
        return "heading"
    if len(value) <= 100 and re.search(r"(^#{1,6}\s+|第.+[章节]|^\d+(\.\d+)*\s+|[:：]$)", value):
        return "heading"
    if re.match(r"^([-*+]|\d+[.)])\s+", value):
        return "list"
    return "paragraph"


def _bbox_to_string(bbox: Any) -> str | None:
    if not bbox or len(bbox) < 4:
        return None
    return ",".join(str(round(float(item), 2)) for item in bbox[:4])


def _vlm_configured() -> bool:
    return bool(_vlm_base_url() and _vlm_api_key())


def _vlm_base_url() -> str | None:
    return os.getenv("VLM_BASE_URL") or os.getenv("OPENAI_COMPATIBLE_BASE_URL")


def _vlm_api_key() -> str | None:
    return read_secret("VLM_API_KEY", "OPENAI_COMPATIBLE_API_KEY")


def _vlm_model() -> str:
    return os.getenv("VLM_MODEL") or os.getenv("CHAT_MODEL_NAME") or "doubao-seed-2-0-pro-260215"


def _vlm_api_style() -> str:
    return os.getenv("VLM_API_STYLE") or os.getenv("LLM_API_STYLE") or "responses"


def _page_image_data_url(content: bytes, request: PageParseRequest) -> str:
    file_name = (request.fileName or "").lower()
    file_type = (request.fileType or "").lower()
    if file_name.endswith(".pdf") or file_type.endswith("pdf") or file_type == ".pdf":
        import fitz
        with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as tmp:
            tmp.write(content)
            tmp_path = tmp.name
        try:
            doc = fitz.open(tmp_path)
            page_no = max(1, request.pageNo or 1)
            page = doc.load_page(min(page_no - 1, len(doc) - 1))
            pix = page.get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
            image_bytes = pix.tobytes("png")
        finally:
            try:
                os.remove(tmp_path)
            except OSError:
                pass
        return "data:image/png;base64," + base64.b64encode(image_bytes).decode("ascii")
    return "data:image/png;base64," + base64.b64encode(content).decode("ascii")


async def _call_openai_compatible_vlm(request: PageParseRequest, image_data_url: str) -> dict[str, Any]:
    prompt = (
        "请解析这页文档图像，输出严格 JSON，不要 Markdown。"
        "JSON 字段：success, parser, parserVersion, fullText, blocks, errorMessage, warnings。"
        "blocks 数组元素字段：orderIndex,type,text,pageNo,level,headingPath,bbox,confidence,source。"
        "type 可取 heading, paragraph, table, list, code, image, caption, header, footer。"
        "保留表格为 Markdown，代码块保留原格式。"
    )
    if _vlm_api_style().lower() == "chat_completions":
        return await _call_chat_completions_vlm(prompt, image_data_url)
    return await _call_responses_vlm(prompt, image_data_url)


async def _call_responses_vlm(prompt: str, image_data_url: str) -> dict[str, Any]:
    payload = {
        "model": _vlm_model(),
        "input": [
            {
                "role": "user",
                "content": [
                    {"type": "input_image", "image_url": image_data_url},
                    {"type": "input_text", "text": prompt},
                ],
            }
        ],
        "temperature": 0.1,
        "max_output_tokens": 4096,
    }
    headers = {"Authorization": f"Bearer {_vlm_api_key()}"}
    async with httpx.AsyncClient(timeout=180) as client:
        response = await client.post(_vlm_base_url().rstrip("/") + "/responses", json=payload, headers=headers)
        response.raise_for_status()
        return response.json()


async def _call_chat_completions_vlm(prompt: str, image_data_url: str) -> dict[str, Any]:
    payload = {
        "model": _vlm_model(),
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {"type": "image_url", "image_url": {"url": image_data_url}},
                ],
            }
        ],
        "temperature": 0.1,
        "max_tokens": 4096,
    }
    headers = {"Authorization": f"Bearer {_vlm_api_key()}"}
    async with httpx.AsyncClient(timeout=180) as client:
        response = await client.post(_vlm_base_url().rstrip("/") + "/chat/completions", json=payload, headers=headers)
        response.raise_for_status()
        return response.json()


def _parse_vlm_json_response(request: PageParseRequest, data: dict[str, Any]) -> ParseResponse:
    content = _extract_vlm_text(data)
    if isinstance(content, list):
        content = "".join(part.get("text", "") for part in content if isinstance(part, dict))
    text = str(content).strip()
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        text = text[start:end + 1]
    try:
        import json
        parsed = json.loads(text)
        parsed = _normalize_vlm_parse_response(parsed, request)
        return ParseResponse(**parsed)
    except Exception:
        block = DocumentBlock(
            orderIndex=0,
            type="paragraph",
            text=str(content).strip(),
            pageNo=request.pageNo or 1,
            confidence=0.7,
            source="vlm",
        )
        return ParseResponse(
            success=bool(block.text),
            parser="vlm-page",
            parserVersion=request.parserVersion or "structured-v1",
            fullText=block.text,
            blocks=[block] if block.text else [],
            errorMessage=None if block.text else "VLM returned empty content",
        )


def _normalize_vlm_parse_response(parsed: dict[str, Any], request: PageParseRequest) -> dict[str, Any]:
    parsed["parser"] = "vlm-page"
    parsed["parserVersion"] = request.parserVersion or "structured-v1"
    blocks = parsed.get("blocks")
    if not isinstance(blocks, list):
        blocks = []
    normalized_blocks: list[dict[str, Any]] = []
    for index, block in enumerate(blocks):
        if not isinstance(block, dict):
            continue
        value = dict(block)
        value.setdefault("orderIndex", index)
        value.setdefault("type", "paragraph")
        value.setdefault("text", "")
        value.setdefault("pageNo", request.pageNo or 1)
        value.setdefault("source", "vlm")
        bbox = value.get("bbox")
        if isinstance(bbox, (list, tuple)):
            value["bbox"] = ",".join(str(item) for item in bbox[:4])
        elif isinstance(bbox, dict):
            ordered = [bbox.get(key) for key in ("x0", "y0", "x1", "y1") if bbox.get(key) is not None]
            value["bbox"] = ",".join(str(item) for item in ordered) if ordered else None
        elif bbox is not None:
            value["bbox"] = str(bbox)
        heading_path = value.get("headingPath")
        if not isinstance(heading_path, list):
            value["headingPath"] = []
        normalized_blocks.append(value)
    parsed["blocks"] = normalized_blocks
    if not parsed.get("fullText"):
        parsed["fullText"] = "\n\n".join(str(block.get("text") or "") for block in normalized_blocks).strip()
    parsed["success"] = bool(parsed.get("success", True) and (parsed.get("fullText") or normalized_blocks))
    if not parsed.get("warnings"):
        parsed["warnings"] = []
    if parsed.get("errorMessage") == "":
        parsed["errorMessage"] = None
    return parsed


def _extract_vlm_text(data: dict[str, Any]) -> str:
    if data.get("output_text"):
        return str(data.get("output_text"))
    parts: list[str] = []
    for item in data.get("output") or []:
        for content in item.get("content") or []:
            if not isinstance(content, dict):
                continue
            if content.get("type") in {"output_text", "text"} and content.get("text"):
                parts.append(str(content.get("text")))
    if parts:
        return "".join(parts)
    content = data.get("choices", [{}])[0].get("message", {}).get("content", "")
    if isinstance(content, list):
        return "".join(part.get("text", "") for part in content if isinstance(part, dict))
    return str(content or "")


def _parse_pdf(content: bytes, request: ParseRequest) -> ParseResponse:
    warnings: list[str] = []
    text_blocks = _extract_pdf_text(content, request, warnings)
    if _is_good_blocks(text_blocks):
        return _response(request, "pdf-text", text_blocks, warnings)
    ocr_blocks = _ocr_pdf_pages(content, request, warnings)
    if _is_good_blocks(ocr_blocks):
        return _response(request, "ocr-layout", ocr_blocks, warnings)
    return _failed(request, "PDF OCR produced no reliable text", warnings)


def _parse_image(content: bytes, request: ParseRequest) -> ParseResponse:
    warnings: list[str] = []
    blocks = _ocr_image_bytes(content, page_no=1, start_index=0, warnings=warnings)
    if _is_good_blocks(blocks):
        return _response(request, "ocr-image", blocks, warnings)
    return _failed(request, "Image OCR produced no reliable text", warnings)


def _parse_plain_text(content: bytes, request: ParseRequest) -> ParseResponse:
    text = content.decode("utf-8", errors="ignore")
    blocks = _text_to_blocks(text, source="plain-text", page_no=None, start_index=0)
    return _response(request, "plain-text", blocks, [])


def _extract_pdf_text(content: bytes, request: ParseRequest, warnings: list[str]) -> list[DocumentBlock]:
    try:
        from pypdf import PdfReader
        reader = PdfReader(io.BytesIO(content))
        blocks: list[DocumentBlock] = []
        index = 0
        max_pages = min(len(reader.pages), request.maxPages or 100)
        for page_index in range(max_pages):
            text = reader.pages[page_index].extract_text() or ""
            for block in _text_to_blocks(text, "pdf-text", page_index + 1, index):
                blocks.append(block)
                index += 1
        return blocks
    except Exception as exc:
        warnings.append(f"pdf text extraction failed: {exc}")
        return []


def _ocr_pdf_pages(content: bytes, request: ParseRequest, warnings: list[str]) -> list[DocumentBlock]:
    try:
        import fitz
    except Exception as exc:
        warnings.append(f"PyMuPDF unavailable: {exc}")
        return []
    blocks: list[DocumentBlock] = []
    index = 0
    with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as tmp:
        tmp.write(content)
        tmp_path = tmp.name
    try:
        doc = fitz.open(tmp_path)
        max_pages = min(len(doc), request.maxPages or 100)
        for page_index in range(max_pages):
            page = doc.load_page(page_index)
            pix = page.get_pixmap(matrix=fitz.Matrix(2, 2), alpha=False)
            image_bytes = pix.tobytes("png")
            page_blocks = _ocr_image_bytes(image_bytes, page_index + 1, index, warnings)
            blocks.extend(page_blocks)
            index += len(page_blocks)
        return blocks
    finally:
        try:
            os.remove(tmp_path)
        except OSError:
            pass


def _ocr_image_bytes(content: bytes, page_no: int, start_index: int, warnings: list[str]) -> list[DocumentBlock]:
    try:
        from PIL import Image
        import pytesseract
    except Exception as exc:
        warnings.append(f"OCR runtime unavailable: {exc}")
        return []
    try:
        image = Image.open(io.BytesIO(content))
        data = pytesseract.image_to_data(image, output_type=pytesseract.Output.DICT, lang=os.getenv("OCR_LANG", "chi_sim+eng"))
        lines: dict[tuple[int, int, int], list[tuple[int, str, float, tuple[int, int, int, int]]]] = {}
        for i, text in enumerate(data.get("text", [])):
            value = (text or "").strip()
            if not value:
                continue
            key = (data["block_num"][i], data["par_num"][i], data["line_num"][i])
            conf = _safe_float(data["conf"][i])
            bbox = (data["left"][i], data["top"][i], data["width"][i], data["height"][i])
            lines.setdefault(key, []).append((data["word_num"][i], value, conf, bbox))
        blocks: list[DocumentBlock] = []
        index = start_index
        for _, words in sorted(lines.items()):
            words = sorted(words, key=lambda item: item[0])
            text = " ".join(item[1] for item in words)
            confidence_values = [item[2] for item in words if item[2] >= 0]
            confidence = sum(confidence_values) / len(confidence_values) / 100 if confidence_values else 0.0
            block_type = _detect_block_type(text)
            blocks.append(DocumentBlock(
                orderIndex=index,
                type=block_type,
                text=text,
                pageNo=page_no,
                level=1 if block_type == "heading" else None,
                confidence=confidence,
                source="ocr",
            ))
            index += 1
        return _merge_heading_paths(blocks)
    except Exception as exc:
        warnings.append(f"OCR failed: {exc}")
        return []


def _text_to_blocks(text: str, source: str, page_no: int | None, start_index: int) -> list[DocumentBlock]:
    blocks: list[DocumentBlock] = []
    heading_path: list[str] = []
    index = start_index
    for part in re.split(r"\n\s*\n", text or ""):
        value = " ".join(line.strip() for line in part.splitlines() if line.strip()).strip()
        if not value:
            continue
        block_type = _detect_block_type(value)
        level = 1 if block_type == "heading" else None
        if block_type == "heading":
            heading_path = [value]
        blocks.append(DocumentBlock(
            orderIndex=index,
            type=block_type,
            text=value,
            pageNo=page_no,
            level=level,
            headingPath=list(heading_path),
            confidence=1.0,
            source=source,
        ))
        index += 1
    return blocks


def _detect_block_type(text: str) -> str:
    value = text.strip()
    if "|" in value and value.count("|") >= 2:
        return "table"
    if len(value) <= 80 and re.search(r"(^#{1,6}\s+|第.+[章节]|^\d+(\.\d+)*\s+)", value):
        return "heading"
    if re.match(r"^([-*+]|\d+[.)])\s+", value):
        return "list"
    return "ocr_text"


def _merge_heading_paths(blocks: list[DocumentBlock]) -> list[DocumentBlock]:
    heading_path: list[str] = []
    for block in blocks:
        if block.type == "heading":
            heading_path = [block.text]
        else:
            block.headingPath = list(heading_path)
    return blocks


def _response(request: ParseRequest, parser: str, blocks: list[DocumentBlock], warnings: list[str]) -> ParseResponse:
    full_text = "\n\n".join(block.text for block in blocks if block.text)
    return ParseResponse(
        success=bool(full_text.strip()),
        parser=parser,
        parserVersion=request.parserVersion or "structured-v1",
        fullText=full_text,
        blocks=blocks,
        errorMessage=None if full_text.strip() else "No text extracted",
        warnings=warnings,
    )


def _failed(request: ParseRequest, message: str, warnings: list[str] | None = None) -> ParseResponse:
    return ParseResponse(
        success=False,
        parser="ocr-layout",
        parserVersion=request.parserVersion or "structured-v1",
        errorMessage=message,
        warnings=warnings or [],
    )


def _is_good_blocks(blocks: list[DocumentBlock]) -> bool:
    text = "\n".join(block.text for block in blocks if block.text)
    valid = sum(1 for ch in text if ch.isalnum() or "\u4e00" <= ch <= "\u9fff")
    return len(text.strip()) >= 20 and valid / max(1, len(text)) > 0.25


def _safe_float(value: Any) -> float:
    try:
        return float(value)
    except Exception:
        return -1.0


def _module_available(name: str) -> bool:
    try:
        __import__(name)
        return True
    except Exception:
        return False
