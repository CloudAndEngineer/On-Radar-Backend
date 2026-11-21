package com.todak.core.risk

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Date
import java.time.LocalDate

/** assessAndSave 결과. Core는 이것만 보고 AI 요약 · SSE · 알림을 결정하면 됩니다. */
data class AssessOutcome(
    val seniorId: Long,
    val date: LocalDate,
    val result: RiskResult,
    val previousScore: Int?,
    val previousLevel: RiskLevel?,
) {
    /** 점수나 단계가 바뀌었으면 → AI risk-summary 호출 + SSE risk-updated */
    val changed: Boolean get() = previousScore != result.score || previousLevel != result.level

    /** 새로 DANGER가 됐으면 → AlertService.open(..., DANGER, reason, CAREGIVER) */
    val becameDanger: Boolean get() = result.level == RiskLevel.DANGER && previousLevel != RiskLevel.DANGER

    /** alerts.reason 으로 쓸 값: 즉시 위험이 있으면 그 이름, 없으면 RISK_SCORE */
    val alertReason: String get() = result.immediateTriggers.firstOrNull() ?: "RISK_SCORE"
}

/**
 * 위험 점수 엔진 (DB 연결). 계산은 RiskCalculator, 여기서는 읽고 저장만 합니다.
 *
 * 부르는 때 (Core):
 *  - 체크 제출 직후, 대화 분류 직후:  riskEngine.assessAndSave(seniorId)
 *  - 매일 22시:                        riskEngine.assessAllAndSave()
 */
