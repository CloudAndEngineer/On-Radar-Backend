package com.todak.core.risk

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 위험 점수 계산 (11/21 확정안). DB·Spring과 무관한 순수 함수입니다.
 *
 * 항목 점수 = 가중치 × min(1, 평소 대비 나빠진 정도)
 *  - NO_RESPONSE 30: (최근 놓친 횟수 − 평소 놓친 횟수 × 최근 칸 수/14) × 10
 *  - WAKE_TIME   30: (최근 아침 응답 평균 − 평소 평균) ÷ 6분, 일찍이면 0
 *  - CONDITION   20: (평소 − 최근) ÷ 평소 × 20
 *  - MEAL        20: (평소 − 최근) ÷ 평소 × 20
 * 합계 반올림 → 0~39 NORMAL · 40~69 CAUTION · 70~ DANGER.
 * 즉시 위험이 하나라도 있으면 DANGER, 점수는 max(점수, 70). 기준선 기간에도 적용.
 * 평소 평균이 0이거나 데이터가 없으면 그 항목은 0점 (더 나빠질 수 없음).
 */
object RiskCalculator {

    fun calculate(input: RiskInput): RiskResult {
        val slots = Slots(input)
        val triggers = immediateTriggers(input, slots)
        val lonelinessDays = recentDates(input).count { it in input.lonelinessDates }

        val baselineEnd = input.baselineStart.plusDays(RiskRules.BASELINE_DAYS - 1)
        val baselineReady = input.today.isAfter(baselineEnd)

        if (!baselineReady) {
            return finish(0.0, emptyList(), triggers, baselineReady = false, lonelinessDays)
        }

        val baselineDates = dateRange(input.baselineStart, baselineEnd)
        val recent = recentDates(input).filter { it.isAfter(baselineEnd) }

        val reasons = listOf(
            noResponse(slots, baselineDates, recent),
            wakeTime(slots, baselineDates, recent),
            ratioItem("CONDITION", RiskRules.W_CONDITION, slots, baselineDates, recent, Session.MORNING) { it.conditionScore },
            ratioItem("MEAL", RiskRules.W_MEAL, slots, baselineDates, recent, Session.EVENING) { it.mealCount },
        )
        val raw = reasons.sumOf { it.second }
        return finish(raw, reasons.map { it.first }, triggers, baselineReady = true, lonelinessDays)
    }

    // ── 항목별 계산 (Reason, 반올림 전 점수) ────────────────────────────────

    private fun noResponse(s: Slots, baseline: List<LocalDate>, recent: List<LocalDate>): Pair<Reason, Double> {
        val baselineMissed = baseline.sumOf { d -> Session.entries.count { s.isMissed(d, it) } }
        val baselineSlots = baseline.size * 2
        val recentDue = recent.sumOf { d -> Session.entries.count { s.isCounted(d, it) } }
        val recentMissed = recent.sumOf { d -> Session.entries.count { s.isMissed(d, it) } }

        // 평소 놓친 횟수를 최근 칸 수에 맞춰 환산 (6칸이면 × 6/14 = × 3/7, 명세와 동일)
        val expected = if (baselineSlots == 0) 0.0 else baselineMissed.toDouble() * recentDue / baselineSlots
        val points = clamp((recentMissed - expected) * RiskRules.POINTS_PER_MISS, RiskRules.W_NO_RESPONSE)
        return Reason("NO_RESPONSE", round1(expected), recentMissed, round1(points)) to points
    }

    private fun wakeTime(s: Slots, baseline: List<LocalDate>, recent: List<LocalDate>): Pair<Reason, Double> {
        val b = averageMinutes(baseline.mapNotNull { s.get(it, Session.MORNING) })
        val r = averageMinutes(recent.mapNotNull { s.get(it, Session.MORNING) })
        if (b == null || r == null) {
            return Reason("WAKE_TIME", hhmm(b), hhmm(r), 0.0) to 0.0
        }
        val points = clamp((r - b) / RiskRules.MINUTES_PER_POINT, RiskRules.W_WAKE_TIME)
        return Reason("WAKE_TIME", hhmm(b), hhmm(r), round1(points)) to points
    }

    private fun ratioItem(
        item: String,
        weight: Double,
        s: Slots,
        baseline: List<LocalDate>,
        recent: List<LocalDate>,
        session: Session,
        value: (CheckRecord) -> Int?,
    ): Pair<Reason, Double> {
        val b = baseline.mapNotNull { s.get(it, session)?.let(value) }.averageOrNull()
        val r = recent.mapNotNull { s.get(it, session)?.let(value) }.averageOrNull()
        val points = if (b == null || r == null || b <= 0.0) 0.0 else clamp((b - r) / b * weight, weight)
        return Reason(item, round1(b ?: 0.0), round1(r ?: 0.0), round1(points)) to points
    }

