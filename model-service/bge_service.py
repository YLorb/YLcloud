import os
from threading import Lock
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from FlagEmbedding import BGEM3FlagModel, FlagReranker


app = FastAPI(title="ylcloud BGE model service")

EMBEDDING_MODEL_NAME = os.getenv("EMBEDDING_MODEL_NAME", "BAAI/bge-m3")
RERANK_MODEL_NAME = os.getenv("RERANK_MODEL_NAME", "BAAI/bge-reranker-v2-m3")
CHAT_MODEL_NAME = os.getenv("CHAT_MODEL_NAME", "Qwen/Qwen2.5-7B-Instruct")
MODEL_CACHE_DIR = os.getenv("MODEL_CACHE_DIR")
USE_FP16 = os.getenv("USE_FP16", "true").lower() == "true"
OPENAI_COMPATIBLE_BASE_URL = os.getenv("OPENAI_COMPATIBLE_BASE_URL")
OPENAI_COMPATIBLE_API_KEY = os.getenv("OPENAI_COMPATIBLE_API_KEY")

if MODEL_CACHE_DIR:
    os.environ.setdefault("HF_HOME", MODEL_CACHE_DIR)
    os.environ.setdefault("TRANSFORMERS_CACHE", MODEL_CACHE_DIR)

embedding_model = None
reranker = None
embedding_lock = Lock()
reranker_lock = Lock()


def get_embedding_model():
    global embedding_model
    if embedding_model is None:
        with embedding_lock:
            if embedding_model is None:
                embedding_model = BGEM3FlagModel(EMBEDDING_MODEL_NAME, use_fp16=USE_FP16)
    return embedding_model


def get_reranker():
    global reranker
    if reranker is None:
        with reranker_lock:
            if reranker is None:
                reranker = FlagReranker(RERANK_MODEL_NAME, use_fp16=USE_FP16)
    return reranker


class EmbedRequest(BaseModel):
    texts: list[str]
    normalize: bool = True


class EmbedResponse(BaseModel):
    model: str
    dimension: int
    vectors: list[list[float]]


class RerankRequest(BaseModel):
    query: str
    documents: list[str]
    topK: Optional[int] = None


class RerankResult(BaseModel):
    index: int
    score: float


class RerankResponse(BaseModel):
    model: str
    results: list[RerankResult]


class ChatRequest(BaseModel):
    model: Optional[str] = None
    systemPrompt: Optional[str] = None
    question: str
    contexts: list[str]
    maxTokens: Optional[int] = 1024
    temperature: Optional[float] = 0.2


class ChatResponse(BaseModel):
    model: str
    answer: str
    promptTokens: int = 0
    completionTokens: int = 0
    totalTokens: int = 0


@app.get("/health")
def health():
    return {
        "status": "ok",
        "embeddingModel": EMBEDDING_MODEL_NAME,
        "rerankModel": RERANK_MODEL_NAME,
        "chatModel": CHAT_MODEL_NAME,
        "chatEnabled": bool(OPENAI_COMPATIBLE_BASE_URL and OPENAI_COMPATIBLE_API_KEY),
        "embeddingLoaded": embedding_model is not None,
        "rerankerLoaded": reranker is not None,
        "cacheDir": MODEL_CACHE_DIR,
        "useFp16": USE_FP16,
    }


@app.post("/embed", response_model=EmbedResponse)
def embed(request: EmbedRequest):
    output = get_embedding_model().encode(
        request.texts,
        batch_size=8,
        max_length=8192,
        return_dense=True,
        return_sparse=False,
        return_colbert_vecs=False,
    )
    vectors = output["dense_vecs"]
    return EmbedResponse(
        model=EMBEDDING_MODEL_NAME,
        dimension=len(vectors[0]) if len(vectors) > 0 else 0,
        vectors=vectors.tolist(),
    )


@app.post("/rerank", response_model=RerankResponse)
def rerank(request: RerankRequest):
    pairs = [[request.query, document] for document in request.documents]
    try:
        scores = get_reranker().compute_score(pairs, normalize=True)
    except TypeError:
        scores = get_reranker().compute_score(pairs)
    if not isinstance(scores, list):
        try:
            scores = scores.tolist()
        except AttributeError:
            scores = [scores]
    results = [
        RerankResult(index=index, score=float(score))
        for index, score in enumerate(scores)
    ]
    results.sort(key=lambda result: result.score, reverse=True)
    if request.topK is not None and request.topK > 0:
        results = results[:request.topK]
    return RerankResponse(
        model=RERANK_MODEL_NAME,
        results=results,
    )


@app.post("/chat", response_model=ChatResponse)
def chat(request: ChatRequest):
    if not OPENAI_COMPATIBLE_BASE_URL or not OPENAI_COMPATIBLE_API_KEY:
        raise HTTPException(
            status_code=503,
            detail="OPENAI_COMPATIBLE_BASE_URL and OPENAI_COMPATIBLE_API_KEY are required for chat",
        )
    model = request.model or CHAT_MODEL_NAME
    prompt = build_chat_prompt(request)
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": request.systemPrompt or ""},
            {"role": "user", "content": prompt},
        ],
        "temperature": request.temperature,
        "max_tokens": request.maxTokens,
    }
    headers = {"Authorization": f"Bearer {OPENAI_COMPATIBLE_API_KEY}"}
    with httpx.Client(timeout=120.0) as client:
        response = client.post(
            OPENAI_COMPATIBLE_BASE_URL.rstrip("/") + "/chat/completions",
            json=payload,
            headers=headers,
        )
        response.raise_for_status()
        data = response.json()
    answer = data["choices"][0]["message"]["content"]
    usage = data.get("usage") or {}
    return ChatResponse(
        model=model,
        answer=answer,
        promptTokens=usage.get("prompt_tokens", 0),
        completionTokens=usage.get("completion_tokens", 0),
        totalTokens=usage.get("total_tokens", 0),
    )


def build_chat_prompt(request: ChatRequest) -> str:
    contexts = "\n\n".join(request.contexts)
    return (
        "请仅根据以下知识库上下文回答问题。"
        "如果上下文中没有答案，请回答“无法从当前知识库回答”。\n\n"
        f"知识库上下文：\n{contexts}\n\n"
        f"问题：{request.question}"
    )
