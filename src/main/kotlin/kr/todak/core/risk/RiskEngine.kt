package kr.todak.core.risk

import kr.todak.core.common.TodakClock
import kr.todak.core.domain.AlertReason
import kr.todak.core.domain.AlertRepository
import kr.todak.core.domain.CheckRecord
import kr.todak.core.domain.CheckRepository
import kr.todak.core.domain.ConversationRepository
import kr.todak.core.domain.RiskLevel
import kr.todak.core.domain.SeniorRepository
import kr.todak.core.domain.Session
import org.springframework.stereotype.Component
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * 위험 점수 엔진 — 명세서 'Core 내부' 인터페이스 그대로.
 * 담당: 주현서. 아래 RuleRiskEngine 은 11/21 확정 '점수 계산 기준'을 옮긴 초안이라
 * 담당자가 자유롭게 고치거나 다른 구현으로 바꿔 끼우면 됩니다 (@Component 하나만 두기).
 */
interface RiskEngine {
    fun assess(seniorId: Long, date: LocalDate): RiskResult
}

data class RiskResult(
    val score: Int,                       // 0~100, 즉시 위험이면 최소 70
    val level: RiskLevel,                 // NORMAL | CAUTION | DANGER
    val reasons: List<Reason>,            // AI 위험 요약의 입력으로 그대로 감
    val immediateTriggers: List<String>,  // 즉시 위험 사유, 없으면 빈 목록
    val baselineReady: Boolean = true,    // 처음 7일은 false (score 0, NORMAL. 즉시 위험은 적용)
    val lonelinessDays: Int = 0,          // 최근 3일 중 외로움 신호가 있던 날 수 → risk-summary 입력
)

data class Reason(val item: String, val baseline: Any?, val recent: Any?, val contribution: Double)

object RiskItem {
    const val NO_RESPONSE = "NO_RESPONSE"
    const val WAKE_TIME = "WAKE_TIME"
    const val CONDITION = "CONDITION"
    const val MEAL = "MEAL"
}

object Trigger {
    const val MORNING_MISSED_2DAYS = "MORNING_MISSED_2DAYS"
    const val FULL_DAY_MISSED = "FULL_DAY_MISSED"
    const val BAD_CONDITION_3DAYS = "BAD_CONDITION_3DAYS"
    const val HELP_BUTTON = "HELP_BUTTON"
    const val CRISIS_TALK = "CRISIS_TALK"
}

/** 하루치 체크. due = 마감 시각이 지났는지 (지났는데 기록이 없으면 미응답) */
data class DayRecord(
    val date: LocalDate,
    val morning: CheckRecord?,
    val evening: CheckRecord?,
    val morningDue: Boolean,
    val eveningDue: Boolean,
) {
    val morningMissed: Boolean get() = morningDue && morning == null
    val eveningMissed: Boolean get() = eveningDue && evening == null
}

data class RiskInput(
    val date: LocalDate,
    val baselineStart: LocalDate,
    val days: List<DayRecord>,   // baselineStart ~ date, 날짜순
    val helpButton: Boolean,
    val crisisTalk: Boolean,
    val lonelinessDays: Int = 0,
)

/** 순수 계산 (DB 없음) — 단위 테스트 대상 */
object RiskCalculator {
    const val BASELINE_DAYS = 7L
    const val RECENT_DAYS = 3L

    fun isCollecting(date: LocalDate, baselineStart: LocalDate) = date.isBefore(baselineStart.plusDays(BASELINE_DAYS))

