"""엔드포인트 뒤에서 도는 처리. 실패하면 언제나 fallback으로 돌아간다."""
import logging
import os

from . import fallback, prompts
from .gemini_client import generate_json, is_configured
from .schemas import (
    ChatRequest,
    ChatResponse,
    ClassifyRequest,
    ClassifyResponse,
    RiskSummaryRequest,
    RiskSummaryResponse,
)

log = logging.getLogger("service")

# 대화는 어르신이 기다리므로 짧게, 분류·요약은 대화가 끝난 뒤 뒤에서 돌므로 길게
CHAT_TIMEOUT = float(os.getenv("GEMINI_TIMEOUT_CHAT", "4.5"))
BG_TIMEOUT = float(os.getenv("GEMINI_TIMEOUT_BG", "12"))


def _gemini_ready() -> bool:
    return prompts.READY and is_configured()


async def chat(req: ChatRequest) -> ChatResponse:
    if not _gemini_ready():
        return fallback.chat_fallback()
    try:
        res = await generate_json(
            prompts.CHAT_SYSTEM, prompts.as_user_input(req), ChatResponse, temperature=0.7, timeout=CHAT_TIMEOUT
        )
    except Exception:
        log.exception("chat 실패 → 대체 응답")
        return fallback.chat_fallback()

    if res.crisisFlag:  # 위기 대사는 항상 고정 문장 (설계서 '안전장치')
        return ChatResponse(reply=fallback.CRISIS_MIND, endConversation=True, crisisFlag=True)
    if req.turn >= req.maxTurns:
        res.endConversation = True
    return res


async def classify(req: ClassifyRequest) -> ClassifyResponse:
    if not _gemini_ready():
        return fallback.classify_fallback()
    try:
        return await generate_json(
            prompts.CLASSIFY_SYSTEM, prompts.as_user_input(req), ClassifyResponse, temperature=0.2, timeout=BG_TIMEOUT
        )
    except Exception:
        log.exception("classify 실패 → 대체 응답")
        return fallback.classify_fallback()


async def risk_summary(req: RiskSummaryRequest) -> RiskSummaryResponse:
    if not _gemini_ready():
        return fallback.risk_summary_fallback(req)
    try:
        return await generate_json(
            prompts.RISK_SUMMARY_SYSTEM, prompts.as_user_input(req), RiskSummaryResponse, temperature=0.2, timeout=BG_TIMEOUT
        )
    except Exception:
        log.exception("risk-summary 실패 → 대체 응답")
        return fallback.risk_summary_fallback(req)
