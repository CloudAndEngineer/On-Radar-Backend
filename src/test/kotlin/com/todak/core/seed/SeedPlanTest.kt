package com.todak.core.seed

import com.todak.core.risk.RiskCalculator
import com.todak.core.risk.RiskInput
import com.todak.core.risk.RiskLevel
import com.todak.core.risk.RiskResult
import com.todak.core.risk.Triggers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** 시드 데이터가 '무너지는 2명만 위험, 나머지는 정상'이 되는지 실제 엔진으로 확인 */
class SeedPlanTest {

    private val today = LocalDate.of(2026, 11, 21)
    private val seniors = SeedPlan.build(today)

    private fun assess(s: SeedPlan.Senior, time: String): RiskResult = RiskCalculator.calculate(
        RiskInput(
            today = today,
            now = today.atTime(LocalTime.parse(time)),
            baselineStart = today.minusDays(SeedPlan.DAYS - 1),
            checks = s.checks,
            lonelinessDates = s.conversations.filter { it.loneliness }.map { it.date }.toSet(),
        )
    )

    @Test
    fun `밤 22시 기준 - 위험 2명, 나머지 정상`() {
        val results = seniors.associate { it.name to assess(it, "22:00") }
        results.forEach { (name, r) -> println("$name ${r.score} ${r.level} ${r.immediateTriggers} lonely=${r.lonelinessDays}") }

        assertEquals(81, results.getValue("김순자").score)
        assertEquals(listOf(Triggers.BAD_CONDITION_3DAYS), results.getValue("김순자").immediateTriggers)
        assertEquals(RiskLevel.DANGER, results.getValue("박영철").level)
        assertTrue(Triggers.MORNING_MISSED_2DAYS in results.getValue("박영철").immediateTriggers)
        assertEquals(2, results.getValue("최말순").lonelinessDays)
        // 한복례(시연 폰)는 오늘 체크를 비워 둬서, 시연 없이 22시가 지나면 '하루 종일 미응답'으로 위험이 맞음
        assertEquals(listOf(Triggers.FULL_DAY_MISSED), results.getValue("한복례").immediateTriggers)
        results.filterKeys { it !in setOf("김순자", "박영철", "한복례") }.forEach { (name, r) ->
            assertEquals(RiskLevel.NORMAL, r.level, name)
        }
    }

    @Test
    fun `시연 시간대(14시)에도 위험 2명, 나머지 정상`() {
        val results = seniors.associate { it.name to assess(it, "14:00") }
        results.forEach { (name, r) -> println("$name ${r.score} ${r.level} ${r.immediateTriggers}") }

        assertEquals(RiskLevel.DANGER, results.getValue("김순자").level)
        assertEquals(RiskLevel.DANGER, results.getValue("박영철").level)
        results.filterKeys { it != "김순자" && it != "박영철" }.forEach { (name, r) ->
            assertEquals(RiskLevel.NORMAL, r.level, name)
        }
    }

    @Test
    fun `대화는 모두 그날 체크가 있는 칸에 붙음`() {
        seniors.forEach { s ->
            s.conversations.forEach { c ->
                assertTrue(s.checks.any { it.date == c.date && it.session == c.session }, "${s.name} ${c.date} ${c.session}")
            }
        }
    }
}
