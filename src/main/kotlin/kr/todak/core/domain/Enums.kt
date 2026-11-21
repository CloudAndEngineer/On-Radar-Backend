package kr.todak.core.domain

// 명세서 ENUM 값 그대로 (앱·웹·AI 모두 이 철자)

enum class Session { MORNING, EVENING }

enum class RiskLevel { NORMAL, CAUTION, DANGER }

enum class AlertLevel { CAUTION, DANGER, EMERGENCY }

/** 가족(GUARDIAN) 단계는 TODO — 아직 진행 순서에 넣지 않음 */
enum class AlertStage { SENIOR, CAREGIVER, CENTER, GUARDIAN }

enum class AlertStatus { IN_PROGRESS, RESOLVED }

enum class ActorType { SYSTEM, CAREGIVER, GUARDIAN }

enum class ActionType {
    CHECK_MISSED,
    REMINDER_SENT,
    ALERT_ESCALATED,
    CALLED,
    CALL_NO_ANSWER,
    CARE_REQUESTED,
    VISIT_SCHEDULED,
    RESOLVED,
    NOTE;

    companion object {
        /** 복지사가 웹에서 직접 남길 수 있는 조치 */
        val CAREGIVER_ACTIONS = setOf(CALLED, CALL_NO_ANSWER, CARE_REQUESTED, VISIT_SCHEDULED, RESOLVED, NOTE)
    }
}

/** 오늘 체크 상태 */
enum class CheckStatus { PENDING, DONE, MISSED }

/** 알림 사유 코드 */
object AlertReason {
    const val MORNING_MISSED = "MORNING_MISSED"
    const val RISK_SCORE = "RISK_SCORE"
    const val HELP_BUTTON = "HELP_BUTTON"
    const val CRISIS_TALK = "CRISIS_TALK"

    fun text(reason: String): String = when (reason) {
        MORNING_MISSED -> "아침 체크 미응답"
        RISK_SCORE -> "위험 점수 70점 이상"
        HELP_BUTTON -> "어르신이 [도와주세요]를 눌러 119 전화 화면이 열렸어요"
        CRISIS_TALK -> "대화 중 위기 표현"
        "MORNING_MISSED_2DAYS" -> "아침 2일 연속 미응답"
        "FULL_DAY_MISSED" -> "아침·저녁 모두 미응답"
        "BAD_CONDITION_3DAYS" -> "'안 좋아요' 3일 연속"
        else -> reason
    }
}
