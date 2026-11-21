# 토닥 Core API + 알림 모듈

Kotlin · Spring Boot 3.4 · Spring Data JDBC · H2 · Render (Docker)
담당: 황태준 · 기준 문서: 토닥 API 명세서 수정본 (11/21)

## 실행

```bash
cd backend
./gradlew bootRun                       # http://localhost:8080
DEMO_TODAY=2026-11-21 ALERT_STEP_MINUTES=1 ./gradlew bootRun   # 시연 설정
./gradlew test                          # 점수 엔진 테스트 (명세서 예시 81점·3점 재현)
```

- Swagger: `http://localhost:8080/api/docs`
- 상태 확인: `GET /health` (오늘 날짜, AI 모드, FCM 사용 여부, SSE 연결 수)
- 호출 예시: `api.http` (IntelliJ / VS Code REST Client)
- 기동하면 시드가 들어갑니다: 복지사 `demo / demo`, 어르신 10명 × 14일

## 환경변수

| 이름 | 기본값 | 설명 |
|---|---|---|
| `DEMO_TODAY` | (비움 = 실제 오늘) | 시드 마지막 날. 날짜만 바뀌고 시각은 실제 한국 시각 |
| `ALERT_STEP_MINUTES` | 120 | 알림 단계 간격. 시연 1~2 |
| `AI_BASE_URL` | (비움 = 내장 모의 AI) | `https://todak-ai-zsgj.onrender.com/v1` |
| `CORS_ALLOWED_ORIGINS` | `*` | 쉼표로 구분 |
| `FIREBASE_CREDENTIALS` | (비움 = 푸시는 로그만) | 서비스 계정 JSON 원문 또는 base64 |
| `DEMO_CAREGIVER_LOGIN_ID` / `DEMO_CAREGIVER_PASSWORD` | demo / demo | 시연용 복지사 계정 |
| `SEED_ENABLED` | true | 시드 넣기 |
| `AUTH_SECRET` | (비움 = 기동마다 새 키) | 복지사 로그인 토큰 서명 키 |
| `CHECK_REMINDER_ENABLED` | true | 8시·18시 체크 알림 푸시 |
| `CHECK_TIME_ENFORCED` | true | 8시·18시 전 체크 제출은 409 `CHECK_NOT_OPEN`. 낮 시연에서 저녁 체크를 보이려면 false |
| `H2_CONSOLE` | false | true 면 `/h2-console` (JDBC URL `jdbc:h2:mem:todak`) |

## Render 배포

레포 루트의 `render.yaml`(Blueprint)로 만들거나, 수동으로 Web Service → Docker, Root Directory `backend`, Health Check Path `/health`.
H2는 메모리 DB라 재시작하면 시드로 돌아갑니다 (시연용으로 의도한 것).
Core는 10분마다 AI `/health`를 불러 AI 서비스가 잠들지 않게 합니다. SSE 30초 ping 이 Core를 깨워 둡니다.

## 구조

```
kr.todak.core
├─ auth          기기 토큰(X-Device-Token) · 복지사 로그인(Bearer, SSE는 ?token=)
├─ check         POST/PUT /checks, GET /seniors/me/today
├─ conversation  대화 시작·메시지·종료, AI classify 뒤처리
├─ senior        기기 등록·FCM·긴급 호출(앱), 웹 조회·조치·ack
├─ ai            AI 서비스 클라이언트 + 실패 시 대체 동작 + 위기 단어 목록
├─ risk          RiskEngine 인터페이스, RuleRiskEngine(초안), RiskService(저장·요약·알림)
├─ alert         AlertService(open/acknowledge/resolve), 1분 스케줄러, FCM, 동주민센터 모의
├─ timeline      조치 타임라인 기록 + SSE timeline-added
├─ sse           SSE 허브 (30초 ping, 커밋 뒤 전송)
├─ jobs          11시 아침 미응답, 21시 저녁 마감, /api/demo/*, /health
└─ seed          시드 (DB→앱 담당 설계가 나오면 PROFILES 교체)
```

