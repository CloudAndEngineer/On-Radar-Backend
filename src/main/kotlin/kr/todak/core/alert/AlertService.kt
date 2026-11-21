package kr.todak.core.alert

import kr.todak.core.common.NotFoundException
import kr.todak.core.common.TodakClock
import kr.todak.core.config.TodakProperties
import kr.todak.core.domain.ActionType
import kr.todak.core.domain.Alert
import kr.todak.core.domain.AlertLevel
import kr.todak.core.domain.AlertReason
import kr.todak.core.domain.AlertRepository
import kr.todak.core.domain.AlertStage
import kr.todak.core.domain.AlertStatus
import kr.todak.core.domain.Senior
import kr.todak.core.domain.SeniorRepository
import kr.todak.core.sse.SseEvents
import kr.todak.core.sse.SseHub
import kr.todak.core.timeline.TimelineService
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/** 명세서의 알림 모듈 인터페이스 (Core 내부 함수 호출) */
interface AlertService {
    /**
     * 알림을 엽니다. 같은 어르신·같은 사유가 IN_PROGRESS 면 그 알림을 그대로 돌려주고,
     * 해결 뒤 24시간 안이면 다시 열지 않고 해결된 알림을 돌려줍니다 (EMERGENCY 는 24시간 규칙 제외).
     */
    fun open(seniorId: Long, level: AlertLevel, reason: String, startStage: AlertStage, detail: String? = null): Alert

    /** 단계 진행 멈춤 (next_action_at = null) */
    fun acknowledge(alertId: Long, caregiverId: Long): Alert

    /** 조치 RESOLVED 에서 호출. alertId 가 없으면 그 어르신의 진행 중 알림 모두 */
    fun resolve(seniorId: Long, alertId: Long?): List<Alert>

    /** 1분 스케줄러: next_action_at 이 지난 알림을 다음 단계로 */
    fun escalateDue(): Int
}

