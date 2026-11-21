# 토닥 AI 서비스

Core만 호출하는 내부 서비스입니다. 상태를 저장하지 않고, 함수 3개를 제공합니다.

| 메서드 | 경로 | 하는 일 |
| --- | --- | --- |
| GET | `/health` | 배포 확인, Gemini·프롬프트 준비 여부 |
| POST | `/v1/chat` | 대화 시작과 매 턴의 AI 답 |
| POST | `/v1/classify` | 대화가 끝날 때 요약 · 외로움 신호 · 위기 표현 |
| POST | `/v1/risk-summary` | 위험 점수 근거로 복지사용 한 줄 요약 |

요청·응답 형식은 **토닥 API 명세서**의 'AI 서비스 입출력', 말투·분류 기준은 **토닥 AI 대화 설계서**를 따릅니다.

## 지금 상태

- 엔드포인트 4개가 모두 동작하고, 명세서 모양대로 답합니다.
- `app/prompts.py`의 `READY = False`인 동안은 Gemini를 부르지 않고 **고정 응답(fallback)**을 돌려줍니다. 그래서 Core는 프롬프트 없이도 연결 작업을 시작할 수 있습니다.
- 프롬프트 본문(`CHAT_SYSTEM` 등)은 본선 당일 설계서를 보고 채웁니다.

## 로컬 실행

```bash
cd ai-service
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env        # GEMINI_API_KEY, GEMINI_MODEL 채우기
uvicorn app.main:app --reload --port 8001
```

- API 문서: http://localhost:8001/docs
- 응답 모양 확인: `python tests/smoke_test.py`
- 분류 정확도: `python tests/run_eval.py` (프롬프트를 채운 뒤)

## Render 배포

1. Render → New → Blueprint로 레포를 연결하면 `render.yaml`을 읽습니다. 레포 최상위에 `ai-service/` 폴더가 있는 구조 기준입니다 (`rootDir`).
2. 대시보드 환경변수에 `GEMINI_API_KEY`, `GEMINI_MODEL`을 넣습니다.
3. 배포 후 `python tests/smoke_test.py https://<서비스>.onrender.com`
4. Core 환경변수 `AI_BASE_URL` = `https://<서비스>.onrender.com/v1` 을 황태준 님께 전달합니다.

무료 플랜은 한동안 요청이 없으면 잠들어 첫 요청이 오래 걸립니다. **발표 5분 전에 `/health`를 한 번 불러 깨웁니다.**

## 본선 당일 순서

1. `prompts.py`에 설계서 내용을 옮겨 `CHAT_SYSTEM` · `CLASSIFY_SYSTEM` · `RISK_SUMMARY_SYSTEM` 작성, `READY = True`
2. `run_eval.py`로 위기·응급 6/6, 나머지 18/19 이상 될 때까지 고치기
3. Core와 연결해 앱에서 실제 대화 확인
4. 시연 대사로 3번 연속 같은 결과 나오는지 확인 (분류·요약 temperature 0.2)

## 안전 규칙 (코드로 고정)

- 위기 표현이 나오면 Gemini 문장 대신 `fallback.py`의 **고정 대사**를 돌려주고 대화를 끝냅니다.
- 첫 모델이 붐비거나(429·500·503·504) 늦으면 `GEMINI_FALLBACK_MODEL`로 한 번 더 시도하고, 그래도 안 되면 고정 응답으로 돌아갑니다. Core는 위기 단어 목록으로 한 번 더 검사합니다.
- 시간 제한: 대화 4.5초(`GEMINI_TIMEOUT_CHAT`), 분류·요약 12초(`GEMINI_TIMEOUT_BG`). Core 쪽 타임아웃은 각각 5초, 13초로 맞춥니다.
