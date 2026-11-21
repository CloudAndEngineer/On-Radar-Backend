package kr.todak.core.risk

import com.fasterxml.jackson.databind.ObjectMapper
import kr.todak.core.ai.AiGateway
import kr.todak.core.ai.FallbackText
import kr.todak.core.ai.RiskSummaryReason
import kr.todak.core.ai.RiskSummaryRequest
import kr.todak.core.alert.AlertService
import kr.todak.core.common.TodakClock
import kr.todak.core.domain.AlertLevel
import kr.todak.core.domain.AlertReason
import kr.todak.core.domain.AlertStage
import kr.todak.core.domain.RiskAssessment
import kr.todak.core.domain.RiskAssessmentRepository
import kr.todak.core.domain.RiskLevel
import kr.todak.core.domain.SeniorRepository
import kr.todak.core.sse.SseEvents
import kr.todak.core.sse.SseHub
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * 점수 엔진을 부르고, 저장하고, 바뀌면 AI 요약·SSE·알림까지 처리합니다.
 * 부르는 때: 체크 제출 직후, 대화 분류 직후, 매일 21시(저녁 마감), 긴급 호출·조치 완료 뒤.
 */
@Service
class RiskService(
    private val engine: RiskEngine,
    private val repo: RiskAssessmentRepository,
    private val alerts: AlertService,
    private val sse: SseHub,
    private val clock: TodakClock,
    private val mapper: ObjectMapper,
    private val summaryWorker: RiskSummaryWorker,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val locks = ConcurrentHashMap<Long, Any>()

    fun reassess(seniorId: Long, openAlerts: Boolean = true): RiskAssessment {
        val date = clock.today()
        val (saved, changed, result) = synchronized(locks.computeIfAbsent(seniorId) { Any() }) {
            val result = engine.assess(seniorId, date)
            val prev = repo.findOne(seniorId, date)
            val json = mapper.writeValueAsString(
                StoredRisk(result.reasons, result.immediateTriggers, result.baselineReady, result.lonelinessDays),
            )
            val changed = prev == null || prev.score != result.score || prev.riskLevel != result.level ||
                stored(prev).immediateTriggers != result.immediateTriggers
            val fallbackSummary = if (!result.baselineReady && result.immediateTriggers.isEmpty()) {
                "기준선 수집 중이에요 (처음 7일)"
            } else {
                FallbackText.riskSummary(result.reasons.map { it.toAi() }, result.immediateTriggers)
            }
            val row = RiskAssessment(
                id = prev?.id,
                seniorId = seniorId,
                assessDate = date,
                score = result.score,
                riskLevel = result.level,
                reasonsJson = json,
                aiSummary = if (changed || prev?.aiSummary.isNullOrBlank()) fallbackSummary else prev?.aiSummary,
            )
            Triple(repo.save(row), changed, result)
        }

        if (changed) {
            log.info("위험 점수 senior={} {}점 {} {}", seniorId, result.score, result.level, result.immediateTriggers)
            sse.publishForSenior(
                seniorId,
                SseEvents.RISK_UPDATED,
                mapOf("seniorId" to seniorId, "score" to result.score, "riskLevel" to result.level),
            )
            if (result.baselineReady || result.immediateTriggers.isNotEmpty()) {
                summaryWorker.refresh(seniorId, date, result)
            }
        }

        // 위험이면 알림 (같은 사유가 진행 중이거나 해결 뒤 24시간 안이면 AlertService 가 막아 줌)
        if (openAlerts && result.level == RiskLevel.DANGER) {
            val reason = result.immediateTriggers.firstOrNull { it != AlertReason.HELP_BUTTON && it != AlertReason.CRISIS_TALK }
                ?: if (result.immediateTriggers.isEmpty()) AlertReason.RISK_SCORE else null
            // 긴급 호출·위기 표현은 이미 EMERGENCY 알림이 열려 있으므로 따로 열지 않음
            if (reason != null) alerts.open(seniorId, AlertLevel.DANGER, reason, AlertStage.CAREGIVER, "${result.score}점")
        }
        return saved
    }

    fun latest(seniorId: Long): RiskAssessment? = repo.findOne(seniorId, clock.today()) ?: repo.findLatest(seniorId)

    /** risk_assessments.reasons_json = {"reasons":[...],"immediateTriggers":[...],"baselineReady":true,"lonelinessDays":1} */
    fun stored(a: RiskAssessment): StoredRisk =
        a.reasonsJson?.let { runCatching { mapper.readValue(it, StoredRisk::class.java) }.getOrNull() } ?: StoredRisk()
}

data class StoredRisk(
    val reasons: List<Reason> = emptyList(),
    val immediateTriggers: List<String> = emptyList(),
    val baselineReady: Boolean = true,
    val lonelinessDays: Int = 0,
)

fun Reason.toAi() = RiskSummaryReason(item, baseline, recent, contribution)

/** AI 위험 요약은 13초까지 걸릴 수 있어 따로 돌립니다 */
@Component
class RiskSummaryWorker(
    private val ai: AiGateway,
    private val repo: RiskAssessmentRepository,
    private val seniors: SeniorRepository,
    private val sse: SseHub,
) {
    @Async
    fun refresh(seniorId: Long, date: LocalDate, result: RiskResult) {
        val senior = seniors.findById(seniorId).orElse(null) ?: return
        val summary = ai.riskSummary(
            RiskSummaryRequest(
                seniorName = senior.name,
                riskLevel = result.level.name,
                score = result.score,
                reasons = result.reasons.filter { it.baseline != null && it.recent != null }.map { it.toAi() },
                immediateTriggers = result.immediateTriggers,
                lonelinessDays = result.lonelinessDays,
            ),
        )
        val row = repo.findOne(seniorId, date) ?: return
        if (row.score != result.score || row.riskLevel != result.level) return // 그 사이 다시 계산됨
        repo.save(row.copy(aiSummary = summary))
        sse.publishForSenior(
            seniorId,
            SseEvents.RISK_UPDATED,
            mapOf("seniorId" to seniorId, "score" to row.score, "riskLevel" to row.riskLevel),
        )
    }
}
