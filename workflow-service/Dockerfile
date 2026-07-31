FROM python:3.11-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN groupadd --system --gid 10001 workflow \
    && useradd --system --uid 10001 --gid workflow --home-dir /nonexistent workflow

COPY pyproject.toml README.md ./
COPY mini_agent_flow ./mini_agent_flow
RUN python -m pip install --upgrade pip \
    && python -m pip install .

USER 10001:10001
EXPOSE 8003

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8003/health', timeout=2)"]

CMD ["uvicorn", "mini_agent_flow.service.api:app", "--host", "0.0.0.0", "--port", "8003", "--workers", "1"]
