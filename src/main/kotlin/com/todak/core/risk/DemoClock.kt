package com.todak.core.risk

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 시연용 시계. Core 전체가 '오늘'과 '지금'을 여기서 가져가면 시연 시각을 한 곳에서 맞출 수 있습니다.
 *  - DEMO_TODAY (예: 2026-11-21): 비우면 실제 오늘
 *  - DEMO_TIME  (예: 08:30)     : 비우면 실제 현재 시각. 시연 때 아침 체크를 보여주려면 08:30 등으로 고정
 */
@Component
class DemoClock(
    @Value("\${DEMO_TODAY:}") demoToday: String,
    @Value("\${DEMO_TIME:}") demoTime: String,
) {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val fixedDate: LocalDate? = demoToday.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
    private val fixedTime: LocalTime? = demoTime.trim().takeIf { it.isNotEmpty() }?.let(LocalTime::parse)

    fun today(): LocalDate = fixedDate ?: LocalDate.now(zone)

    fun now(): LocalDateTime = today().atTime(fixedTime ?: LocalTime.now(zone).withNano(0))
}
