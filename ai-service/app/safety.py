"""위기 단어 규칙. Gemini보다 먼저, 그리고 Gemini가 실패해도 항상 돈다 (대화 설계서 '안전장치').

- 음성 인식은 띄어쓰기가 흔들리므로 공백을 지우고 비교한다.
- 놓치는 것보다 한 번 더 확인하는 쪽을 택한다. 단, "아파 죽겠어" 같은 관용 표현은 제외한다.
- 규칙에 걸리면 Gemini 결과와 상관없이 위기로 본다 (OR).
"""
import re
from typing import Iterable, Literal, Optional

CrisisType = Literal["MIND", "BODY"]

# 몸의 응급: 먼저 검사한다 ("숨이 안 쉬어져서 죽겠어"는 BODY)
_BODY = [
    r"넘어(져|졌|짐).{0,10}(못|안)일어",
    r"(못|안)일어나겠",
    r"숨이?(안|못)(쉬|셔|쉐)",
    r"숨(이)?막",
    r"숨(쉬기|이)?(가)?(힘들|어렵|차)",
    r"가슴이?.{0,6}(답답|조여|쥐어|짓눌|터질|찢어)",
    r"가슴이?.{0,4}(너무|많이|엄청)아",
    r"쓰러(졌|질것|지겠)",
    r"피가.{0,4}(안멈|많이|계속)",
    r"(말이|말을)(안|못)나",
    r"한쪽.{0,6}(힘이없|마비|안움직)",
    r"119",
]

# 마음의 위기
_MIND = [
    r"죽고싶",
    r"죽어(버|야겠|야지|야하나)",
    r"죽을(래|란다|거야|까)",
    r"그만살",
    r"살기(싫|가싫)",
    r"살고싶지(않|가않)",
    r"살아(서|봤자)?뭐(하|해)",
    r"살아서뭣",
    r"사는게(무슨|뭔)의미",
    r"사라지고싶",
    r"없어지는게나",
    r"없어졌으면",
    r"(안|못)일어났으면",
    r"(눈을?|깨지)(안|않)?(떴으면|았으면|않았으면)",
    r"(다시는|영영)(안|못)깼으면",
    r"끝내고싶",
    r"목숨",
    r"자살",
    r"뛰어내리",
    r"(약|수면제)을?다먹",
    r"살만큼살았",
    r"(영감|할멈|남편|마누라|아내).{0,6}따라가",
    r"(얼른|빨리|어서)데려갔으면",
    r"자는듯이가",
]

# 관용 표현: "○○ 죽겠어"는 위기가 아니다
_IDIOM = re.compile(r"(아파|배고파|더워|추워|힘들어|졸려|심심해|피곤해|웃겨|좋아|귀찮아|바빠|답답해|미치겠고|짜증나)죽겠")

_BODY_RE = [re.compile(p) for p in _BODY]
_MIND_RE = [re.compile(p) for p in _MIND]


def _norm(text: str) -> str:
    return re.sub(r"\s+", "", text or "")


def detect(text: str) -> Optional[CrisisType]:
    """한 문장에서 위기 유형을 찾는다. 없으면 None."""
    t = _norm(text)
    if not t:
        return None
    if any(r.search(t) for r in _BODY_RE):
        return "BODY"
    if any(r.search(t) for r in _MIND_RE):
        return "MIND"
    return None


def is_idiom_only(text: str) -> bool:
    """관용 표현 '○○ 죽겠어'가 있고 다른 위기 단어는 없는 경우."""
    return bool(_IDIOM.search(_norm(text))) and detect(text) is None


def scan(texts: Iterable[str]) -> tuple[Optional[CrisisType], Optional[str]]:
    """여러 문장 중 위기 문장을 찾아 (유형, 그 문장)을 돌려준다. BODY가 있으면 BODY를 우선한다."""
    found: tuple[Optional[CrisisType], Optional[str]] = (None, None)
    for t in texts:
        kind = detect(t)
        if kind == "BODY":
            return kind, t
        if kind and found[0] is None:
            found = (kind, t)
    return found


# 외로움 단어 규칙: Gemini가 실패했을 때만 쓰는 대체 판단 (외로움은 알림을 보내지 않으므로 보조용)
_LONELY = [re.compile(p) for p in [
    r"아무도(안|못)(와|오|찾|온)", r"찾아오는(사람|이)?(이)?없", r"연락(이|도)?없", r"연락(을|도)?안",
    r"말할(사람|데)(이|가)?없", r"말붙일", r"적적", r"쓸쓸", r"외로", r"허전",
    r"(먼저|다)갔", r"혼자(라|라서|니까|서)?.{0,6}(귀찮|싫|슬프|서럽)", r"사람구경",
]]


def loneliness(texts: Iterable[str]) -> Optional[str]:
    """외로움 표현이 있는 첫 문장을 돌려준다. 없으면 None."""
    for t in texts:
        if any(r.search(_norm(t)) for r in _LONELY):
            return t
    return None