@Service
class AlertServiceImpl(
    private val alerts: AlertRepository,
    private val seniors: SeniorRepository,
    private val timeline: TimelineService,
    private val push: PushSender,
    private val center: CenterDispatcher,
    private val sse: SseHub,
    private val clock: TodakClock,
    private val props: TodakProperties,
    private val jdbc: JdbcClient,
) : AlertService {
    private val log = LoggerFactory.getLogger(javaClass)

    /** CAUTION(아침 미응답)은 SENIOR 부터, DANGER·EMERGENCY 는 CAREGIVER 부터. GUARDIAN 은 TODO */
    private fun stagesOf(level: AlertLevel): List<AlertStage> = when (level) {
        AlertLevel.CAUTION -> listOf(AlertStage.SENIOR, AlertStage.CAREGIVER, AlertStage.CENTER)
        AlertLevel.DANGER, AlertLevel.EMERGENCY -> listOf(AlertStage.CAREGIVER, AlertStage.CENTER)
    }

    private fun nextStage(level: AlertLevel, stage: AlertStage): AlertStage? {
        val stages = stagesOf(level)
        val i = stages.indexOf(stage)
        return if (i >= 0) stages.getOrNull(i + 1) else null
    }

    private fun nextActionAt(level: AlertLevel, stage: AlertStage, now: LocalDateTime): LocalDateTime? =
        nextStage(level, stage)?.let { now.plusMinutes(props.alertStepMinutes) }

    @Synchronized
    override fun open(seniorId: Long, level: AlertLevel, reason: String, startStage: AlertStage, detail: String?): Alert {
        alerts.findOpen(seniorId, reason)?.let { return it }
        val now = clock.now()
        if (level != AlertLevel.EMERGENCY) {
            alerts.findResolvedSince(seniorId, reason, now.minusHours(24))?.let {
                log.info("알림 중복 방지: senior={} reason={} (해결 뒤 24시간 이내)", seniorId, reason)
                return it
            }
        }
        val senior = seniors.findById(seniorId).orElseThrow { NotFoundException("SENIOR_NOT_FOUND", "어르신을 찾을 수 없어요") }
        val stage = if (startStage in stagesOf(level)) startStage else stagesOf(level).first()
        // reason_text 컬럼이 없어 문구는 사유 코드에서 만듭니다. detail(점수·인용)은 SSE 에만 덧붙입니다
        val reasonText = listOfNotNull(AlertReason.text(reason), detail?.takeIf { it.isNotBlank() }).joinToString(" · ")
        val alert = alerts.save(
            Alert(
                seniorId = seniorId,
                alertLevel = level,
                alertStage = stage,
                alertStatus = AlertStatus.IN_PROGRESS,
                reason = reason,
                nextActionAt = nextActionAt(level, stage, now),
                createdAt = now,
            ),
        )
        log.info("알림 열림 #{} senior={} {} {} {}", alert.id, seniorId, level, stage, reason)
        sse.publishForSenior(
            seniorId,
            SseEvents.ALERT_CREATED,
            mapOf(
                "alertId" to alert.id,
                "seniorId" to seniorId,
                "level" to level,
                "stage" to stage,
                "reason" to reason,
                "reasonText" to reasonText,
            ),
        )
        enterStage(alert, senior, opened = true)
        return alert
    }

    /** 단계에 들어갈 때 할 일: 어르신 푸시 / 복지사(SSE는 이미 보냄) / 동주민센터 모의 발송 */
    private fun enterStage(alert: Alert, senior: Senior, opened: Boolean) {
        val levelText = when (alert.alertLevel) {
            AlertLevel.CAUTION -> "주의"
            AlertLevel.DANGER -> "위험"
            AlertLevel.EMERGENCY -> "긴급"
        }
        when (alert.alertStage) {
            AlertStage.SENIOR -> {
                val ok = push.send(
                    senior.fcmToken,
                    title = "토닥",
                    body = "${senior.name} ${senior.honorificOrDefault()}, 오늘 아침 인사를 기다리고 있어요. 눌러서 알려 주세요.",
                    data = mapOf("type" to "CHECK_REMINDER", "alertId" to alert.id.toString(), "session" to "MORNING"),
                )
                timeline.system(
                    senior.id!!,
                    ActionType.REMINDER_SENT,
                    if (ok) "어르신 앱으로 다시 알림 (푸시)" else "어르신 앱으로 다시 알림 시도 (기기 푸시 토큰 없음)",
                )
            }
            AlertStage.CAREGIVER -> timeline.system(
                senior.id!!,
                ActionType.ALERT_ESCALATED,
                (if (opened) "[$levelText] 알림 시작 · 담당 복지사에게 알림" else "[$levelText] 확인이 없어 담당 복지사에게 알림") +
                    " · ${alert.reasonText()}",
            )
            AlertStage.CENTER -> {
                center.dispatch(senior.name, senior.addressDong, alert.reasonText())
                timeline.system(
                    senior.id!!,
                    ActionType.ALERT_ESCALATED,
                    "[$levelText] 확인이 없어 동주민센터에 알림 (모의 발송) · ${alert.reasonText()}",
                )
            }
            AlertStage.GUARDIAN -> Unit // TODO 가족 단계
        }
    }

    override fun acknowledge(alertId: Long, caregiverId: Long): Alert {
        val alert = findForCaregiver(alertId, caregiverId)
        if (alert.alertStatus == AlertStatus.RESOLVED || alert.acknowledgedAt != null) return alert
        val now = clock.now()
        val saved = alerts.save(alert.copy(nextActionAt = null, acknowledgedAt = now))
        publishUpdated(saved)
        return saved
    }

    override fun resolve(seniorId: Long, alertId: Long?): List<Alert> {
        val targets = if (alertId != null) {
            val a = alerts.findById(alertId).orElseThrow { NotFoundException("ALERT_NOT_FOUND", "알림을 찾을 수 없어요") }
            if (a.seniorId != seniorId) throw NotFoundException("ALERT_NOT_FOUND", "이 어르신의 알림이 아니에요")
            listOf(a).filter { it.alertStatus == AlertStatus.IN_PROGRESS }
        } else {
            alerts.findOpenBySenior(seniorId)
        }
        val now = clock.now()
        return targets.map {
            val saved = alerts.save(it.copy(alertStatus = AlertStatus.RESOLVED, resolvedAt = now, nextActionAt = null))
            publishUpdated(saved)
            saved
        }
    }

    override fun escalateDue(): Int {
        val now = clock.now()
        var count = 0
        for (alert in alerts.findDueForEscalation(now)) {
            val next = nextStage(alert.alertLevel, alert.alertStage)
            val nextAt = next?.let { nextActionAt(alert.alertLevel, it, now) }
            // 확인(ack)과 겹치면 확인이 이기도록 조건부로 갱신
            val updated = jdbc.sql(
                """UPDATE alerts SET stage = ?, next_action_at = ?
                   WHERE id = ? AND stage = ? AND status = 'IN_PROGRESS' AND next_action_at IS NOT NULL""",
            ).params((next ?: alert.alertStage).name, nextAt, alert.id, alert.alertStage.name).update()
            if (updated != 1 || next == null) continue
            val moved = alert.copy(alertStage = next, nextActionAt = nextAt)
            val senior = seniors.findById(alert.seniorId).orElse(null) ?: continue
            log.info("알림 단계 올림 #{} {} → {}", alert.id, alert.alertStage, next)
            enterStage(moved, senior, opened = false)
            publishUpdated(moved)
            count++
        }
        return count
    }

    private fun publishUpdated(a: Alert) {
        sse.publishForSenior(
            a.seniorId,
            SseEvents.ALERT_UPDATED,
            mapOf(
                "alertId" to a.id,
                "seniorId" to a.seniorId,
                "stage" to a.alertStage,
                "status" to a.alertStatus,
                "acknowledged" to (a.acknowledgedAt != null),
            ),
        )
    }

    private fun findForCaregiver(alertId: Long, caregiverId: Long): Alert {
        val alert = alerts.findById(alertId).orElseThrow { NotFoundException("ALERT_NOT_FOUND", "알림을 찾을 수 없어요") }
        val senior = seniors.findById(alert.seniorId).orElse(null)
        if (senior == null || senior.caregiverId != caregiverId) throw NotFoundException("ALERT_NOT_FOUND", "알림을 찾을 수 없어요")
        return alert
    }
}

@Component
class AlertScheduler(private val alertService: AlertService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${todak.jobs.alert-escalation-delay-ms:60000}", initialDelay = 30_000)
    fun tick() {
        try {
            val n = alertService.escalateDue()
            if (n > 0) log.info("알림 {}건 단계 올림", n)
        } catch (e: Exception) {
            log.error("알림 스케줄러 오류", e)
        }
    }
}