    // ── 즉시 위험 ────────────────────────────────────────────────────────────

    private fun immediateTriggers(input: RiskInput, s: Slots): List<String> {
        val result = mutableListOf<String>()
        val today = input.today

        // 마감이 지난 가장 최근 아침 2개가 모두 미응답
        val lastDueMorning = if (s.isDue(today, Session.MORNING)) today else today.minusDays(1)
        if (s.isMissed(lastDueMorning, Session.MORNING) && s.isMissed(lastDueMorning.minusDays(1), Session.MORNING)) {
            result += Triggers.MORNING_MISSED_2DAYS
        }

        // 아침·저녁이 모두 마감된 가장 최근 날에 둘 다 미응답
        val lastFullDay = if (s.isDue(today, Session.EVENING)) today else today.minusDays(1)
        if (s.isMissed(lastFullDay, Session.MORNING) && s.isMissed(lastFullDay, Session.EVENING)) {
            result += Triggers.FULL_DAY_MISSED
        }

        // 가장 최근 아침 응답부터 3일 연속 '안 좋아요'(0)
        val lastMorning = if (s.get(today, Session.MORNING) != null) today else today.minusDays(1)
        if ((0L..2L).all { s.get(lastMorning.minusDays(it), Session.MORNING)?.conditionScore == 0 }) {
            result += Triggers.BAD_CONDITION_3DAYS
        }

        if (Triggers.HELP_BUTTON in input.emergencyReasonsToday) result += Triggers.HELP_BUTTON
        if (input.crisisTalkToday || Triggers.CRISIS_TALK in input.emergencyReasonsToday) result += Triggers.CRISIS_TALK
        return result
    }

    // ── 마무리 ───────────────────────────────────────────────────────────────

    private fun finish(
        raw: Double,
        reasons: List<Reason>,
        triggers: List<String>,
        baselineReady: Boolean,
        lonelinessDays: Int,
    ): RiskResult {
        var score = raw.roundToInt().coerceIn(0, 100)
        var level = when {
            score >= RiskRules.DANGER_FROM -> RiskLevel.DANGER
            score >= RiskRules.CAUTION_FROM -> RiskLevel.CAUTION
            else -> RiskLevel.NORMAL
        }
        if (triggers.isNotEmpty()) {
            score = max(score, RiskRules.IMMEDIATE_SCORE)
            level = RiskLevel.DANGER
        }
        return RiskResult(score, level, reasons, triggers, baselineReady, lonelinessDays)
    }

    // ── 도우미 ───────────────────────────────────────────────────────────────

    /** (날짜, 세션) 칸 단위로 체크를 찾고, 마감·미응답을 판단합니다. */
    private class Slots(private val input: RiskInput) {
        private val byKey = input.checks.associateBy { it.date to it.session }

        fun get(date: LocalDate, session: Session): CheckRecord? = byKey[date to session]

        /** 마감 시각이 지났는가 (아침 11:00, 저녁 21:00) */
        fun isDue(date: LocalDate, session: Session): Boolean =
            !input.now.isBefore(date.atTime(RiskRules.deadline(session)))

        /** 행이 없고, 마감이 지났고, 등록일 이후인 칸 */
        fun isMissed(date: LocalDate, session: Session): Boolean =
            !date.isBefore(input.baselineStart) && get(date, session) == null && isDue(date, session)

        /** 계산에 넣는 칸: 응답했거나 마감이 지난 칸 (아직 시간이 안 된 칸은 뺌) */
        fun isCounted(date: LocalDate, session: Session): Boolean =
            get(date, session) != null || isDue(date, session)
    }

    private fun recentDates(input: RiskInput): List<LocalDate> =
        dateRange(input.today.minusDays(RiskRules.RECENT_DAYS - 1), input.today)

    private fun dateRange(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

    private fun averageMinutes(records: List<CheckRecord>): Double? =
        records.map { it.respondedAt.toLocalTime().toSecondOfDay() / 60.0 }.averageOrNull()

    private fun List<Number>.averageOrNull(): Double? =
        if (isEmpty()) null else sumOf { it.toDouble() } / size

    private fun hhmm(minutes: Double?): String =
        minutes?.let { LocalTime.ofSecondOfDay((it.roundToInt() * 60L).coerceIn(0, 86_399)).toString() } ?: "-"

    private fun clamp(v: Double, maxValue: Double) = min(maxValue, max(0.0, v))

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
}