@Service
class JdbcRiskEngine(
    private val jdbc: JdbcTemplate,
    private val clock: DemoClock,
    private val mapper: ObjectMapper,
) : RiskEngine {

    override fun assess(seniorId: Long, date: LocalDate): RiskResult =
        RiskCalculator.calculate(loadInput(seniorId, date))

    @Transactional
    fun assessAndSave(seniorId: Long, date: LocalDate = clock.today()): AssessOutcome {
        val previous = jdbc.query(
            """SELECT score, level FROM risk_assessments
               WHERE senior_id = ? AND assessed_date <= ? ORDER BY assessed_date DESC LIMIT 1""",
            { rs, _ -> rs.getInt("score") to RiskLevel.valueOf(rs.getString("level")) },
            seniorId, Date.valueOf(date),
        ).firstOrNull()

        val result = assess(seniorId, date)

        // 어르신·날짜당 1행 덮어쓰기. ai_summary는 건드리지 않음 (saveAiSummary로 따로 저장)
        jdbc.update(
            """MERGE INTO risk_assessments (senior_id, assessed_date, score, level, reasons_json)
               KEY (senior_id, assessed_date) VALUES (?, ?, ?, ?, ?)""",
            seniorId, Date.valueOf(date), result.score, result.level.name, reasonsJson(result),
        )
        if (result.baselineReady) saveBaseline(seniorId, result)

        return AssessOutcome(seniorId, date, result, previous?.first, previous?.second)
    }

    /** 매일 22시 스케줄러용: 모든 어르신을 다시 판정 */
    @Transactional
    fun assessAllAndSave(date: LocalDate = clock.today()): List<AssessOutcome> =
        jdbc.queryForList("SELECT id FROM seniors ORDER BY id", Long::class.javaObjectType).map { assessAndSave(it, date) }

    /** AI risk-summary 응답(또는 실패 시 대체 문장)을 저장 */
    fun saveAiSummary(seniorId: Long, date: LocalDate, summary: String) {
        jdbc.update(
            "UPDATE risk_assessments SET ai_summary = ? WHERE senior_id = ? AND assessed_date = ?",
            summary, seniorId, Date.valueOf(date),
        )
    }

    /** AI POST /v1/risk-summary 요청 본문 (명세서 형식 그대로) */
    fun riskSummaryRequest(seniorName: String, result: RiskResult): Map<String, Any> = mapOf(
        "seniorName" to seniorName,
        "riskLevel" to result.level.name,
        "score" to result.score,
        "reasons" to result.reasons,
        "immediateTriggers" to result.immediateTriggers,
        "lonelinessDays" to result.lonelinessDays,
    )

    /** AI 실패 시 대체 문장 (명세서: reasons로 만든 고정 문장) */
    fun fallbackSummary(result: RiskResult): String {
        val labels = mapOf("NO_RESPONSE" to "체크 놓침 ↑", "WAKE_TIME" to "아침 응답 늦어짐", "CONDITION" to "컨디션 ↓", "MEAL" to "식사 ↓")
        val triggerLabels = mapOf(
            Triggers.MORNING_MISSED_2DAYS to "아침 2일 연속 미응답", Triggers.FULL_DAY_MISSED to "하루 종일 미응답",
            Triggers.BAD_CONDITION_3DAYS to "'안 좋아요' 3일 연속", Triggers.HELP_BUTTON to "[도와주세요] 누름",
            Triggers.CRISIS_TALK to "대화 중 위기 표현",
        )
        val parts = result.immediateTriggers.mapNotNull { triggerLabels[it] } +
            result.reasons.filter { it.contribution > 0 }.sortedByDescending { it.contribution }.mapNotNull { labels[it.item] }
        return if (parts.isEmpty()) "평소와 비슷하게 지내고 계세요." else parts.joinToString(" · ")
    }

    // ── DB 읽기 ──────────────────────────────────────────────────────────────

    private fun loadInput(seniorId: Long, date: LocalDate): RiskInput {
        val createdAt = jdbc.queryForObject(
            "SELECT created_at FROM seniors WHERE id = ?",
            { rs, _ -> rs.getTimestamp("created_at").toLocalDateTime() }, seniorId,
        )!!

        val checks = jdbc.query(
            """SELECT check_date, session, responded_at, condition_score, meal_count
               FROM daily_checks WHERE senior_id = ? AND check_date <= ?""",
            { rs, _ ->
                CheckRecord(
                    date = rs.getDate("check_date").toLocalDate(),
                    session = Session.valueOf(rs.getString("session")),
                    respondedAt = rs.getTimestamp("responded_at").toLocalDateTime(),
                    conditionScore = (rs.getObject("condition_score") as Number?)?.toInt(),
                    mealCount = (rs.getObject("meal_count") as Number?)?.toInt(),
                )
            },
            seniorId, Date.valueOf(date),
        )

        val emergencyReasons = jdbc.queryForList(
            "SELECT reason FROM alerts WHERE senior_id = ? AND level = 'EMERGENCY' AND CAST(sent_at AS DATE) = ?",
            String::class.java, seniorId, Date.valueOf(date),
        ).toSet()

        val crisisToday = jdbc.queryForObject(
            """SELECT COUNT(*) FROM conversations c JOIN daily_checks d ON c.check_id = d.id
               WHERE d.senior_id = ? AND d.check_date = ? AND c.crisis_flag = TRUE""",
            Int::class.javaObjectType, seniorId, Date.valueOf(date),
        )!! > 0

        val lonelinessDates = jdbc.query(
            """SELECT DISTINCT d.check_date FROM conversations c JOIN daily_checks d ON c.check_id = d.id
               WHERE d.senior_id = ? AND d.check_date BETWEEN ? AND ? AND c.loneliness_signal = TRUE""",
            { rs, _ -> rs.getDate(1).toLocalDate() },
            seniorId, Date.valueOf(date.minusDays(RiskRules.RECENT_DAYS - 1)), Date.valueOf(date),
        ).toSet()

        // 오늘이면 지금 시각, 지난 날이면 그날이 끝난 시각 기준으로 미응답 판단
        val now = if (date == clock.today()) clock.now() else date.atTime(23, 59, 59)

        return RiskInput(
            today = date,
            now = now,
            baselineStart = createdAt.toLocalDate(),
            checks = checks,
            emergencyReasonsToday = emergencyReasons,
            crisisTalkToday = crisisToday,
            lonelinessDates = lonelinessDates,
        )
    }

    // ── DB 저장 도우미 ───────────────────────────────────────────────────────

    private fun reasonsJson(r: RiskResult): String = mapper.writeValueAsString(
        mapOf(
            "reasons" to r.reasons,
            "immediateTriggers" to r.immediateTriggers,
            "baselineReady" to r.baselineReady,
            "lonelinessDays" to r.lonelinessDays,
        )
    )

    /** seniors.baseline_json: 처음 7일 기준선 (웹 표시용 캐시) */
    private fun saveBaseline(seniorId: Long, r: RiskResult) {
        val baseline = r.reasons.associate { it.item to it.baseline }
        jdbc.update("UPDATE seniors SET baseline_json = ? WHERE id = ?", mapper.writeValueAsString(baseline), seniorId)
    }
}
