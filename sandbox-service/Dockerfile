FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /app
RUN apt-get update && apt-get install --yes --no-install-recommends docker.io ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 sandbox && useradd --system --uid 10001 --gid sandbox --home-dir /nonexistent sandbox
COPY pyproject.toml ./
COPY sandbox_service ./sandbox_service
RUN python -m pip install .
USER 10001:10001
EXPOSE 8004
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8004/health', timeout=2)"]
CMD ["uvicorn", "sandbox_service.api:app", "--host", "0.0.0.0", "--port", "8004", "--workers", "1"]
