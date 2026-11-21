package kr.todak.core.senior

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import kr.todak.core.alert.AlertService
import kr.todak.core.auth.AuthCaregiver
import kr.todak.core.check.SessionStatus
import kr.todak.core.check.TodayStatusService
import kr.todak.core.common.BadRequestException
import kr.todak.core.common.NotFoundException
import kr.todak.core.common.TodakClock
import kr.todak.core.domain.ActionType
import kr.todak.core.domain.ActorType
import kr.todak.core.domain.Alert
import kr.todak.core.domain.AlertLevel
import kr.todak.core.domain.AlertRepository
import kr.todak.core.domain.AlertStage
import kr.todak.core.domain.AlertStatus
import kr.todak.core.domain.CheckRepository
import kr.todak.core.domain.CheckStatus
import kr.todak.core.domain.ConversationRepository
import kr.todak.core.domain.RiskLevel
import kr.todak.core.domain.Senior
import kr.todak.core.domain.SeniorRepository
import kr.todak.core.domain.Session
import kr.todak.core.domain.TimelineRepository
import kr.todak.core.risk.Reason
import kr.todak.core.risk.RiskCalculator
import kr.todak.core.risk.RiskService
import kr.todak.core.timeline.TimelineService
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.OffsetDateTime

// ---- DTO (명세서 JSON 예시 기준) ----

data class SummaryResponse(val dangerCount: Int, val cautionCount: Int, val normalCount: Int, val todayMissedCount: Int)

data class OpenAlertDto(val alertId: Long, val level: AlertLevel, val stage: AlertStage)
data class Last3Days(val mealAvg: Double?, val worstCondition: Int?, val avgMorningRespondedAt: String?)
data class TodayMissed(val morning: Boolean, val evening: Boolean)

data class SeniorListItem(
    val seniorId: Long,
    val name: String,
    val age: Int,
    val addressDong: String?,
    val riskLevel: RiskLevel,
    val score: Int,
    val aiSummary: String?,
    val last3Days: Last3Days,
    val todayMissed: TodayMissed,
    val openAlert: OpenAlertDto?,
    val baselineReady: Boolean,
)

data class TodayDto(val morning: SessionStatus, val evening: SessionStatus)

data class SeniorDetail(
    val seniorId: Long,
    val name: String,
    val honorific: String,
    val age: Int,
    val addressDong: String?,
    val monitoringStartDate: LocalDate,
    val baselineReady: Boolean,
    val riskLevel: RiskLevel,
    val score: Int,
    val aiSummary: String?,
    val reasons: List<Reason>,
    val immediateTriggers: List<String>,
    val today: TodayDto,
    val openAlert: OpenAlertDto?,
    val callNoAnswerCount: Int, // 진행 중 알림 이후 CALL_NO_ANSWER 횟수 — 2 이상이면 웹이 [돌봄 체계에 확인 요청] 강조
)

data class PatternDay(
    val date: LocalDate,
    @get:JsonProperty("isBaseline") val isBaseline: Boolean,
    val mealCount: Int?,
    val morningRespondedAt: String?,
    val conditionScore: Int?,
)

data class PatternResponse(val baselineDays: Int, val days: List<PatternDay>)

data class ConversationDto(
    val conversationId: Long,
    val session: Session?,
    val startedAt: OffsetDateTime?,
    val summary: String?,
    val lonelinessSignal: Boolean,
    val lonelinessEvidence: String?,
    val crisisFlag: Boolean,
    val crisisEvidence: String?,
)

data class TimelineDto(
    val eventId: Long,
    val actorType: ActorType,
    val actorName: String?,
    val action: ActionType,
    val memo: String?,
    val createdAt: OffsetDateTime?,
)

data class ActionRequest(@field:NotNull val action: ActionType?, val memo: String? = null, val alertId: Long? = null)
data class ActionResponse(val eventId: Long, val createdAt: OffsetDateTime?)
data class AckResponse(val alertId: Long, val stage: AlertStage, val status: AlertStatus, val acknowledgedAt: OffsetDateTime?)

// ---- 조회 ----

