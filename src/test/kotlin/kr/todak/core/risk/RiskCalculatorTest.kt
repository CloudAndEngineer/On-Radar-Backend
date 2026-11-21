package kr.todak.core.risk

import kr.todak.core.domain.CheckRecord
import kr.todak.core.domain.RiskLevel
import kr.todak.core.domain.Session
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 명세서 '점수 계산 기준' 표의 두 예시를 그대로 재현 */
class RiskCalculatorTest {
    private val start = LocalDate.of(2026, 11, 8)
    private val date = LocalDate.of(2026, 11, 21)

    private fun day(i: Int, wake: LocalTime?, cond: Int?, meals: Int?): DayRecord {
        val d = start.plusDays(i.toLong())
        return DayRecord(
            date = d,
            morning = wake?.let { CheckRecord(seniorId = 1, checkDate = d, sessionType = Session.MORNING, conditionScore = cond, respondedAt = d.atTime(it)) },
            evening = meals?.let { CheckRecord(seniorId = 1, checkDate = d, sessionType = Session.EVENING, mealCount = it, respondedAt = d.atTime(19, 0)) },
            morningDue = true,
            eveningDue = true,
        )
    }

    private fun input(days: List<DayRecord>) = RiskInput(date, start, days, helpButton = false, crisisTalk = false)

    @Test
    fun `김순자 할머니 - 서서히 나빠짐 81점 위험`() {
        val conds = listOf(2, 2, 1, 2, 2, 1, 2) // 평균 1.7 (평소 1.6 근처, 최근 0이면 어차피 20점)
        val days = (0..6).map { day(it, LocalTime.of(7, 0), conds[it], 3) } +
            (7..10).map { day(it, LocalTime.of(8, 0), 1, 2) } +
            listOf(
                day(11, LocalTime.of(9, 46), 0, null), // 저녁 놓침
                day(12, LocalTime.of(9, 46), 0, null), // 저녁 놓침
                day(13, LocalTime.of(9, 46), 0, 1),
            )
        val r = RiskCalculator.calculate(input(days))
        val byItem = r.reasons.associateBy { it.item }
        assertEquals(20.0, byItem.getValue(RiskItem.NO_RESPONSE).contribution)
        assertEquals(27.7, byItem.getValue(RiskItem.WAKE_TIME).contribution)
        assertEquals(20.0, byItem.getValue(RiskItem.CONDITION).contribution)
        assertEquals(13.3, byItem.getValue(RiskItem.MEAL).contribution)
        assertEquals(81, r.score)
        assertEquals(RiskLevel.DANGER, r.level)
        assertTrue(Trigger.BAD_CONDITION_3DAYS in r.immediateTriggers)
    }

    @Test
    fun `정상 어르신 - 하루 1시간 늦잠 3점 정상`() {
        val days = (0..12).map { day(it, LocalTime.of(7, 0), 2, 3) } + day(13, LocalTime.of(8, 0), 2, 3)
        val r = RiskCalculator.calculate(input(days))
        assertEquals(3, r.score)
        assertEquals(RiskLevel.NORMAL, r.level)
        assertTrue(r.immediateTriggers.isEmpty())
    }

    @Test
    fun `아침 2일 연속 미응답이면 즉시 위험 70점`() {
        val days = (0..11).map { day(it, LocalTime.of(7, 0), 2, 3) } +
            listOf(day(12, null, null, 3), day(13, null, null, 3))
        val r = RiskCalculator.calculate(input(days))
        assertEquals(RiskLevel.DANGER, r.level)
        assertTrue(r.score >= 70)
        assertTrue(Trigger.MORNING_MISSED_2DAYS in r.immediateTriggers)
    }

    @Test
    fun `처음 7일은 기준선 수집 중`() {
        val days = (0..3).map { day(it, LocalTime.of(7, 0), 2, 3) }
        val r = RiskCalculator.calculate(RiskInput(start.plusDays(3), start, days, false, false))
        assertTrue(!r.baselineReady)
        assertEquals(0, r.score)
    }
}