    fun calculate(input: RiskInput): RiskResult {
        val triggers = triggers(input)
        if (isCollecting(input.date, input.baselineStart)) {
            // 처음 7일은 점수를 내지 않음 ('기준선 수집 중'). 즉시 위험 규칙만 적용
            return if (triggers.isEmpty()) RiskResult(0, RiskLevel.NORMAL, emptyList(), emptyList(), false, input.lonelinessDays)
            else RiskResult(70, RiskLevel.DANGER, emptyList(), triggers, false, input.lonelinessDays)
        }

        val baselineEnd = input.baselineStart.plusDays(BASELINE_DAYS - 1)
        val baseline = input.days.filter { !it.date.isAfter(baselineEnd) }
        val recentFrom = input.date.minusDays(RECENT_DAYS - 1)
        val recent = input.days.filter { !it.date.isBefore(recentFrom) && !it.date.isAfter(input.date) }

        val reasons = listOf(
            noResponse(baseline, recent),
            wakeTime(baseline, recent),
            condition(baseline, recent),
            meal(baseline, recent),
        )
        val raw = reasons.sumOf { it.second }
        var score = raw.roundToInt().coerceIn(0, 100)
        val level = when {
            triggers.isNotEmpty() -> RiskLevel.DANGER
            score >= 70 -> RiskLevel.DANGER
            score >= 40 -> RiskLevel.CAUTION
            else -> RiskLevel.NORMAL
        }
        if (triggers.isNotEmpty()) score = max(score, 70)
        return RiskResult(
            score = score,
            level = level,
            reasons = reasons.map { it.first }.sortedByDescending { it.contribution },
            immediateTriggers = triggers,
            baselineReady = true,
            lonelinessDays = input.lonelinessDays,
        )
    }

    /** 반복 무응답 (최대 30): 최근 3일 놓친 횟수 − 평소(처음 7일 놓친 횟수 × 3/7), 1회당 10점 */
    private fun noResponse(baseline: List<DayRecord>, recent: List<DayRecord>): Pair<Reason, Double> {
        val baseMissed = baseline.sumOf { missedCount(it) } * 3.0 / 7.0
        val recentMissed = recent.sumOf { missedCount(it) }
        val c = ((recentMissed - baseMissed) * 10.0).coerceIn(0.0, 30.0)
        return Reason(RiskItem.NO_RESPONSE, round1(baseMissed), recentMissed, round1(c)) to c
    }

    /** 응답 지연 (최대 30): 아침 응답 시각 평균 차이, 6분당 1점 (3시간 이상 만점, 일찍이면 0). 놓친 아침은 빼고 */
    private fun wakeTime(baseline: List<DayRecord>, recent: List<DayRecord>): Pair<Reason, Double> {
        val b = baseline.mapNotNull { it.morning?.respondedAt?.toLocalTime() }.map { TodakClock.minutesOfDay(it).toDouble() }.average0()
        val r = recent.mapNotNull { it.morning?.respondedAt?.toLocalTime() }.map { TodakClock.minutesOfDay(it).toDouble() }.average0()
        val c = if (b == null || r == null) 0.0 else ((r - b) / 6.0).coerceIn(0.0, 30.0)
        return Reason(RiskItem.WAKE_TIME, TodakClock.hhmmFromMinutes(b), TodakClock.hhmmFromMinutes(r), round1(c)) to c
    }

    /** 컨디션 (최대 20): (평소 평균 − 최근 평균) ÷ 평소 평균 × 20. 응답 안 한 날은 빼고. 평소 0이면 0점 */
    private fun condition(baseline: List<DayRecord>, recent: List<DayRecord>): Pair<Reason, Double> {
        val b = baseline.mapNotNull { it.morning?.conditionScore?.toDouble() }.average0()
        val r = recent.mapNotNull { it.morning?.conditionScore?.toDouble() }.average0()
        val c = ratioDrop(b, r, 20.0)
        return Reason(RiskItem.CONDITION, b?.let { round1(it) }, r?.let { round1(it) }, round1(c)) to c
    }

    /** 식사 (최대 20): (평소 하루 끼니 − 최근 평균) ÷ 평소 끼니 × 20. 응답 안 한 저녁은 빼고 */
    private fun meal(baseline: List<DayRecord>, recent: List<DayRecord>): Pair<Reason, Double> {
        val b = baseline.mapNotNull { it.evening?.mealCount?.toDouble() }.average0()
        val r = recent.mapNotNull { it.evening?.mealCount?.toDouble() }.average0()
        val c = ratioDrop(b, r, 20.0)
        return Reason(RiskItem.MEAL, b?.let { round1(it) }, r?.let { round1(it) }, round1(c)) to c
    }