@Service
class SeniorQueryService(
    private val seniors: SeniorRepository,
    private val checks: CheckRepository,
    private val alerts: AlertRepository,
    private val conversations: ConversationRepository,
    private val timelineRepo: TimelineRepository,
    private val todayStatus: TodayStatusService,
    private val risk: RiskService,
    private val clock: TodakClock,
) {
    private val levelOrder = mapOf(RiskLevel.DANGER to 0, RiskLevel.CAUTION to 1, RiskLevel.NORMAL to 2)

    fun mine(caregiverId: Long, seniorId: Long): Senior {
        val s = seniors.findById(seniorId).orElse(null)
        if (s == null || s.caregiverId != caregiverId) throw NotFoundException("SENIOR_NOT_FOUND", "담당 어르신을 찾을 수 없어요")
        return s
    }

    /** 위험도순: DANGER → CAUTION → NORMAL, 같은 단계는 점수 높은 순 */
    fun list(caregiverId: Long): List<SeniorListItem> {
        val today = clock.today()
        val now = clock.now()
        return seniors.findByCaregiverId(caregiverId).map { s ->
            val a = risk.latest(s.id!!)
            val recent = checks.findRange(s.id, today.minusDays(2), today)
            val mornings = recent.filter { it.sessionType == Session.MORNING }
            val evenings = recent.filter { it.sessionType == Session.EVENING }
            val avgMorning = mornings.map { TodakClock.minutesOfDay(it.respondedAt.toLocalTime()).toDouble() }
                .takeIf { it.isNotEmpty() }?.average()
            SeniorListItem(
                seniorId = s.id,
                name = s.name,
                age = s.ageOn(today),
                addressDong = s.addressDong,
                riskLevel = a?.riskLevel ?: RiskLevel.NORMAL,
                score = a?.score ?: 0,
                aiSummary = a?.aiSummary,
                last3Days = Last3Days(
                    mealAvg = evenings.mapNotNull { it.mealCount?.toDouble() }.takeIf { it.isNotEmpty() }?.average()
                        ?.let { RiskCalculator.round1(it) },
                    worstCondition = mornings.mapNotNull { it.conditionScore }.minOrNull(),
                    avgMorningRespondedAt = TodakClock.hhmmFromMinutes(avgMorning),
                ),
                todayMissed = TodayMissed(
                    morning = todayStatus.status(s.id, today, Session.MORNING, now).status == CheckStatus.MISSED,
                    evening = todayStatus.status(s.id, today, Session.EVENING, now).status == CheckStatus.MISSED,
                ),
                openAlert = openAlert(s.id),
                baselineReady = a?.let { risk.stored(it).baselineReady } ?: !RiskCalculator.isCollecting(today, s.baselineStart()),
            )
        }.sortedWith(compareBy<SeniorListItem> { levelOrder[it.riskLevel] }.thenByDescending { it.score }.thenBy { it.name })
    }

    fun summary(caregiverId: Long): SummaryResponse {
        val items = list(caregiverId)
        return SummaryResponse(
            dangerCount = items.count { it.riskLevel == RiskLevel.DANGER },
            cautionCount = items.count { it.riskLevel == RiskLevel.CAUTION },
            normalCount = items.count { it.riskLevel == RiskLevel.NORMAL },
            todayMissedCount = items.count { it.todayMissed.morning || it.todayMissed.evening },
        )
    }

    fun detail(caregiverId: Long, seniorId: Long): SeniorDetail {
        val s = mine(caregiverId, seniorId)
        val today = clock.today()
        val now = clock.now()
        val a = risk.latest(seniorId)
        val open = alerts.findOpenBySenior(seniorId)
        val since = open.minOfOrNull { it.createdAt }
        val noAnswer = if (since == null) 0 else timelineRepo.findLatest(seniorId, 200)
            .count { it.actionType == ActionType.CALL_NO_ANSWER && !it.createdAt.isBefore(since) }
        return SeniorDetail(
            seniorId = seniorId,
            name = s.name,
            honorific = s.honorificOrDefault(),
            age = s.ageOn(today),
            addressDong = s.addressDong,
            monitoringStartDate = s.baselineStart(),
            baselineReady = a?.let { risk.stored(it).baselineReady } ?: !RiskCalculator.isCollecting(today, s.baselineStart()),
            riskLevel = a?.riskLevel ?: RiskLevel.NORMAL,
            score = a?.score ?: 0,
            aiSummary = a?.aiSummary,
            reasons = a?.let { risk.stored(it).reasons } ?: emptyList(),
            immediateTriggers = a?.let { risk.stored(it).immediateTriggers } ?: emptyList(),
            today = TodayDto(
                todayStatus.status(seniorId, today, Session.MORNING, now),
                todayStatus.status(seniorId, today, Session.EVENING, now),
            ),
            openAlert = openAlert(seniorId),
            callNoAnswerCount = noAnswer,
        )
    }

    /** 날짜별 식사·아침 응답 시각·컨디션. 체크 안 한 칸은 null */
    fun pattern(caregiverId: Long, seniorId: Long, days: Int): PatternResponse {
        val s = mine(caregiverId, seniorId)
        val n = days.coerceIn(1, 60)
        val today = clock.today()
        val from = today.minusDays((n - 1).toLong())
        val byKey = checks.findRange(seniorId, from, today).associateBy { it.checkDate to it.sessionType }
        val baselineEnd = s.baselineStart().plusDays(RiskCalculator.BASELINE_DAYS - 1)
        val list = (0 until n).map { i ->
            val d = from.plusDays(i.toLong())
            val m = byKey[d to Session.MORNING]
            val e = byKey[d to Session.EVENING]
            PatternDay(
                date = d,
                isBaseline = !d.isBefore(s.baselineStart()) && !d.isAfter(baselineEnd),
                mealCount = e?.mealCount,
                morningRespondedAt = TodakClock.hhmm(m?.respondedAt?.toLocalTime()),
                conditionScore = m?.conditionScore,
            )
        }
        return PatternResponse(RiskCalculator.BASELINE_DAYS.toInt(), list)
    }

    fun conversations(caregiverId: Long, seniorId: Long, limit: Int): List<ConversationDto> {
        mine(caregiverId, seniorId)
        return conversations.findRecent(seniorId, limit.coerceIn(1, 50)).map {
            ConversationDto(
                conversationId = it.id!!,
                session = checks.findById(it.checkId).map { c -> c.sessionType }.orElse(null),
                startedAt = TodakClock.toOffset(it.startedAt),
                summary = it.summary,
                lonelinessSignal = it.lonelinessSignal,
                lonelinessEvidence = it.lonelinessEvidence,
                crisisFlag = it.crisisFlag,
                crisisEvidence = it.crisisEvidence,
            )
        }
    }

    fun timeline(caregiverId: Long, seniorId: Long, limit: Int): List<TimelineDto> {
        mine(caregiverId, seniorId)
        return timelineRepo.findLatest(seniorId, limit.coerceIn(1, 500)).map {
            TimelineDto(it.id!!, it.actorType, it.actorName, it.actionType, it.memo, TodakClock.toOffset(it.createdAt))
        }
    }

    private fun openAlert(seniorId: Long): OpenAlertDto? {
        // EMERGENCY > DANGER > CAUTION 순으로 가장 급한 것 하나
        return alerts.findOpenBySenior(seniorId).maxByOrNull { it.alertLevel.ordinal }?.toDto()
    }

    private fun Alert.toDto() = OpenAlertDto(id!!, alertLevel, alertStage)
}

