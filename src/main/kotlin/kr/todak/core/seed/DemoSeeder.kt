package kr.todak.core.seed

import kr.todak.core.ai.AiTurn
import kr.todak.core.auth.Passwords
import kr.todak.core.common.TodakClock
import kr.todak.core.conversation.Transcripts
import kr.todak.core.config.TodakProperties
import kr.todak.core.domain.ActionType
import kr.todak.core.domain.ActorType
import kr.todak.core.domain.Caregiver
import kr.todak.core.domain.CaregiverRepository
import kr.todak.core.domain.CheckRecord
import kr.todak.core.domain.CheckRepository
import kr.todak.core.domain.Conversation
import kr.todak.core.domain.ConversationRepository
import kr.todak.core.domain.Senior
import kr.todak.core.domain.SeniorRepository
import kr.todak.core.domain.Session
import kr.todak.core.domain.TimelineEvent
import kr.todak.core.domain.TimelineRepository
import kr.todak.core.risk.RiskService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.random.Random

/**
 * 개발·시연용 시드: 복지사 1명, 어르신 10명 × 14일 (DEMO_TODAY 가 마지막 날).
 *  - 김순자(KSJ-0001): 서서히 나빠짐 → 위험. 오늘 체크는 비워 둠 (앱 시연용)
 *  - 박영호(PYH-0002): 최근 이틀 아침·저녁 모두 미응답 → 즉시 위험. 오늘 비워 둠
 *  - 최덕수(CDS-0004): 가족 없음 (가족 기능은 TODO)
 *  - 나머지: 정상, 오늘 아침 체크까지 있음
 * DB→앱 담당의 시드 설계가 나오면 이 파일의 PROFILES 만 바꾸면 됩니다.
 */