    fun triggers(input: RiskInput): List<String> {
        val result = mutableListOf<String>()
        val desc = input.days.filter { !it.date.isAfter(input.date) }.sortedByDescending { it.date }

        // 아침 2일 연속 미응답: 마감이 지난 최근 아침 2개가 모두 비어 있음
        val dueMornings = desc.filter { it.morningDue }
        if (dueMornings.size >= 2 && dueMornings.take(2).all { it.morning == null }) result += Trigger.MORNING_MISSED_2DAYS

        // 아침·저녁 모두 미응답인 날 (최근 3일 안)
        val recentFrom = input.date.minusDays(RECENT_DAYS - 1)
        if (desc.any { !it.date.isBefore(recentFrom) && it.morningMissed && it.eveningMissed }) result += Trigger.FULL_DAY_MISSED

        // '안 좋아요' 3일 연속: 최근 아침 3개(응답했거나 마감 지난 것)가 모두 0
        val mornings = desc.filter { it.morning != null || it.morningDue }.take(3)
        if (mornings.size == 3 && mornings.all { it.morning?.conditionScore == 0 }) result += Trigger.BAD_CONDITION_3DAYS

        if (input.helpButton) result += Trigger.HELP_BUTTON
        if (input.crisisTalk) result += Trigger.CRISIS_TALK
        return result
    }

    private fun missedCount(d: DayRecord) = (if (d.morningMissed) 1 else 0) + (if (d.eveningMissed) 1 else 0)

    private fun ratioDrop(b: Double?, r: Double?, weight: Double): Double {
        // 열린 질문: 평소 평균이 0이면 나눌 수 없음 → 0점으로 둠
        if (b == null || r == null || b <= 0.0) return 0.0
        return ((b - r) / b * weight).coerceIn(0.0, weight)
    }

    private fun List<Double>.average0(): Double? = if (isEmpty()) null else average()

    fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}

/** DB 에서 입력을 모아 RiskCalculator 로 계산 */
@Component
class RuleRiskEngine(
    private val seniors: SeniorRepository,
    private val checks: CheckRepository,
    private val alerts: AlertRepository,
    private val conversations: ConversationRepository,
    private val clock: TodakClock,
) : RiskEngine {

    override fun assess(seniorId: Long, date: LocalDate): RiskResult = RiskCalculator.calculate(loadInput(seniorId, date))

    fun loadInput(seniorId: Long, date: LocalDate): RiskInput {
        val senior = seniors.findById(seniorId).orElseThrow()
        val start = senior.baselineStart()
        val now = clock.now()
        val byKey = checks.findRange(seniorId, start, date).associateBy { it.checkDate to it.sessionType }
        val days = generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(date) }
            .map { d ->
                DayRecord(
                    date = d,
                    morning = byKey[d to Session.MORNING],
                    evening = byKey[d to Session.EVENING],
                    morningDue = clock.isDue(d, Session.MORNING, now),
                    eveningDue = clock.isDue(d, Session.EVENING, now),
                )
            }.toList()
        // 긴급 호출·위기 표현은 그 알림이 해결되기 전까지 즉시 위험으로 봅니다
        val open = alerts.findOpenBySenior(seniorId).map { it.reason }.toSet()
        val lonelinessDays = conversations
            .findStartedBetween(seniorId, date.minusDays(RiskCalculator.RECENT_DAYS - 1).atStartOfDay(), date.plusDays(1).atStartOfDay())
            .filter { it.lonelinessSignal }
            .map { it.startedAt.toLocalDate() }
            .distinct().size
        return RiskInput(
            date = date,
            baselineStart = start,
            days = days,
            helpButton = AlertReason.HELP_BUTTON in open,
            crisisTalk = AlertReason.CRISIS_TALK in open,
            lonelinessDays = lonelinessDays,
        )
    }
}