// ---- 웹 컨트롤러 ----

@RestController
@RequestMapping("/api")
class WebController(
    private val query: SeniorQueryService,
    private val timeline: TimelineService,
    private val alerts: AlertService,
    private val riskLater: RiskLater,
) {

    @GetMapping("/caregivers/me/summary")
    fun summary(me: AuthCaregiver) = query.summary(me.id)

    @GetMapping("/caregivers/me/seniors")
    fun seniors(me: AuthCaregiver) = query.list(me.id)

    @GetMapping("/seniors/{id}")
    fun detail(me: AuthCaregiver, @PathVariable id: Long) = query.detail(me.id, id)

    @GetMapping("/seniors/{id}/pattern")
    fun pattern(me: AuthCaregiver, @PathVariable id: Long, @RequestParam(defaultValue = "14") days: Int) =
        query.pattern(me.id, id, days)

    @GetMapping("/seniors/{id}/conversations")
    fun conversations(me: AuthCaregiver, @PathVariable id: Long, @RequestParam(defaultValue = "5") limit: Int) =
        query.conversations(me.id, id, limit)

    @GetMapping("/seniors/{id}/timeline")
    fun timeline(me: AuthCaregiver, @PathVariable id: Long, @RequestParam(defaultValue = "100") limit: Int) =
        query.timeline(me.id, id, limit)

    /**
     * 조치 기록. RESOLVED 면 alertId 가 있으면 그 알림만, 없으면 진행 중 알림 모두 끝냅니다.
     * 다른 조치라도 alertId 를 보내면 그 알림은 '확인'된 것으로 보고 단계 진행을 멈춥니다.
     */
    @PostMapping("/seniors/{id}/actions")
    fun action(me: AuthCaregiver, @PathVariable id: Long, @Valid @RequestBody req: ActionRequest): ActionResponse {
        val senior = query.mine(me.id, id)
        val action = req.action!!
        if (action !in ActionType.CAREGIVER_ACTIONS) {
            throw BadRequestException("INVALID_ACTION", "복지사가 남길 수 있는 조치가 아니에요: $action")
        }
        if (req.alertId != null && action != ActionType.RESOLVED) alerts.acknowledge(req.alertId, me.id)
        if (action == ActionType.RESOLVED) alerts.resolve(senior.id!!, req.alertId)

        val event = timeline.record(
            seniorId = senior.id!!,
            actorType = ActorType.CAREGIVER,
            actorName = me.caregiver.name,
            action = action,
            memo = req.memo?.trim()?.takeIf { it.isNotEmpty() },
        )
        if (action == ActionType.RESOLVED) riskLater.reassess(senior.id)
        return ActionResponse(event.id!!, TodakClock.toOffset(event.createdAt))
    }

    /** 알림 확인 — 단계 진행 멈춤 */
    @PatchMapping("/alerts/{alertId}/ack")
    fun ack(me: AuthCaregiver, @PathVariable alertId: Long): AckResponse {
        val a = alerts.acknowledge(alertId, me.id)
        return AckResponse(a.id!!, a.alertStage, a.alertStatus, TodakClock.toOffset(a.acknowledgedAt))
    }
}
