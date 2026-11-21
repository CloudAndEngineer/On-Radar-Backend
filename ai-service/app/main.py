"""토닥 AI 서비스. Core만 호출하는 내부 서비스이고, 상태를 저장하지 않는다.

실행: uvicorn app.main:app --reload --port 8001
문서: http://localhost:8001/docs
"""
import logging
import os

from fastapi import FastAPI

from . import prompts, service
from .gemini_client import is_configured
from .schemas import (
    ChatRequest,
    ChatResponse,
    ClassifyRequest,
    ClassifyResponse,
    RiskSummaryRequest,
    RiskSummaryResponse,
)

try:  # 로컬에서는 .env를 읽고, Render에서는 대시보드 환경변수를 쓴다
    from dotenv import load_dotenv

    load_dotenv()
except ImportError:
    pass

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))

app = FastAPI(title="토닥 AI 서비스", version="0.1.0")


@app.get("/health")
async def health() -> dict:
    return {
        "status": "ok",
        "geminiConfigured": is_configured(),
        "promptsReady": prompts.READY,
        "model": os.getenv("GEMINI_MODEL") or None,
    }


@app.post("/v1/chat", response_model=ChatResponse)
async def chat(req: ChatRequest) -> ChatResponse:
    return await service.chat(req)


@app.post("/v1/classify", response_model=ClassifyResponse)
async def classify(req: ClassifyRequest) -> ClassifyResponse:
    return await service.classify(req)


@app.post("/v1/risk-summary", response_model=RiskSummaryResponse)
async def risk_summary(req: RiskSummaryRequest) -> RiskSummaryResponse:
    return await service.risk_summary(req)
