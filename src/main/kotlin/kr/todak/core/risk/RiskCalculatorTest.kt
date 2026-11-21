package com.todak.core.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** 기준선 11/08~11/14, 판정일 11/21 (DEMO_TODAY) */
class RiskCalculatorTest {

    private val start = LocalDate.of(2026, 11, 8)
    private val today = LocalDate.of(2026, 11, 21)
    private fun day(n: Long) = start.plusDays(n)

    private fun morning(d: LocalDate, time: String, condition: Int) =
        CheckRecord(d, Session.MORNING, d.atTime(LocalTime.parse(time)), conditionScore = condition)

    private fun evening(d: LocalDate, meals: Int) =
        CheckRecord(d, Session.EVENING, d.atTime(18, 30), mealCount = meals)

    /** 매일 08:00 '좋아요', 저녁 3끼인 평범한 14일 */
    private fun healthyDays(): MutableList<CheckRecord> =
        (0L..13L).flatMap { listOf(morning(day(it), "08:00", 2), evening(day(it), 3)) }.toMutableList()

    private fun input(checks: List<CheckRecord>, nowTime: String = "22:00", on: LocalDate = today) =
        RiskInput(today = on, now = on.atTime(LocalTime.parse(nowTime)), baselineStart = start, checks = checks)

    private fun RiskResult.points(item: String) = reasons.first { it.item == item }.contribution

    @Test
    fun `김순자 할머니 서서히 나빠짐 - 81점 위험`() {
        val conditions = listOf(2, 2, 1, 2, 1, 2, 1)  // 평소 평균 1.57
        val checks = mutableListOf<CheckRecord>()
        (0L..10L).forEach {
            checks += morning(day(it), "08:00", conditions[(it % 7).toInt()])
            checks += evening(day(it), 3)
        }
        (11L..13L).forEach { checks += morning(day(it), "10:46", 0) }
        checks += evening(day(11), 1)  // 저녁 2번(11/20, 11/21) 놓침

        val r = RiskCalculator.calculate(input(checks))

        assertEquals(20.0, r.points("NO_RESPONSE"))
        assertEquals(27.7, r.points("WAKE_TIME"))
        assertEquals(20.0, r.points("CONDITION"))
        assertEquals(13.3, r.points("MEAL"))
        assertEquals(81, r.score)
        assertEquals(RiskLevel.DANGER, r.level)
        assertEquals(listOf(Triggers.BAD_CONDITION_3DAYS), r.immediateTriggers)
        assertEquals("08:00", r.reasons.first { it.item == "WAKE_TIME" }.baseline)
        assertEquals("10:46", r.reasons.first { it.item == "WAKE_TIME" }.recent)
        assertTrue(r.baselineReady)
    }

    @Test
    fun `정상 어르신 하루 1시간 늦잠 - 3점 정상`() {
        val checks = healthyDays()
        checks.removeIf { it.date == day(12) && it.session == Session.MORNING }
        checks += morning(day(12), "09:00", 2)

        val r = RiskCalculator.calculate(input(checks))

        assertEquals(3.3, r.points("WAKE_TIME"))
        assertEquals(3, r.score)
        assertEquals(RiskLevel.NORMAL, r.level)
        assertTrue(r.immediateTriggers.isEmpty())
    }

    @Test
    fun `오늘 저녁 마감 전이면 미응답으로 세지 않음`() {
        val checks = healthyDays()
        checks.removeIf { it.date == today && it.session == Session.EVENING }

        val r = RiskCalculator.calculate(input(checks, nowTime = "15:00"))

        assertEquals(0.0, r.points("NO_RESPONSE"))
        assertEquals(0, r.score)
        assertTrue(r.immediateTriggers.isEmpty())
    }

    @Test
    fun `오늘 저녁 21시 지나면 미응답 1회`() {
        val checks = healthyDays()
        checks.removeIf { it.date == today && it.session == Session.EVENING }

        val r = RiskCalculator.calculate(input(checks, nowTime = "21:00"))

        assertEquals(10.0, r.points("NO_RESPONSE"))
        assertEquals(RiskLevel.NORMAL, r.level)
    }

