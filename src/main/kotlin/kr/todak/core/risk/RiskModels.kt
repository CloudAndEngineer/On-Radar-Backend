package com.todak.core.risk

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// ── 결과 (명세서 RiskResult + 11/21 추가 필드 2개) ──────────────────────────

enum class RiskLevel { NORMAL, CAUTION, DANGER }

enum class Session { MORNING, EVENING }

/** item = NO_RESPONSE | WAKE_TIME | CONDITION | MEAL. AI risk-summary 입력으로 그대로 갑니다. */
data class Reason(
    val item: String,
    val baseline: Any,      // NO_RESPONSE: Double, WAKE_TIME: "08:00", CONDITION/MEAL: Double
    val recent: Any,        // NO_RESPONSE: Int,    WAKE_TIME: "10:46", CONDITION/MEAL: Double
    val contribution: Double,
)

data class RiskResult(
    val score: Int,                         // 0~100, 즉시 위험이면 max(점수, 70)
    val level: RiskLevel,
    val reasons: List<Reason>,
    val immediateTriggers: List<String>,    // 없으면 빈 목록
    val baselineReady: Boolean,             // 처음 7일은 false (점수 0, NORMAL. 즉시 위험은 적용)
    val lonelinessDays: Int,                // 최근 3일 중 외로움 신호가 있었던 날 수 (점수에는 안 들어감)
)

interface RiskEngine {
    fun assess(seniorId: Long, date: LocalDate): RiskResult
}

// ── 계산 입력 (DB와 무관한 순수 데이터) ──────────────────────────────────────

/** daily_checks 한 행. 행이 없으면 그 칸은 '아직 안 함' 또는 '미응답'. */
data class CheckRecord(
    val date: LocalDate,
    val session: Session,
    val respondedAt: LocalDateTime,
    val conditionScore: Int? = null,   // 아침만 0/1/2
    val mealCount: Int? = null,        // 저녁만 0~3
)

data class RiskInput(
    val today: LocalDate,                   // 판정일 (DEMO_TODAY)
    val now: LocalDateTime,                 // 지금 시각. 마감 전 체크는 미응답에서 뺍니다
    val baselineStart: LocalDate,           // seniors.created_at 날짜. 이 날부터 7일이 기준선
    val checks: List<CheckRecord>,
    val emergencyReasonsToday: Set<String> = emptySet(),  // 오늘 alerts(level=EMERGENCY).reason
    val crisisTalkToday: Boolean = false,                  // 오늘 대화 중 conversations.crisis_flag
    val lonelinessDates: Set<LocalDate> = emptySet(),      // loneliness_signal=true 대화가 있는 날짜
)

// ── 팀 약속 상수 ──────────────────────────────────────────────────────────

object RiskRules {
    const val BASELINE_DAYS = 7L
    const val RECENT_DAYS = 3L

    /** 알림 후 3시간이 지나면 미응답 (아침 8시 → 11시, 저녁 18시 → 21시) */
    val MORNING_DEADLINE: LocalTime = LocalTime.of(11, 0)
    val EVENING_DEADLINE: LocalTime = LocalTime.of(21, 0)

    const val W_NO_RESPONSE = 30.0
    const val W_WAKE_TIME = 30.0
    const val W_CONDITION = 20.0
    const val W_MEAL = 20.0

    const val POINTS_PER_MISS = 10.0
    const val MINUTES_PER_POINT = 6.0

    const val CAUTION_FROM = 40
    const val DANGER_FROM = 70
    const val IMMEDIATE_SCORE = 70

    fun deadline(session: Session): LocalTime =
        if (session == Session.MORNING) MORNING_DEADLINE else EVENING_DEADLINE
}

object Triggers {
    const val MORNING_MISSED_2DAYS = "MORNING_MISSED_2DAYS"
    const val FULL_DAY_MISSED = "FULL_DAY_MISSED"
    const val BAD_CONDITION_3DAYS = "BAD_CONDITION_3DAYS"
    const val HELP_BUTTON = "HELP_BUTTON"
    const val CRISIS_TALK = "CRISIS_TALK"
}
