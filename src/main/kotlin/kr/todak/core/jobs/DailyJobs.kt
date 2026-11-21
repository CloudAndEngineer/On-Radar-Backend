package kr.todak.core.jobs

import kr.todak.core.ai.AiGateway
import kr.todak.core.alert.AlertService
import kr.todak.core.alert.PushSender
import kr.todak.core.auth.AuthCaregiver
import kr.todak.core.common.TodakClock
import kr.todak.core.config.TodakProperties
import kr.todak.core.domain.ActionType
import kr.todak.core.domain.AlertLevel
import kr.todak.core.domain.AlertReason
import kr.todak.core.domain.AlertStage
import kr.todak.core.domain.CheckRepository
import kr.todak.core.domain.SeniorRepository
import kr.todak.core.domain.Session
import kr.todak.core.risk.RiskService
import kr.todak.core.seed.DemoSeeder
import kr.todak.core.sse.SseHub
import kr.todak.core.timeline.TimelineService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Component
class DailyJobs(
    private val seniors: SeniorRepository,
    private val checks: CheckRepository,
    private val alerts: AlertService,
    private val risk: RiskService,
    private val timeline: TimelineService,
    private val clock: TodakClock,
    private val push: PushSender,
    @Value("\${todak.jobs.check-reminder-enabled:true}") private val reminderEnabled: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 8시: 아침 체크 알림 푸시 (이미 한 어르신은 건너뜀) */
    @Scheduled(cron = "\${todak.jobs.morning-reminder-cron:0 0 8 * * *}", zone = "Asia/Seoul")
    fun morningReminderScheduled() = safely("morning-reminder") { if (reminderEnabled) checkReminder(Session.MORNING) }

    /** 18시: 저녁 체크 알림 푸시 */
    @Scheduled(cron = "\${todak.jobs.evening-reminder-cron:0 0 18 * * *}", zone = "Asia/Seoul")
    fun eveningReminderScheduled() = safely("evening-reminder") { if (reminderEnabled) checkReminder(Session.EVENING) }

    fun checkReminder(session: Session): Int {
        val date = clock.today()
        var sent = 0
        for (s in seniors.findAll()) {
            if (checks.findOne(s.id!!, date, session.name) != null) continue
            val body = if (session == Session.MORNING) {
                "${s.name} ${s.honorificOrDefault()}, 좋은 아침이에요. 오늘 몸 상태를 알려 주세요."
            } else {
                "${s.name} ${s.honorificOrDefault()}, 오늘 식사는 몇 번 하셨어요?"
            }
            if (push.send(s.fcmToken, "토닥", body, mapOf("type" to "CHECK_OPEN", "session" to session.name))) sent++
        }
        log.info("{} 체크 알림 {}명", session, sent)
        return sent
    }

    /** 11시(아침 알림 8시 + 3시간): 아침 미응답 → 타임라인 CHECK_MISSED + CAUTION 알림(어르신 재알림부터) */
    @Scheduled(cron = "\${todak.jobs.morning-missed-cron:0 0 11 * * *}", zone = "Asia/Seoul")
    fun morningMissedScheduled() = safely("morning-missed") { morningMissed() }

    /** 21시(저녁 알림 18시 + 3시간): 저녁만 빠진 날은 알림 없이 CHECK_MISSED 만. 모든 어르신 점수 다시 계산(아침·저녁 모두 미응답 판정) */
    @Scheduled(cron = "\${todak.jobs.evening-close-cron:0 0 21 * * *}", zone = "Asia/Seoul")
    fun eveningCloseScheduled() = safely("evening-close") { eveningClose() }

    fun morningMissed(): Int {
        val date = clock.today()
        var count = 0
        for (s in seniors.findAll()) {
            if (checks.findOne(s.id!!, date, Session.MORNING.name) != null) continue
            val memo = "$date 아침 체크 미응답"
            if (!timeline.exists(s.id, ActionType.CHECK_MISSED, memo)) timeline.system(s.id, ActionType.CHECK_MISSED, memo)
            alerts.open(s.id, AlertLevel.CAUTION, AlertReason.MORNING_MISSED, AlertStage.SENIOR)
            risk.reassess(s.id)
            count++
        }
        log.info("아침 미응답 {}명 처리 ({})", count, date)
        return count
    }

    fun eveningClose(): Int {
        val date = clock.today()
        var missed = 0
        for (s in seniors.findAll()) {
            if (checks.findOne(s.id!!, date, Session.EVENING.name) == null) {
                val memo = "$date 저녁 체크 미응답"
                if (!timeline.exists(s.id, ActionType.CHECK_MISSED, memo)) timeline.system(s.id, ActionType.CHECK_MISSED, memo)
                missed++
            }
            risk.reassess(s.id)
        }
        log.info("저녁 마감: 미응답 {}명 ({})", missed, date)
        return missed
    }

    private fun safely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.error("작업 실패: {}", name, e)
        }
    }
}

/** 시연·리허설용 버튼 (복지사 로그인 필요) */
@RestController
@RequestMapping("/api/demo")
class DemoController(
    private val jobs: DailyJobs,
    private val alerts: AlertService,
    private val seeder: DemoSeeder,
) {
    @PostMapping("/jobs/morning-missed")
    fun morningMissed(me: AuthCaregiver) = mapOf("processed" to jobs.morningMissed())

    /** 체크 알림 푸시 지금 보내기: session=MORNING|EVENING */
    @PostMapping("/jobs/reminder")
    fun reminder(me: AuthCaregiver, @RequestParam(defaultValue = "MORNING") session: Session) =
        mapOf("sent" to jobs.checkReminder(session))

    @PostMapping("/jobs/evening-close")
    fun eveningClose(me: AuthCaregiver) = mapOf("eveningMissed" to jobs.eveningClose())

    /** 1분 기다리지 않고 지금 단계 올리기 (next_action_at 이 지난 것만) */
    @PostMapping("/jobs/escalate")
    fun escalate(me: AuthCaregiver) = mapOf("escalated" to alerts.escalateDue())

    /** 어르신 데이터를 지우고 시드를 다시 넣습니다. 앱은 기기 등록을 다시 해야 합니다 */
    @PostMapping("/reset")
    fun reset(me: AuthCaregiver) = mapOf("seniors" to seeder.reseed(me.id))
}

@RestController
class HealthController(
    private val clock: TodakClock,
    private val ai: AiGateway,
    private val push: PushSender,
    private val sse: SseHub,
    private val props: TodakProperties,
) {
    @GetMapping("/health", "/api/health")
    fun health() = mapOf(
        "status" to "UP",
        "today" to clock.today().toString(),
        "now" to TodakClock.toOffset(clock.now()).toString(),
        "demoToday" to clock.demoToday?.toString(),
        "aiMode" to ai.mode,
        "fcm" to push.enabled,
        "alertStepMinutes" to props.alertStepMinutes,
        "sseConnections" to sse.connectionCount(),
    )
}
