import base64
import io
import os
import re
import tempfile
from typing import Any

import httpx
from fastapi import FastAPI
from pydantic import BaseModel, Field

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
        "vlmEnabled": bool(os.getenv("VLM_PARSE_ENDPOINT")),
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


@app.post("/parse/page", response_model=ParseResponse)
async def parse_page(request: PageParseRequest) -> ParseResponse:
    endpoint = os.getenv("VLM_PARSE_ENDPOINT")
    if not endpoint:
        return ParseResponse(
            success=False,
            parser="vlm-page",
            parserVersion=request.parserVersion or "structured-v1",
            errorMessage="VLM parser is disabled: VLM_PARSE_ENDPOINT is not configured",
        )
    try:
        async with httpx.AsyncClient(timeout=120) as client:
            response = await client.post(endpoint, json=request.model_dump())
            response.raise_for_status()
            data = response.json()
        return ParseResponse(**data)
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