@Component
class DemoSeeder(
    private val props: TodakProperties,
    private val clock: TodakClock,
    private val caregivers: CaregiverRepository,
    private val seniors: SeniorRepository,
    private val checks: CheckRepository,
    private val conversations: ConversationRepository,
    private val timeline: TimelineRepository,
    private val risk: RiskService,
    private val transcripts: Transcripts,
    private val jdbc: JdbcClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private enum class Kind { STABLE, SLOW_DECLINE, SUDDEN_MISS }

    private data class Profile(
        val name: String,
        val honorific: String,
        val code: String,
        val birthYear: Int,
        val gender: String,
        val dong: String,
        val wake: LocalTime,
        val meals: Int,
        val kind: Kind = Kind.STABLE,
        val goodRate: Double = 0.6,      // 컨디션 '좋아요' 비율 (나머지는 '그저 그래요')
        val baselineMiss: Int = -1,      // 기준선 기간에 저녁 한 번 놓친 날 (없으면 -1)
    )

    private val PROFILES = listOf(
        Profile("김순자", "할머니", "KSJ-0001", 1945, "F", "성수1가2동", LocalTime.of(8, 5), 3, Kind.SLOW_DECLINE),
        Profile("박영호", "할아버지", "PYH-0002", 1942, "M", "성수2가1동", LocalTime.of(8, 15), 3, Kind.SUDDEN_MISS),
        Profile("이말순", "할머니", "LMS-0003", 1948, "F", "성수1가1동", LocalTime.of(8, 5), 3, goodRate = 0.7),
        Profile("최덕수", "할아버지", "CDS-0004", 1950, "M", "성수2가3동", LocalTime.of(8, 30), 2, goodRate = 0.5, baselineMiss = 3),
        Profile("정옥자", "할머니", "JOJ-0005", 1944, "F", "송정동", LocalTime.of(8, 0), 3, goodRate = 0.6),
        Profile("강춘식", "할아버지", "KCS-0006", 1947, "M", "용답동", LocalTime.of(8, 0), 3, goodRate = 0.8),
        Profile("윤금례", "할머니", "YGR-0007", 1940, "F", "성수1가2동", LocalTime.of(8, 20), 2, goodRate = 0.5, baselineMiss = 5),
        Profile("장복남", "할아버지", "JBN-0008", 1946, "M", "송정동", LocalTime.of(8, 10), 3, goodRate = 0.6),
        Profile("한분이", "할머니", "HBI-0009", 1943, "F", "성수2가1동", LocalTime.of(8, 35), 3, goodRate = 0.7),
        Profile("오정희", "할머니", "OJH-0010", 1949, "F", "용답동", LocalTime.of(8, 10), 3, goodRate = 0.6),
    )

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        if (!props.seedEnabled) return
        val cg = caregivers.findByLoginId(props.demoCaregiverLoginId) ?: caregivers.save(
            Caregiver(
                name = "이지은",
                centerName = "성동 토닥 복지관",
                phone = "02-000-0000",
                loginId = props.demoCaregiverLoginId,
                passwordHash = Passwords.hash(props.demoCaregiverPassword),
            ),
        )
        if (seniors.count() > 0L) return
        val n = seedSeniors(cg.id!!)
        log.info("시드 완료: 복지사 {} (로그인 {}), 어르신 {}명, 오늘={}", cg.name, cg.loginId, n, clock.today())
    }

    fun reseed(caregiverId: Long): Int {
        listOf("conversations", "timeline_events", "alerts", "risk_assessments", "daily_checks", "guardians", "seniors")
            .forEach { jdbc.sql("DELETE FROM $it").update() }
        return seedSeniors(caregiverId)
    }

    fun seedSeniors(caregiverId: Long): Int {
        val today = clock.today()
        val start = today.minusDays(13)
        PROFILES.forEachIndexed { idx, p ->
            val senior = seniors.save(
                Senior(
                    caregiverId = caregiverId,
                    name = p.name,
                    birthYear = p.birthYear,
                    addressDong = p.dong,
                    createdAt = start.atTime(9, 0), // 이 날부터 7일이 기준선
                    honorific = p.honorific,
                    registrationCode = p.code,
                ),
            )
            seedChecks(senior, p, start, today, Random(1000 + idx))
        }
        seedConversations(today)
        // 처음 점수 계산 (기동 직후 알림이 쏟아지지 않도록 알림은 열지 않음)
        seniors.findAll().forEach { risk.reassess(it.id!!, openAlerts = false) }
        return PROFILES.size
    }

    private fun seedChecks(s: Senior, p: Profile, start: LocalDate, today: LocalDate, rnd: Random) {
        for (d in 0..13) {
            val date = start.plusDays(d.toLong())
            val isToday = date == today
            when (p.kind) {
                Kind.STABLE -> {
                    val wake = p.wake.plusMinutes(rnd.nextLong(0, 26)) // 8시 알림 뒤 바로~25분
                    val cond = if (rnd.nextDouble() < p.goodRate) 2 else 1
                    morning(s, date, wake, cond)
                    if (isToday) continue
                    if (d == p.baselineMiss) {
                        missed(s, date, Session.EVENING)
                    } else {
                        val meals = if (rnd.nextDouble() < 0.1) p.meals - 1 else p.meals
                        evening(s, date, LocalTime.of(18, 30).plusMinutes(rnd.nextLong(0, 60)), meals)
                    }
                }
                Kind.SLOW_DECLINE -> {
                    if (isToday) continue
                    // 평소 8시 5분·3끼·컨디션 1.6 → 최근 10시 40분대·1끼·'안 좋아요' 3일
                    val plan = listOf(
                        Triple(0, 2, 3), Triple(5, 2, 3), Triple(-3, 1, 3), Triple(3, 2, 3), Triple(-2, 1, 3), Triple(0, 2, 3), Triple(2, 1, 3),
                        Triple(20, 1, 3), Triple(45, 1, 2), Triple(70, 1, 2), Triple(110, 0, 2), Triple(150, 0, -1), Triple(165, 0, 1),
                    )[d]
                    morning(s, date, p.wake.plusMinutes(plan.first.toLong()), plan.second)
                    if (plan.third < 0) missed(s, date, Session.EVENING)
                    else evening(s, date, LocalTime.of(19, 10).plusMinutes(d.toLong()), plan.third)
                }
                Kind.SUDDEN_MISS -> {
                    if (isToday) continue
                    if (d >= 11) {
                        missed(s, date, Session.MORNING)
                        missed(s, date, Session.EVENING)
                    } else {
                        morning(s, date, p.wake.plusMinutes(rnd.nextLong(0, 16)), if (rnd.nextDouble() < 0.6) 2 else 1)
                        evening(s, date, LocalTime.of(18, 20).plusMinutes(rnd.nextLong(0, 40)), 3)
                    }
                }
            }
        }
    }

    private fun morning(s: Senior, date: LocalDate, at: LocalTime, cond: Int) {
        checks.save(CheckRecord(seniorId = s.id!!, checkDate = date, sessionType = Session.MORNING, conditionScore = cond, respondedAt = date.atTime(at)))
    }

    private fun evening(s: Senior, date: LocalDate, at: LocalTime, meals: Int) {
        checks.save(CheckRecord(seniorId = s.id!!, checkDate = date, sessionType = Session.EVENING, mealCount = meals, respondedAt = date.atTime(at)))
    }

    /** 미응답은 행을 만들지 않고, 타임라인에만 남깁니다 (11시/21시 작업과 같은 문구) */
    private fun missed(s: Senior, date: LocalDate, session: Session) {
        val (label, time) = if (session == Session.MORNING) "아침" to clock.morningDeadline else "저녁" to clock.eveningDeadline
        timeline.save(
            TimelineEvent(
                seniorId = s.id!!,
                actorType = ActorType.SYSTEM,
                actorName = "토닥",
                actionType = ActionType.CHECK_MISSED,
                memo = "$date $label 체크 미응답",
                createdAt = date.atTime(time),
            ),
        )
    }

    private fun seedConversations(today: LocalDate) {
        val ksj = seniors.findByRegistrationCode("KSJ-0001") ?: return
        conversation(
            ksj, today.minusDays(3), Session.MORNING, "요즘 경로당에 안 나가고 집에만 있다고 하심", false, null,
            listOf("할머니, 오늘은 그저 그러시군요. 요즘 경로당은 다니세요?", "요즘은 잘 안 가. 다리도 아프고", "다리가 아프시면 나가기 힘드시죠. 오늘은 푹 쉬세요."),
        )
        conversation(
            ksj, today.minusDays(1), Session.EVENING, "혼자 먹으니 입맛이 없다고 하심", true, "혼자 먹으니 귀찮아서 그렇지 뭐",
            listOf("오늘은 밥을 한 번 드셨네요. 입맛이 없으셨어요?", "혼자 먹으니 귀찮아서 그렇지 뭐", "혼자 드시면 그럴 수 있어요. 내일은 누구랑 이야기 나누실 거예요?"),
        )
        val lms = seniors.findByRegistrationCode("LMS-0003") ?: return
        conversation(
            lms, today.minusDays(1), Session.MORNING, "딸이 주말에 온다고 해서 기분이 좋다고 하심", false, null,
            listOf("할머니, 오늘 몸이 괜찮으시다니 다행이에요. 오늘은 뭐 하실 계획이세요?", "딸이 주말에 온대서 장 좀 보려고", "따님이 오시는군요! 장 보실 때 무거운 건 나눠 드세요."),
        )
    }

    private fun conversation(
        s: Senior,
        date: LocalDate,
        session: Session,
        summary: String,
        lonely: Boolean,
        evidence: String?,
        lines: List<String>,
    ) {
        val check = checks.findOne(s.id!!, date, session.name)
        val startedAt: LocalDateTime = (check?.respondedAt ?: date.atTime(if (session == Session.MORNING) 9 else 19, 0)).plusMinutes(1)
        if (check == null) return
        conversations.save(
            Conversation(
                checkId = check.id!!,
                startedAt = startedAt,
                transcript = transcripts.write(lines.mapIndexed { i, text -> AiTurn(if (i % 2 == 0) "ai" else "senior", text) }),
                summary = summary,
                lonelinessSignal = lonely,
                endedAt = startedAt.plusMinutes(3),
                lonelinessEvidence = evidence,
            ),
        )
    }
}
