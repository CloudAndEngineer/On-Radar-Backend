package kr.todak.core.check

import com.fasterxml.jackson.annotation.JsonInclude
import kr.todak.core.alert.AlertService
import kr.todak.core.auth.AuthSenior
import kr.todak.core.common.BadRequestException
import kr.todak.core.common.ConflictException
import kr.todak.core.common.NotFoundException
import kr.todak.core.common.TodakClock
import kr.todak.core.config.TodakProperties
import kr.todak.core.domain.ActionType
import kr.todak.core.domain.AlertReason
import kr.todak.core.domain.AlertRepository
import kr.todak.core.domain.CheckRecord
import kr.todak.core.domain.CheckRepository
import kr.todak.core.domain.CheckStatus
import kr.todak.core.domain.RiskLevel
import kr.todak.core.domain.Session
import kr.todak.core.risk.RiskService
import kr.todak.core.sse.SseEvents
import kr.todak.core.sse.SseHub
import kr.todak.core.timeline.TimelineService
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

// ---- DTO ----

data class CheckRequest(
    val session: Session? = null,
    val conditionScore: Int? = null,
    val mealCount: Int? = null,
)

data class CheckResponse(val checkId: Long, val respondedAt: OffsetDateTime?, val riskLevel: RiskLevel)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SessionStatus(
    val status: CheckStatus,
    val checkId: Long? = null,
    val respondedAt: OffsetDateTime? = null,
    val opensAt: OffsetDateTime? = null,
)

data class TodayResponse(
    val date: LocalDate,
    val morning: SessionStatus,
    val evening: SessionStatus,
    val callContact: Any? = null, // 가족 기능(TODO) 전까지 null → 앱은 [딸에게 전화하기] 숨김
)

// ---- 오늘 상태 계산 (앱 홈, 웹 목록 공용) ----

@Service
class TodayStatusService(private val checks: CheckRepository, private val clock: TodakClock) {

    fun today(seniorId: Long): TodayResponse {
        val date = clock.today()
        val now = clock.now()
        return TodayResponse(
            date = date,
            morning = status(seniorId, date, Session.MORNING, now),
            evening = status(seniorId, date, Session.EVENING, now),
        )
    }

    fun status(seniorId: Long, date: LocalDate, session: Session, now: LocalDateTime = clock.now()): SessionStatus {
        val check = checks.findOne(seniorId, date, session.name)
        return when {
            check != null -> SessionStatus(CheckStatus.DONE, check.id, TodakClock.toOffset(check.respondedAt))
            clock.isDue(date, session, now) -> SessionStatus(CheckStatus.MISSED)
            else -> SessionStatus(CheckStatus.PENDING, opensAt = TodakClock.toOffset(clock.opensAt(date, session)))
        }
    }
}

// ---- 체크 제출·수정 ----

@Service
class CheckService(
    private val checks: CheckRepository,
    private val clock: TodakClock,
    private val props: TodakProperties,
    private val risk: RiskService,
    private val sse: SseHub,
    private val alerts: AlertService,
    private val alertRepo: AlertRepository,
    private val timeline: TimelineService,
) {

    fun submit(seniorId: Long, req: CheckRequest): CheckResponse {
        val session = validate(req)
        val date = clock.today()
        if (checks.findOne(seniorId, date, session.name) != null) throw alreadySubmitted(session)
        val opensAt = clock.opensAt(date, session)
        if (props.checkTimeEnforced && clock.now().isBefore(opensAt)) {
            val label = if (session == Session.MORNING) "아침" else "저녁"
            throw ConflictException("CHECK_NOT_OPEN", "아직 $label 체크 시간이 아니에요 (${TodakClock.hhmm(opensAt.toLocalTime())}부터)")
        }
        val saved = try {
            checks.save(
                CheckRecord(
                    seniorId = seniorId,
                    checkDate = date,
                    sessionType = session,
                    conditionScore = if (session == Session.MORNING) req.conditionScore else null,
                    mealCount = if (session == Session.EVENING) req.mealCount else null,
                    respondedAt = clock.now(),
                ),
            )
        } catch (e: Exception) {
            if (isDuplicate(e)) throw alreadySubmitted(session) else throw e
        }

        // 늦게라도 아침 체크가 들어오면 '아침 미응답' 알림은 시스템이 닫습니다
        if (session == Session.MORNING) {
            alertRepo.findOpen(seniorId, AlertReason.MORNING_MISSED)?.let { open ->
                alerts.resolve(seniorId, open.id)
                timeline.system(seniorId, ActionType.RESOLVED, "아침 체크가 들어와 알림을 닫았어요")
            }
        }
        return afterChange(seniorId, saved)
    }

    fun update(seniorId: Long, checkId: Long, req: CheckRequest): CheckResponse {
        val existing = checks.findById(checkId).orElse(null)
        if (existing == null || existing.seniorId != seniorId) throw NotFoundException("CHECK_NOT_FOUND", "체크 기록을 찾을 수 없어요")
        val session = req.session ?: existing.sessionType
        if (session != existing.sessionType) throw BadRequestException("SESSION_MISMATCH", "아침 체크와 저녁 체크는 서로 바꿀 수 없어요")
        validate(req.copy(session = session))
        // respondedAt(처음 누른 시각)은 기상 신호라 그대로 둡니다
        val saved = checks.save(
            existing.copy(
                conditionScore = if (session == Session.MORNING) req.conditionScore else null,
                mealCount = if (session == Session.EVENING) req.mealCount else null,
            ),
        )
        return afterChange(seniorId, saved)
    }

    private fun afterChange(seniorId: Long, check: CheckRecord): CheckResponse {
        sse.publishForSenior(seniorId, SseEvents.CHECK_SUBMITTED, mapOf("seniorId" to seniorId, "session" to check.sessionType))
        val assessment = risk.reassess(seniorId)
        return CheckResponse(check.id!!, TodakClock.toOffset(check.respondedAt), assessment.riskLevel)
    }

    private fun validate(req: CheckRequest): Session {
        val session = req.session ?: throw BadRequestException("INVALID_CHECK", "session 은 MORNING 또는 EVENING 이에요")
        when (session) {
            Session.MORNING -> if (req.conditionScore == null || req.conditionScore !in 0..2) {
                throw BadRequestException("INVALID_CHECK", "아침은 conditionScore(0~2)를 보내 주세요")
            }
            Session.EVENING -> if (req.mealCount == null || req.mealCount !in 0..3) {
                throw BadRequestException("INVALID_CHECK", "저녁은 mealCount(0~3)를 보내 주세요")
            }
        }
        return session
    }

    private fun alreadySubmitted(session: Session) = ConflictException(
        "CHECK_ALREADY_SUBMITTED",
        if (session == Session.MORNING) "오늘 아침 체크는 이미 했어요" else "오늘 저녁 체크는 이미 했어요",
    )

    private fun isDuplicate(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is DuplicateKeyException || it.javaClass.simpleName.contains("IntegrityConstraint") }
}

@RestController
@RequestMapping("/api")
class CheckController(private val service: CheckService, private val today: TodayStatusService) {

    @GetMapping("/seniors/me/today")
    fun today(me: AuthSenior): TodayResponse = today.today(me.id)

    @PostMapping("/checks")
    fun submit(me: AuthSenior, @RequestBody req: CheckRequest): CheckResponse = service.submit(me.id, req)

    @PutMapping("/checks/{checkId}")
    fun update(me: AuthSenior, @PathVariable checkId: Long, @RequestBody req: CheckRequest): CheckResponse =
        service.update(me.id, checkId, req)
}
