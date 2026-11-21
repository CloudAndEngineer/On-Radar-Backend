"""엔드포인트 뒤에서 도는 처리. 실패하면 언제나 fallback으로 돌아간다."""
import logging
import os
from typing import Literal, Optional

from pydantic import BaseModel

from . import fallback, prompts, safety
from .gemini_client import SafetyBlocked, generate_json, is_configured
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


class _ChatOut(BaseModel):
    """Gemini가 돌려주는 대화 결과. crisisType은 고정 대사를 고르는 데만 쓰고 Core에는 보내지 않는다."""

    reply: str
    endConversation: bool
    crisisFlag: bool
    crisisType: Optional[Literal["MIND", "BODY"]] = None


def _gemini_ready() -> bool:
    return prompts.READY and is_configured()


def _crisis_reply(kind: Optional[str]) -> ChatResponse:
    line = fallback.CRISIS_BODY if kind == "BODY" else fallback.CRISIS_MIND
    return ChatResponse(reply=line, endConversation=True, crisisFlag=True)


def _senior_texts(history) -> list[str]:
    return [u.text for u in history if u.role == "senior"]


async def chat(req: ChatRequest) -> ChatResponse:
    # 1) 단어 규칙: Gemini보다 먼저, 설정·네트워크와 상관없이 항상 돈다
    seniors = _senior_texts(req.history)
    latest = seniors[-1] if seniors else ""
    kind = safety.detect(latest)
    if kind:
        return _crisis_reply(kind)

    if not _gemini_ready():
        return fallback.chat_fallback(req.session, req.turn, req.maxTurns)
    try:
        out = await generate_json(
            prompts.CHAT_SYSTEM, prompts.as_user_input(req), _ChatOut, temperature=0.7, timeout=CHAT_TIMEOUT
        )
    except SafetyBlocked:
        # 2) 필터에 막혔다 = 위험한 말일 가능성이 높다. 관용 표현만 있을 때만 위기에서 뺀다
        if safety.is_idiom_only(latest):
            return fallback.chat_fallback(req.session, req.turn, req.maxTurns)
        return _crisis_reply("MIND")
    except Exception:
        log.exception("chat 실패 → 대체 응답")
        return fallback.chat_fallback(req.session, req.turn, req.maxTurns)

    if out.crisisFlag:  # 위기 대사는 항상 고정 문장 (설계서 '안전장치')
        return _crisis_reply(out.crisisType)
    end = out.endConversation or req.turn >= req.maxTurns
    return ChatResponse(reply=out.reply, endConversation=end, crisisFlag=False)


async def classify(req: ClassifyRequest) -> ClassifyResponse:
    seniors = _senior_texts(req.transcript)
    rule_kind, rule_text = safety.scan(seniors)

    res: Optional[ClassifyResponse] = None
    blocked = False
    if _gemini_ready():
        try:
            res = await generate_json(
                prompts.CLASSIFY_SYSTEM, prompts.as_user_input(req), ClassifyResponse, temperature=0.2, timeout=BG_TIMEOUT
            )
        except SafetyBlocked:
            blocked = True
        except Exception:
            log.exception("classify 실패 → 대체 응답")
    if res is None:
        res = fallback.classify_fallback()
        if blocked and not all(safety.is_idiom_only(t) for t in seniors if t.strip()):
            res.crisisFlag = True
            res.crisisEvidence = rule_text or (seniors[-1] if seniors else None)

    # 단어 규칙은 Gemini 결과와 OR로 합친다
    if rule_kind and not res.crisisFlag:
        res.crisisFlag = True
        res.crisisEvidence = rule_text
    if res.crisisFlag and not res.summary:
        res.summary = "위기 표현 있음, 바로 확인 필요"
    return res


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
