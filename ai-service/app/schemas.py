"""요청·응답 형식. 토닥 API 명세서의 'AI 서비스 입출력' 섹션과 1:1로 맞춘다.

필드 이름을 바꾸면 Core(황태준)와 명세서도 같이 바꿔야 한다.
"""
from typing import Literal, Optional, Union

from pydantic import BaseModel, Field

Session = Literal["MORNING", "EVENING"]
RiskLevel = Literal["NORMAL", "CAUTION", "DANGER"]
# 11/21 점수 기준: 반복 무응답 30 · 응답 지연 30 · 컨디션 20 · 식사 20 (복약·수면 제외)
ReasonItem = Literal["NO_RESPONSE", "WAKE_TIME", "CONDITION", "MEAL"]
# 점수와 상관없이 바로 '위험'으로 올리는 경우
ImmediateTrigger = Literal[
    "MORNING_MISSED_2DAYS",  # 아침 2일 연속 미응답
    "FULL_DAY_MISSED",  # 아침·저녁 모두 미응답인 날
    "BAD_CONDITION_3DAYS",  # '안 좋아요' 3일 연속
    "HELP_BUTTON",  # [도와주세요]
    "CRISIS_TALK",  # 대화 중 위기 표현
]


class Senior(BaseModel):
    name: str
    honorific: str = "어르신"  # 예: 할머니, 할아버지


class TodayCheck(BaseModel):
    # 아침: 몸 상태 하나, 저녁: 밥 하나 (11/21 수면·복약 질문 제외)
    conditionScore: Optional[int] = Field(None, ge=0, le=2)  # 2 좋아요 · 1 그저 그래요 · 0 안 좋아요
    mealCount: Optional[int] = Field(None, ge=0, le=3)
    morningRespondedAt: Optional[str] = None  # "08:12" 처럼. 기상이 늦어지는 것도 대화 소재


class Utterance(BaseModel):
    role: Literal["ai", "senior"]
    text: str


# ---------- /v1/chat ----------
class ChatRequest(BaseModel):
    senior: Senior
    session: Session
    todayCheck: TodayCheck = Field(default_factory=TodayCheck)
    memory: list[str] = Field(default_factory=list)  # 최근 대화 요약, 최신순 최대 3개
    history: list[Utterance] = Field(default_factory=list)  # 비어 있으면 1턴(AI가 먼저 말 걸기)
    turn: int = Field(1, ge=1)
    maxTurns: int = Field(3, ge=1)


class ChatResponse(BaseModel):
    reply: str
    endConversation: bool
    crisisFlag: bool


# ---------- /v1/classify ----------
class ClassifyRequest(BaseModel):
    transcript: list[Utterance]
    todayCheck: TodayCheck = Field(default_factory=TodayCheck)


class ClassifyResponse(BaseModel):
    summary: str  # 40자 안쪽, 다음 대화의 memory로 그대로 쓰임
    lonelinessSignal: bool
    lonelinessEvidence: Optional[str] = None  # 어르신 말 그대로 인용
    crisisFlag: bool
    crisisEvidence: Optional[str] = None


# ---------- /v1/risk-summary ----------
class Reason(BaseModel):
    """baseline은 처음 7일 평소값, recent는 최근 3일 값.

    NO_RESPONSE: 3일 기준 놓친 횟수 (baseline은 7일 값을 3일로 바꾼 값, 예 0.9)
    WAKE_TIME:   아침 응답 시각 평균 "07:05" (놓친 아침은 빼고 계산)
    CONDITION:   몸 상태 평균 0~2 (2 좋아요 · 1 보통 · 0 안 좋아요)
    MEAL:        하루 끼니 수 평균 0~3
    """

    item: ReasonItem
    baseline: Union[float, str]
    recent: Union[float, str]
    contribution: Optional[float] = None  # 항목 점수(예 27.7). 큰 순서로 2개만 요약에 씀


class RiskSummaryRequest(BaseModel):
    seniorName: str
    riskLevel: RiskLevel
    score: int = Field(ge=0, le=100)  # 즉시 위험이면 Core가 70으로 보냄
    reasons: list[Reason] = Field(default_factory=list)
    immediateTriggers: list[ImmediateTrigger] = Field(default_factory=list)
    lonelinessDays: int = 0


class RiskSummaryResponse(BaseModel):
    summary: str  # 90자 안쪽