    @Test
    fun `기준선 7일 동안은 점수 없음, 즉시 위험만 적용`() {
        val on = day(3)
        val checks = healthyDays().filter { !it.date.isAfter(on) }

        val r = RiskCalculator.calculate(input(checks, on = on))
        assertFalse(r.baselineReady)
        assertEquals(0, r.score)
        assertEquals(RiskLevel.NORMAL, r.level)
        assertTrue(r.reasons.isEmpty())

        val help = RiskCalculator.calculate(input(checks, on = on).copy(emergencyReasonsToday = setOf(Triggers.HELP_BUTTON)))
        assertEquals(70, help.score)
        assertEquals(RiskLevel.DANGER, help.level)
        assertEquals(listOf(Triggers.HELP_BUTTON), help.immediateTriggers)
    }

    @Test
    fun `8일째부터 점수 계산 시작`() {
        val on = day(7)
        val r = RiskCalculator.calculate(input(healthyDays().filter { !it.date.isAfter(on) }, on = on))
        assertTrue(r.baselineReady)
        assertEquals(0, r.score)
    }

    @Test
    fun `아침 2일 연속 미응답 - 즉시 위험 70점`() {
        val checks = healthyDays()
        checks.removeIf { it.session == Session.MORNING && (it.date == day(12) || it.date == today) }

        val r = RiskCalculator.calculate(input(checks))

        assertTrue(Triggers.MORNING_MISSED_2DAYS in r.immediateTriggers)
        assertEquals(20.0, r.points("NO_RESPONSE"))
        assertEquals(70, r.score)
        assertEquals(RiskLevel.DANGER, r.level)
    }

    @Test
    fun `오늘 아침 11시 전에는 어제 기준으로 판단`() {
        val checks = healthyDays()
        checks.removeIf { it.session == Session.MORNING && (it.date == day(12) || it.date == today) }

        val r = RiskCalculator.calculate(input(checks, nowTime = "10:00"))

        assertFalse(Triggers.MORNING_MISSED_2DAYS in r.immediateTriggers)
    }

    @Test
    fun `아침 저녁 모두 미응답인 날`() {
        val checks = healthyDays()
        checks.removeIf { it.date == today }

        val r = RiskCalculator.calculate(input(checks))

        assertTrue(Triggers.FULL_DAY_MISSED in r.immediateTriggers)
        assertEquals(RiskLevel.DANGER, r.level)
    }

    @Test
    fun `대화 위기 표현 - crisis_flag 또는 알림 사유`() {
        val a = RiskCalculator.calculate(input(healthyDays()).copy(crisisTalkToday = true))
        val b = RiskCalculator.calculate(input(healthyDays()).copy(emergencyReasonsToday = setOf(Triggers.CRISIS_TALK)))
        assertEquals(listOf(Triggers.CRISIS_TALK), a.immediateTriggers)
        assertEquals(listOf(Triggers.CRISIS_TALK), b.immediateTriggers)
        assertEquals(70, a.score)
    }

    @Test
    fun `평소 끼니 평균이 0이면 식사 0점`() {
        val checks = healthyDays().map { if (it.session == Session.EVENING) it.copy(mealCount = 0) else it }

        val r = RiskCalculator.calculate(input(checks))

        assertEquals(0.0, r.points("MEAL"))
    }

    @Test
    fun `일찍 일어나면 지연 0점`() {
        val checks = healthyDays()
        checks.removeIf { it.session == Session.MORNING && it.date.isAfter(day(10)) }
        (11L..13L).forEach { checks += morning(day(it), "07:30", 2) }

        val r = RiskCalculator.calculate(input(checks))

        assertEquals(0.0, r.points("WAKE_TIME"))
    }

    @Test
    fun `외로움 신호 날 수는 최근 3일만`() {
        val r = RiskCalculator.calculate(input(healthyDays()).copy(lonelinessDates = setOf(day(5), day(12), today)))
        assertEquals(2, r.lonelinessDays)
        assertEquals(0, r.score)
    }
}
