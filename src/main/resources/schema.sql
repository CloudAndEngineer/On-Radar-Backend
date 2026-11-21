-- 토닥 Core DB (H2, 메모리 모드) · ERD 11/21 기준
-- 위치: backend/src/main/resources/schema.sql  (Spring Boot가 시작할 때 자동 실행)
-- ENUM은 Spring Data JDBC 매핑이 쉽도록 VARCHAR + CHECK로 둡니다.
-- 시각은 모두 한국 시간(Asia/Seoul) 기준 TIMESTAMP 입니다.
-- [API] 표시는 ERD에는 없지만 API 명세 때문에 필요한 컬럼입니다. 황태준님 확인 필요.

CREATE TABLE caregivers (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(50)  NOT NULL,
    center_name   VARCHAR(100) NOT NULL,
    phone         VARCHAR(20),
    login_id      VARCHAR(50)  NOT NULL UNIQUE, -- [API] POST /auth/login 아이디
    password      VARCHAR(100) NOT NULL         -- [API] 비밀번호. BCrypt 해시(60자) 저장 권장, 평문이면 시연용만
);

CREATE TABLE seniors (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    caregiver_id       BIGINT       NOT NULL REFERENCES caregivers(id),
    name               VARCHAR(50)  NOT NULL,
    birth_year         INT          NOT NULL,
    address_dong       VARCHAR(50),
    consent_at         TIMESTAMP,
    consent_scope      VARCHAR(100),
    baseline_json      TEXT,                     -- 처음 7일 기준선 (엔진이 캐시로 저장)
    created_at         TIMESTAMP    NOT NULL,    -- 이 날짜부터 7일이 기준선 기간
    honorific          VARCHAR(20),              -- [API] 기기 등록 응답 "할머니"/"할아버지"
    registration_code  VARCHAR(20)  UNIQUE,      -- [API] 기기 등록 코드 "KSJ-0001"
    device_token       VARCHAR(100) UNIQUE,      -- [API] X-Device-Token
    fcm_token          VARCHAR(255)              -- [API] PUT /devices/fcm-token
);

-- 아침·저녁 체크 1회 = 1행. 행이 없고 마감(아침 11:00, 저녁 21:00)이 지났으면 미응답.
CREATE TABLE daily_checks (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    senior_id        BIGINT      NOT NULL REFERENCES seniors(id),
    check_date       DATE        NOT NULL,
    session          VARCHAR(10) NOT NULL CHECK (session IN ('MORNING', 'EVENING')),
    responded_at     TIMESTAMP   NOT NULL,
    condition_score  INT         CHECK (condition_score BETWEEN 0 AND 2),  -- 아침만
    meal_count       INT         CHECK (meal_count BETWEEN 0 AND 3),       -- 저녁만
    CONSTRAINT uk_daily_checks UNIQUE (senior_id, check_date, session)
);

CREATE TABLE conversations (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    check_id           BIGINT    NOT NULL REFERENCES daily_checks(id),
    started_at         TIMESTAMP NOT NULL,
    transcript         TEXT,                    -- JSON 배열 [{"role":"ai","text":"..."}]
    summary            TEXT,                    -- 40자 안쪽
    loneliness_signal  BOOLEAN   NOT NULL DEFAULT FALSE,
    crisis_flag        BOOLEAN   NOT NULL DEFAULT FALSE,
    loneliness_evidence TEXT,                   -- [API] classify 응답. 어르신 말 그대로 인용 (복지사 화면 근거)
    crisis_evidence     TEXT                    -- [API] classify 응답
);

-- 어르신·날짜당 1행, 다시 계산하면 덮어씀
CREATE TABLE risk_assessments (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    senior_id      BIGINT      NOT NULL REFERENCES seniors(id),
    assessed_date  DATE        NOT NULL,
    score          INT         NOT NULL,
    level          VARCHAR(10) NOT NULL CHECK (level IN ('NORMAL', 'CAUTION', 'DANGER')),
    reasons_json   TEXT,                        -- {"reasons":[...],"immediateTriggers":[...],"baselineReady":true,"lonelinessDays":1}
    ai_summary     TEXT,
    CONSTRAINT uk_risk_assessments UNIQUE (senior_id, assessed_date)
);

CREATE TABLE alerts (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    senior_id        BIGINT      NOT NULL REFERENCES seniors(id),
    level            VARCHAR(10) NOT NULL CHECK (level IN ('CAUTION', 'DANGER', 'EMERGENCY')),
    reason           VARCHAR(50) NOT NULL,      -- HELP_BUTTON, CRISIS_TALK, MORNING_MISSED ... (즉시 위험 이름과 같은 철자)
    stage            VARCHAR(10) NOT NULL CHECK (stage IN ('SENIOR', 'CAREGIVER', 'CENTER', 'GUARDIAN')),
    sent_at          TIMESTAMP   NOT NULL,
    next_action_at   TIMESTAMP,                 -- null 이면 단계 진행 멈춤
    acknowledged_at  TIMESTAMP,
    status           VARCHAR(15) NOT NULL CHECK (status IN ('IN_PROGRESS', 'RESOLVED'))
);

CREATE TABLE timeline_events (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    senior_id   BIGINT      NOT NULL REFERENCES seniors(id),
    actor_type  VARCHAR(10) NOT NULL CHECK (actor_type IN ('SYSTEM', 'CAREGIVER', 'GUARDIAN')),
    actor_name  VARCHAR(50),
    action      VARCHAR(20) NOT NULL CHECK (action IN (
                    'CHECK_MISSED', 'REMINDER_SENT', 'ALERT_ESCALATED', 'CALLED', 'CALL_NO_ANSWER',
                    'CARE_REQUESTED', 'VISIT_SCHEDULED', 'RESOLVED', 'NOTE')),
    memo        TEXT,
    created_at  TIMESTAMP   NOT NULL
);

-- 가족 (TODO) · 설계만
CREATE TABLE guardians (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    senior_id     BIGINT      NOT NULL REFERENCES seniors(id),
    name          VARCHAR(50) NOT NULL,
    relation      VARCHAR(20),
    phone         VARCHAR(20),
    notify_order  INT
);

CREATE INDEX idx_daily_checks_senior_date ON daily_checks (senior_id, check_date);
CREATE INDEX idx_alerts_senior_status     ON alerts (senior_id, status);
CREATE INDEX idx_timeline_senior_created  ON timeline_events (senior_id, created_at);
