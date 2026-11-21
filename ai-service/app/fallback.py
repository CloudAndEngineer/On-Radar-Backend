"""Gemini가 느리거나 실패할 때 돌려줄 고정 응답과, 위기 상황의 고정 대사.

위기 대사는 Gemini가 만들지 않고 항상 여기 문장을 쓴다 (대화 설계서 '안전장치').
"""
from typing import Optional

from .schemas import (
    ChatResponse,
    ClassifyResponse,
    RiskSummaryRequest,
    RiskSummaryResponse,
)

CHAT_FALLBACK = "말씀 고마워요. 오늘도 편안한 하루 보내세요."

CRISIS_MIND = "말씀해 주셔서 고마워요. 지금 복지사 선생님께 바로 알려서 연락드리도록 할게요."
CRISIS_BODY = "많이 불편하시군요. 복지사 선생님께 바로 알릴게요. 급하시면 119에 전화하세요."

ITEM_LABEL = {"NO_RESPONSE": "체크 놓침", "WAKE_TIME": "아침 응답", "CONDITION": "몸 상태", "MEAL": "식사"}
TRIGGER_LABEL = {
    "MORNING_MISSED_2DAYS": "아침 응답 2일 연속 없음",
    "FULL_DAY_MISSED": "하루 종일 응답 없음",
    "BAD_CONDITION_3DAYS": "'안 좋아요' 3일 연속",
    "HELP_BUTTON": "[도와주세요] 누름",
    "CRISIS_TALK": "대화 중 위기 표현",
}
LEVEL_ACTION = {
    "DANGER": "방문 상담을 권장해요.",
    "CAUTION": "안부 전화를 권장해요.",
    "NORMAL": "평소와 비슷해요.",
}


# Gemini가 늦을 때: 대화를 끊지 않고 맞장구 + 열린 질문으로 이어 간다. 마지막 턴이면 인사로 마무리.
CHAT_OPEN = {
    "MORNING": "체크해 주셔서 고마워요. 오늘은 뭐 하실 계획이세요?",
    "EVENING": "오늘도 체크해 주셔서 고마워요. 오늘 하루는 어떠셨어요?",
}
CHAT_CONTINUE = "그러셨군요. 조금 더 이야기해 주실래요?"
CHAT_CLOSE = {
    "MORNING": "말씀 고마워요. 저녁에 또 여쭤볼게요.",
    "EVENING": "말씀 고마워요. 내일 아침에 또 여쭤볼게요.",
}


def chat_fallback(session: Optional[str] = None, turn: int = 99, max_turns: int = 3) -> ChatResponse:
    """첫 턴이면 기본 인사로 열고, 중간 턴이면 이어 가고, 마지막 턴이면 인사로 끝낸다."""
    if session and turn == 1 and max_turns > 1:  # 첫마디가 늦으면 기본 인사로 연다
        return ChatResponse(reply=CHAT_OPEN[session], endConversation=False, crisisFlag=False)
    if session and 1 < turn < max_turns:
        return ChatResponse(reply=CHAT_CONTINUE, endConversation=False, crisisFlag=False)
    return ChatResponse(reply=CHAT_CLOSE.get(session or "", CHAT_FALLBACK), endConversation=True, crisisFlag=False)


def classify_fallback() -> ClassifyResponse:
    # 위기 여부는 Core가 단어 목록으로 한 번 더 검사한다 (API 명세서 '실패할 때').
    return ClassifyResponse(summary="", lonelinessSignal=False, crisisFlag=False)


def _direction(item: str, baseline, recent) -> str:
    try:
        if item == "WAKE_TIME":
            return "늦어짐" if str(recent) > str(baseline) else "비슷"
        if item == "NO_RESPONSE":
            return "늘어남" if float(recent) > float(baseline) else "비슷"
        return "↓" if float(recent) < float(baseline) else "비슷"
    except (TypeError, ValueError):
        return "변화"


def risk_summary_fallback(req: RiskSummaryRequest) -> RiskSummaryResponse:
    parts = [TRIGGER_LABEL[t] for t in req.immediateTriggers[:2]]
    if req.riskLevel == "NORMAL" and not parts:
        return RiskSummaryResponse(summary=LEVEL_ACTION["NORMAL"])
    top = sorted(req.reasons, key=lambda r: r.contribution or 0, reverse=True)
    for r in top:
        if len(parts) >= 2:
            break
        if (r.contribution or 0) > 0:
            parts.append(f"{ITEM_LABEL[r.item]} {_direction(r.item, r.baseline, r.recent)}")
    if req.lonelinessDays > 0:
        parts.append("외로움 신호")
    level = "DANGER" if req.immediateTriggers else req.riskLevel
    head = " · ".join(parts)
    return RiskSummaryResponse(summary=f"{head}. {LEVEL_ACTION[level]}" if head else LEVEL_ACTION[level])
