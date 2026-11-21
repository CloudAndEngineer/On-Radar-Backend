"""프롬프트 자리.

여기 문구는 본선 당일에 '토닥 AI 대화 설계서'를 보고 채운다.
(사전 준비로 프롬프트 초안을 써 와도 되는지 운영진 답을 받으면, 그때 미리 채워도 된다.)

READY = False인 동안 모든 엔드포인트는 fallback.py의 고정 응답을 돌려준다.
그래서 Core는 프롬프트가 없어도 내일 아침부터 연결 작업을 할 수 있다.
"""
import json

from pydantic import BaseModel

READY = False  # 프롬프트를 채운 뒤 True로

# 설계서: 말투 규칙 · 턴 흐름 · 기억 쓰는 법 · 안전장치
CHAT_SYSTEM = """TODO"""

# 설계서: 분류 기준 (외로움은 남발하지 않게, 위기는 놓치지 않게)
CLASSIFY_SYSTEM = """TODO"""

# 설계서: 위험 요약 작성 규칙
RISK_SUMMARY_SYSTEM = """TODO"""


def as_user_input(req: BaseModel) -> str:
    """요청을 그대로 JSON 문자열로 넘긴다. 필요하면 당일에 읽기 쉬운 형태로 바꾼다."""
    return json.dumps(req.model_dump(), ensure_ascii=False)
