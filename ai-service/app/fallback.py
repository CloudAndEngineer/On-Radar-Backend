"""Gemini가 느리거나 실패할 때 돌려줄 고정 응답과, 위기 상황의 고정 대사.

위기 대사는 Gemini가 만들지 않고 항상 여기 문장을 쓴다 (대화 설계서 '안전장치').
"""
from .schemas import (
    ChatResponse,
    ClassifyResponse,
    RiskSummaryRequest,
    RiskSummaryResponse,
)

CHAT_FALLBACK = "말씀 고마워요. 오늘도 편안한 하루 보내세요."

CRISIS_MIND = "말씀해 주셔서 고마워요. 지금 복지사 선생님께 바로 알려서 연락드리도록 할게요."
CRISIS_BODY = "많이 불편하시군요. 복지사 선생님께 바로 알릴게요. 급하시면 119에 전화하세요."

ITEM_LABEL = {"MEAL": "식사", "CONDITION": "몸 상태", "WAKE_TIME": "기상"}
LEVEL_ACTION = {
    "DANGER": "방문 상담을 권장해요.",
    "CAUTION": "안부 전화를 권장해요.",
    "NORMAL": "평소와 비슷해요.",
}


def chat_fallback() -> ChatResponse:
    return ChatResponse(reply=CHAT_FALLBACK, endConversation=True, crisisFlag=False)


def classify_fallback() -> ClassifyResponse:
    # 위기 여부는 Core가 단어 목록으로 한 번 더 검사한다 (API 명세서 '실패할 때').
    return ClassifyResponse(summary="", lonelinessSignal=False, crisisFlag=False)


def _direction(item: str, baseline, recent) -> str:
    try:
        if item == "WAKE_TIME":
            return "늦어짐" if str(recent) > str(baseline) else "비슷"
        return "↓" if float(recent) < float(baseline) else "비슷"
    except (TypeError, ValueError):
        return "변화"


def risk_summary_fallback(req: RiskSummaryRequest) -> RiskSummaryResponse:
    if req.riskLevel == "NORMAL" or not req.reasons:
        return RiskSummaryResponse(summary=LEVEL_ACTION[req.riskLevel])
    top = sorted(req.reasons, key=lambda r: r.contribution or 0, reverse=True)[:2]
    parts = [f"{ITEM_LABEL[r.item]} {_direction(r.item, r.baseline, r.recent)}" for r in top]
    if req.lonelinessDays > 0:
        parts.append("외로움 신호")
    return RiskSummaryResponse(summary=f"{' · '.join(parts)}. {LEVEL_ACTION[req.riskLevel]}")
