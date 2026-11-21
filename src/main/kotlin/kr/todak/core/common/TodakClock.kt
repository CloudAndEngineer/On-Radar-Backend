package kr.todak.core.common

import kr.todak.core.config.TodakProperties
import kr.todak.core.domain.Session
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 모든 "오늘/지금"은 여기서 가져옵니다.
 * DEMO_TODAY 가 있으면 날짜만 그 날로 바꾸고 시각은 실제 한국 시각을 씁니다.
 */
@Component
class TodakClock(props: TodakProperties) {

    val demoToday: LocalDate? = props.demoToday.trim().takeIf { it.isNotEmpty() }?.let(LocalDate::parse)

    val morningOpensAt: LocalTime = LocalTime.parse(props.morningOpensAt)
    val morningDeadline: LocalTime = LocalTime.parse(props.morningDeadline)
    val eveningOpensAt: LocalTime = LocalTime.parse(props.eveningOpensAt)
    val eveningDeadline: LocalTime = LocalTime.parse(props.eveningDeadline)

    fun today(): LocalDate = demoToday ?: LocalDate.now(ZONE)

    fun now(): LocalDateTime =
        LocalDateTime.of(today(), LocalTime.now(ZONE)).truncatedTo(ChronoUnit.SECONDS)

    fun opensAt(date: LocalDate, session: Session): LocalDateTime = when (session) {
        Session.MORNING -> date.atTime(morningOpensAt)
        Session.EVENING -> date.atTime(eveningOpensAt)
    }

    fun deadline(date: LocalDate, session: Session): LocalDateTime = when (session) {
        Session.MORNING -> date.atTime(morningDeadline)
        Session.EVENING -> date.atTime(eveningDeadline)
    }

    /** 이 체크의 마감이 지났는지 (지났는데 행이 없으면 미응답) */
    fun isDue(date: LocalDate, session: Session, now: LocalDateTime = now()): Boolean =
        !now.isBefore(deadline(date, session))

    companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun toOffset(t: LocalDateTime?): OffsetDateTime? =
            t?.truncatedTo(ChronoUnit.SECONDS)?.atZone(ZONE)?.toOffsetDateTime()

        fun hhmm(t: LocalTime?): String? = t?.format(HHMM)

        fun minutesOfDay(t: LocalTime): Int = t.hour * 60 + t.minute

        fun hhmmFromMinutes(minutes: Double?): String? {
            if (minutes == null) return null
            val m = Math.round(minutes).toInt().coerceIn(0, 24 * 60 - 1)
            return "%02d:%02d".format(m / 60, m % 60)
        }
    }
}