## 다른 담당자와 맞닿는 곳

- **위험 점수 엔진 (주현서)**: `risk/RiskEngine.kt` 의 `RiskEngine` 인터페이스는 명세서 그대로입니다. `RuleRiskEngine`은 11/21 확정 기준을 옮긴 초안이고 테스트가 있습니다. 바꿀 때는 `@Component` 구현이 하나만 남게 해 주세요.
- **AI 서비스 (김서연)**: 배포 주소 `https://todak-ai-zsgj.onrender.com/v1`. Core가 보내는 JSON은 `ai/AiClient.kt` 의 `ChatRequest`·`ClassifyRequest`·`RiskSummaryRequest`. 타임아웃 chat 5초, 나머지 13초. `AI_BASE_URL`이 없으면 모의 AI라 앱·웹은 AI 없이도 개발 가능합니다.
- **어르신 앱 (도우주)**: 등록 코드 `KSJ-0001`(김순자)은 오늘 체크가 비어 있어 바로 시연 가능. `PUT /devices/fcm-token` 은 204 를 돌려줍니다. 푸시 data: `{type: "CHECK_REMINDER", alertId, session}`.
- **복지관 웹**: 로그인 `{loginId, password}` → `{token, caregiverId, name, centerName}`. SSE 는 연결 직후 `connected` 이벤트를 한 번 보내고 30초마다 `ping`.

## 명세서에 없어서 정한 것 (팀 확인 필요)

1. **열린 질문 2** — ERD(팀 schema.sql)에 `timeline_events.alert_id` 가 없어 저장하지 않습니다. 요청의 `alertId` 는 그 알림을 끝내는 데만 씁니다.
2. **열린 질문 3** — 미응답은 행을 만들지 않고 "행 없음 + 마감 지남"으로 판단합니다. 알림 아침 08:00·저녁 18:00, 마감은 3시간 뒤 11:00·21:00. 체크는 아침 08:00~, 저녁 18:00~ 부터 받습니다 (`CHECK_TIME_ENFORCED`).
3. **점수 엔진 열린 질문** — 평소 컨디션·끼니 평균이 0이면 그 항목은 0점.
4. **즉시 위험 점수** — `max(점수, 70)`. 명세서 예시(81점 + BAD_CONDITION_3DAYS)와 맞추려고 70 고정 대신 최소 70.
5. **[도와주세요]·위기 표현** — 그 EMERGENCY 알림이 해결될 때까지 즉시 위험으로 유지. 같은 사유 24시간 재발 방지는 EMERGENCY 에는 적용하지 않음(진행 중 중복만 막음).
6. **DANGER 알림 사유** — 즉시 위험 규칙이면 그 이름(`BAD_CONDITION_3DAYS` 등), 아니면 `RISK_SCORE`. 같은 사유끼리만 중복 방지.
7. **늦게 들어온 아침 체크** — 진행 중인 '아침 미응답' 알림은 시스템이 닫고 타임라인에 `RESOLVED`(SYSTEM).
8. **조치 + alertId** — RESOLVED 가 아닌 조치(CALLED 등)에 `alertId` 를 보내면 그 알림은 확인된 것으로 보고 단계 진행을 멈춥니다.
9. **조치 값 확정** — 복지사가 보낼 수 있는 값: `CALLED · CALL_NO_ANSWER · CARE_REQUESTED · VISIT_SCHEDULED · RESOLVED · NOTE`. 나머지(`CHECK_MISSED · REMINDER_SENT · ALERT_ESCALATED`)는 시스템 전용, 보내면 400 `INVALID_ACTION`.
10. **응답 필드 추가** (명세서 예시에 없던 것, 빼도 됨): 목록·상세의 `baselineReady`, 상세의 `monitoringStartDate`(= seniors.created_at)·`reasons`·`immediateTriggers`·`today`·`callNoAnswerCount`(2 이상이면 [돌봄 체계에 확인 요청] 강조), `alert-created` 의 `reasonText`, `alert-updated` 의 `seniorId`·`acknowledged`.
11. **상단 카드** `GET /caregivers/me/summary` → `{dangerCount, cautionCount, normalCount, todayMissedCount}`.
13. **[도와주세요] 기록** — 앱은 119 다이얼 화면만 띄우므로 서버는 통화 여부를 모릅니다. 알림 사유(타임라인·SSE `reasonText`)는 "어르신이 [도와주세요]를 눌러 119 전화 화면이 열렸어요"로 사실만 적고, 복지사에게 EMERGENCY 알림이 갑니다.
14. **체크 알림 푸시** — 8시·18시에 아직 체크 안 한 어르신에게 FCM (data `{type: "CHECK_OPEN", session}`). 앱이 자체 알람을 쓰면 `CHECK_REMINDER_ENABLED=false`.
12. **알림 열림 SSE** — `alert-created` 는 단계와 상관없이 열릴 때 보냅니다 (CAUTION 은 stage `SENIOR` 로 옴, 웹에서 토스트 여부 결정).

