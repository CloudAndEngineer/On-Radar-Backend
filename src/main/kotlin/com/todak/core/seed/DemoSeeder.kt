package com.todak.core.seed

import com.fasterxml.jackson.databind.ObjectMapper
import com.todak.core.risk.DemoClock
import com.todak.core.risk.JdbcRiskEngine
import com.todak.core.risk.RiskRules
import com.todak.core.risk.Session
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.SimpleJdbcInsert
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.sql.Date
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 서버가 켜질 때 시연 데이터를 넣습니다 (H2 메모리 DB라 켤 때마다 실행).
 * 이미 복지사가 있으면 건너뜁니다.
 *
 * 설정 (application.yml 또는 환경변수):
 *  - todak.seed.enabled  (기본 true)   시드 전체 끄기
 *  - todak.seed.alerts   (기본 true)   위험 2명의 진행 중 알림 · 타임라인도 넣기
 *  - DEMO_TODAY                       시드 마지막 날 (= 오늘)
 */
@Component
class DemoSeeder(
    private val jdbc: JdbcTemplate,
    private val clock: DemoClock,
    private val riskEngine: JdbcRiskEngine,
    private val mapper: ObjectMapper,
    @Value("\${todak.seed.enabled:true}") private val enabled: Boolean,
    @Value("\${todak.seed.alerts:true}") private val seedAlerts: Boolean,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val DEMO_LOGIN_ID = "demo"
        const val DEMO_PASSWORD = "demo1234"
    }

    @Transactional
    override fun run(vararg args: String) {
        if (!enabled) return
        val existing = jdbc.queryForObject("SELECT COUNT(*) FROM caregivers", Int::class.javaObjectType)!!
        if (existing > 0) {
            log.info("[seed] 데이터가 이미 있어 건너뜀")
            return
        }

        val today = clock.today()
        val start = today.minusDays(SeedPlan.DAYS - 1)
        val plan = SeedPlan.build(today)

        val caregiverId = insert(
            "caregivers", mapOf(
                "name" to "이은정",
                "center_name" to "행복노인복지관",
                "phone" to "02-000-0000",
                "login_id" to DEMO_LOGIN_ID,
                "password" to BCryptPasswordEncoder().encode(DEMO_PASSWORD),
            )
        )

        val seniorIds = mutableMapOf<String, Long>()
        for (s in plan) {
            val seniorId = insert(
                "seniors", mapOf(
                    "caregiver_id" to caregiverId,
                    "name" to s.name,
                    "honorific" to s.honorific,
                    "birth_year" to s.birthYear,
                    "address_dong" to s.addressDong,
                    "registration_code" to s.registrationCode,
                    "consent_at" to start.atTime(10, 0),
                    "consent_scope" to "체크 결과 · 대화 요약 · 위험 판정을 담당 복지사에게 공유",
                    "created_at" to start.atTime(9, 0),
                )
            )
            seniorIds[s.name] = seniorId
            if (s.hasFamily) {
                insert("guardians", mapOf("senior_id" to seniorId, "name" to "${s.name.first()}○○", "relation" to "자녀", "notify_order" to 1))
            }

            val checkIds = mutableMapOf<Pair<LocalDate, Session>, Long>()
            for (c in s.checks) {
                checkIds[c.date to c.session] = insert(
                    "daily_checks", mapOf(
                        "senior_id" to seniorId,
                        "check_date" to Date.valueOf(c.date),
                        "session" to c.session.name,
                        "responded_at" to c.respondedAt,
                        "condition_score" to c.conditionScore,
                        "meal_count" to c.mealCount,
                    )
                )
            }

            for (talk in s.conversations) {
                val checkId = checkIds.getValue(talk.date to talk.session)
                val check = s.checks.first { it.date == talk.date && it.session == talk.session }
                insert(
                    "conversations", mapOf(
                        "check_id" to checkId,
                        "started_at" to check.respondedAt.plusMinutes(1),
                        "transcript" to mapper.writeValueAsString(
                            listOf(mapOf("role" to "ai", "text" to talk.aiLine), mapOf("role" to "senior", "text" to talk.seniorLine))
                        ),
                        "summary" to talk.summary,
                        "loneliness_signal" to talk.loneliness,
                        "loneliness_evidence" to talk.lonelinessEvidence,
                        "crisis_flag" to false,
                    )
                )
            }
        }

        if (seedAlerts) seedAlertsAndTimeline(seniorIds, today)

        // 기준선이 끝난 다음 날부터 오늘까지 매일 판정을 저장 (판정 이력 + 오늘 결과)
        val firstScoredDay = start.plusDays(RiskRules.BASELINE_DAYS)
        for ((name, seniorId) in seniorIds) {
            var d = firstScoredDay
            while (!d.isAfter(today)) {
                val outcome = riskEngine.assessAndSave(seniorId, d)
                if (d == today) {
                    val fixed = plan.first { it.name == name }.aiSummary
                    // 시드용 고정 요약. 단, 시연 시각 때문에 결과가 달라졌으면 규칙 문장으로
                    val summary = if (outcome.result.level == expectedLevel(name)) fixed else riskEngine.fallbackSummary(outcome.result)
                    riskEngine.saveAiSummary(seniorId, d, summary)
                }
                d = d.plusDays(1)
            }
        }

        log.info("[seed] 복지사 1명(로그인 {} / {}), 어르신 {}명, {}~{} 데이터 완료", DEMO_LOGIN_ID, DEMO_PASSWORD, plan.size, start, today)
    }

    private fun expectedLevel(name: String) =
        if (name == "김순자" || name == "박영철") com.todak.core.risk.RiskLevel.DANGER else com.todak.core.risk.RiskLevel.NORMAL

    /** 위험 2명: 진행 중 알림 1건씩 + 타임라인 (웹을 켜자마자 알림 화면이 보이도록) */
    private fun seedAlertsAndTimeline(ids: Map<String, Long>, today: LocalDate) {
        val now = clock.now()
        val yesterday = today.minusDays(1)

        // 박영철: 어제·오늘 아침 미응답 → 재알림 → 위험
        val park = ids.getValue("박영철")
        timeline(park, "CHECK_MISSED", "어제 아침 체크 미응답", yesterday.atTime(11, 0))
        timeline(park, "REMINDER_SENT", "어르신께 재알림 푸시", yesterday.atTime(11, 0, 5))
        if (!now.isBefore(today.atTime(RiskRules.MORNING_DEADLINE))) {
            timeline(park, "CHECK_MISSED", "오늘 아침 체크 미응답", today.atTime(11, 0))
            alert(park, "DANGER", "MORNING_MISSED_2DAYS", today.atTime(11, 0, 10))
            timeline(park, "ALERT_ESCALATED", "아침 2일 연속 미응답 · 담당 복지사에게 알림", today.atTime(11, 0, 10))
        }

        // 김순자: 저녁 놓침 기록 + 오늘 아침 체크 직후 위험 알림
        val kim = ids.getValue("김순자")
        timeline(kim, "CHECK_MISSED", "어제 저녁 체크 미응답", yesterday.atTime(21, 0))
        val kimAlertAt = today.atTime(10, 47)
        if (!now.isBefore(kimAlertAt)) {
            alert(kim, "DANGER", "BAD_CONDITION_3DAYS", kimAlertAt)
            timeline(kim, "ALERT_ESCALATED", "'안 좋아요' 3일 연속 · 담당 복지사에게 알림", kimAlertAt)
        }
    }

    private fun alert(seniorId: Long, level: String, reason: String, sentAt: LocalDateTime) {
        // 시드 알림은 시연 중 저절로 다음 단계로 넘어가지 않도록 다음 단계 시각을 2시간 뒤로
        insert(
            "alerts", mapOf(
                "senior_id" to seniorId, "level" to level, "reason" to reason, "stage" to "CAREGIVER",
                "sent_at" to sentAt, "next_action_at" to clock.now().plusHours(2), "status" to "IN_PROGRESS",
            )
        )
    }

    private fun timeline(seniorId: Long, action: String, memo: String, at: LocalDateTime) {
        insert(
            "timeline_events", mapOf(
                "senior_id" to seniorId, "actor_type" to "SYSTEM", "actor_name" to "토닥",
                "action" to action, "memo" to memo, "created_at" to at,
            )
        )
    }

    private fun insert(table: String, values: Map<String, Any?>): Long =
        SimpleJdbcInsert(jdbc).withTableName(table).usingGeneratedKeyColumns("id")
            .usingColumns(*values.keys.toTypedArray())
            .executeAndReturnKey(values).toLong()
}
