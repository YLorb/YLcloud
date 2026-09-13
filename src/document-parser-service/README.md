# Document Parser Service

This service converts dirty documents into structured RAG blocks.

## APIs

`GET /health`

`POST /parse/ocr`

```json
{
  "fileUuid": "object-name-or-id",
  "fileHash": "sha256",
  "fileName": "scan.pdf",
  "fileType": "pdf",
  "objectUrl": "http://optional-presigned-url",
  "maxPages": 100,
  "parserVersion": "structured-v1"
}
```

`POST /parse/page`

Reserved for VLM page understanding. It returns a disabled response unless a VLM provider is configured.

## Notes

The OCR path uses optional runtime capabilities:

- direct text extraction for PDF via `pypdf`
- page/image OCR via Tesseract when available
- PDF page rendering via PyMuPDF when available

If OCR dependencies are unavailable or the document cannot be fetched, the service returns a failed response with an explicit error message so the Java RAG flow can fall back safely.
