-- Core(황태준) 추가분 — 팀 schema.sql 다음에 실행됩니다 (application.yml 의 schema-locations).
-- 팀 schema.sql 은 건드리지 않고, Core 가 꼭 필요한 컬럼만 여기서 더합니다.
-- 팀 schema.sql 에 합칠 때는 이 파일을 지우면 됩니다.

-- 대화를 끝낸 시각 (끝난 대화에 메시지가 더 오면 409)
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP;
-- AI classify 의 근거 인용 — 복지사가 웹에서 "어르신이 실제로 한 말"을 봐야 함 (명세서 'AI 서비스 입출력')
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS loneliness_evidence VARCHAR(500);
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS crisis_evidence VARCHAR(500);

-- 같은 사유 알림을 해결 뒤 24시간 안에 다시 열지 않기 위해 (명세서 '잘못된 알림 줄이기')
ALTER TABLE alerts ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMP;