## DB 스키마

- `schema.sql` 은 팀(DB 담당) 파일입니다. Core 는 그 이름에 맞춰 매핑합니다 (`domain/Entities.kt`).
- `schema-core.sql` — Core 가 꼭 필요한 컬럼 4개만 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 로 더합니다.
  `conversations.ended_at · loneliness_evidence · crisis_evidence`, `alerts.resolved_at`. 팀 schema.sql 에 합치면 이 파일은 지우면 됩니다.
- 복지사 로그인 토큰은 서명된 토큰이라 테이블이 없습니다 (`AUTH_SECRET`, 비우면 재시작 때 다시 로그인).
- `caregivers.password_hash` 에 BCrypt 해시를 저장하고 로그인 시 BCrypt로 검증합니다. 기본 시연 계정은 `demo / demo` 입니다.
- `risk_assessments.reasons_json` = `{"reasons":[...],"immediateTriggers":[...],"baselineReady":true,"lonelinessDays":1}`
- 어르신 기준선 시작일은 `seniors.created_at` 의 날짜입니다.

## 에러 코드

| 상태 | code |
|---|---|
| 400 | `VALIDATION_ERROR` `INVALID_BODY` `INVALID_CHECK` `SESSION_MISMATCH` `INVALID_ACTION` `INVALID_PARAMETER` |
| 401 | `UNAUTHORIZED` `INVALID_DEVICE_TOKEN` `INVALID_TOKEN` `INVALID_CREDENTIALS` |
| 404 | `REGISTRATION_CODE_NOT_FOUND` `SENIOR_NOT_FOUND` `CHECK_NOT_FOUND` `CONVERSATION_NOT_FOUND` `ALERT_NOT_FOUND` |
| 409 | `CHECK_ALREADY_SUBMITTED` `CHECK_NOT_OPEN` `CONVERSATION_ENDED` |

## 시연 순서 예시 (ALERT_STEP_MINUTES=1)

1. 웹 로그인 → 목록 맨 위 김순자(위험)·박영호(위험)
2. 앱에서 `KSJ-0001` 등록 → 아침 '안 좋아요' → 웹이 SSE로 갱신, 위험 알림 열림(CAREGIVER)
3. 대화 3턴 → 상세 '최근 대화'에 요약·외로움 근거
4. 웹 [전화하기] `CALL_NO_ANSWER` 2회 → [돌봄 체계에 확인 요청] → [연락 완료 처리] `RESOLVED`
5. 앱 [도와주세요] → 웹 빨간 배너(EMERGENCY), 확인 안 하면 1분 뒤 동주민센터 단계
6. 아침 미응답 시나리오는 `POST /api/demo/jobs/morning-missed` 로 11시 작업을 앞당겨 실행
