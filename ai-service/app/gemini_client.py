"""Gemini 호출 유틸: JSON 모드로 부르고, 형식이 틀리면 한 번 더 요청한다.

환경변수
  GEMINI_API_KEY         Google AI Studio에서 발급한 키 (레포에 올리지 말 것)
  GEMINI_MODEL           먼저 부를 모델
  GEMINI_FALLBACK_MODEL  첫 모델이 붐비거나(429·500·503·504) 늦으면 대신 부를 모델 (선택)
  GEMINI_THINKING_LEVEL  생각 단계 수준. 빠르게 하려면 low (선택, 비우면 모델 기본값)
  시간 제한은 service.py에서 함수별로 정한다 (대화 4.5초, 분류·요약 12초).
"""
import asyncio
import logging
import os
from typing import Optional, TypeVar

from pydantic import BaseModel, ValidationError

log = logging.getLogger("gemini")
T = TypeVar("T", bound=BaseModel)

BUSY_CODES = (429, 500, 503, 504)
HTTP_TIMEOUT_MS = 15_000  # 가장 긴 함수 제한(12초)보다 조금 길게. 실제 제한은 wait_for가 건다.

_client = None


def is_configured() -> bool:
    return bool(os.getenv("GEMINI_API_KEY")) and bool(os.getenv("GEMINI_MODEL"))


def _get_client():
    global _client
    if _client is None:
        from google import genai  # 설치: pip install google-genai
        from google.genai import types

        # SDK 자체 재시도를 끈다. 503에서 SDK가 몇십 초씩 재시도하면
        # 대체 모델로 넘어가기 전에 시간을 다 써 버리기 때문.
        try:
            http_options = types.HttpOptions(
                timeout=HTTP_TIMEOUT_MS, retry_options=types.HttpRetryOptions(attempts=1)
            )
        except (AttributeError, TypeError):  # 옛 SDK 버전이면 기본값 사용
            http_options = None
        _client = genai.Client(api_key=os.environ["GEMINI_API_KEY"], http_options=http_options)
    return _client


def _build_config(system: str, schema: type[BaseModel], temperature: float):
    from google.genai import types

    kwargs = dict(
        system_instruction=system,
        temperature=temperature,
        response_mime_type="application/json",
        response_schema=schema,
    )
    level = os.getenv("GEMINI_THINKING_LEVEL")
    if level:
        try:
            kwargs["thinking_config"] = types.ThinkingConfig(thinking_level=level)
        except (AttributeError, TypeError, ValueError):
            log.warning("이 SDK에서는 thinking_level을 쓸 수 없어 기본값으로 호출")
    return types.GenerateContentConfig(**kwargs)


def _is_busy(e: Exception) -> bool:
    if isinstance(e, asyncio.TimeoutError):
        return True
    code = getattr(e, "code", None) or getattr(e, "status_code", None)
    return code in BUSY_CODES


async def generate_json(
    system: str,
    user: str,
    schema: type[T],
    temperature: float = 0.2,
    timeout: float = 4.5,
) -> T:
    """system 지시 + user 입력으로 schema 모양의 JSON을 받아 검증된 객체로 돌려준다.

    timeout 안에서 첫 모델 → (붐비거나 늦으면) 대체 모델 순으로 시도한다.
    대체 모델이 있으면 첫 모델에는 시간의 60%만 줘서, 대체 모델이 쓸 시간을 남긴다.
    실패하면 예외를 올린다. 대체 응답은 부르는 쪽(service.py)이 정한다.
    """
    config = _build_config(system, schema, temperature)
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    client = _get_client()

    models = [os.environ["GEMINI_MODEL"]]
    if os.getenv("GEMINI_FALLBACK_MODEL"):
        models.append(os.environ["GEMINI_FALLBACK_MODEL"])

    last_error: Optional[Exception] = None
    for i, model in enumerate(models):
        has_next = i < len(models) - 1
        model_deadline = loop.time() + timeout * 0.6 if has_next else deadline
        model_deadline = min(model_deadline, deadline)
        for attempt in range(2):  # 형식 오류면 같은 모델로 한 번 더
            remaining = model_deadline - loop.time()
            if remaining <= 0.3:
                last_error = asyncio.TimeoutError(f"{model} 시간 초과")
                break
            try:
                resp = await asyncio.wait_for(
                    client.aio.models.generate_content(model=model, contents=user, config=config),
                    timeout=remaining,
                )
            except Exception as e:  # noqa: BLE001
                if _is_busy(e):
                    log.warning("%s 사용 불가(%s) → 다음 모델", model, getattr(e, "code", type(e).__name__))
                    last_error = e
                    break
                raise
            try:
                return schema.model_validate_json(resp.text or "")
            except ValidationError as e:
                last_error = e
                log.warning("JSON 형식 오류 (%s, 시도 %d): %s", model, attempt + 1, resp.text)
    raise last_error or RuntimeError("Gemini 응답 없음")
