package kr.todak.core.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.LocalDate
import java.time.LocalDateTime

/*
 * 팀 schema.sql (ERD 11/21) 에 맞춘 매핑.
 * Spring Data JDBC 는 이름을 따옴표로 감싸 대소문자를 구분하므로, H2 가 저장하는 대문자로 적습니다.
 * 엔티티에 없는 컬럼(seniors.consent_at, baseline_json 등)은 읽지도 덮어쓰지도 않습니다.
 */

@Table("CAREGIVERS")
data class Caregiver(
    @Id val id: Long? = null,
    val name: String,
    val centerName: String,
    val phone: String? = null,
    val loginId: String? = null,
    val passwordHash: String? = null, // SHA-256 hex (시드가 평문을 넣어도 로그인은 됨)
)

@Table("SENIORS")
data class Senior(
    @Id val id: Long? = null,
    val caregiverId: Long,
    val name: String,
    val birthYear: Int,
    val addressDong: String? = null,
    val createdAt: LocalDateTime,        // 이 날짜부터 7일이 기준선 기간
    val honorific: String? = null,
    val registrationCode: String? = null,
    val deviceToken: String? = null,
    val fcmToken: String? = null,
) {
    fun ageOn(date: LocalDate): Int = date.year - birthYear
    fun baselineStart(): LocalDate = createdAt.toLocalDate()
    fun honorificOrDefault(): String = honorific ?: "어르신"
}

@Table("DAILY_CHECKS")
data class CheckRecord(
    @Id val id: Long? = null,
    val seniorId: Long,
    val checkDate: LocalDate,
    @Column("SESSION") val sessionType: Session,
    val respondedAt: LocalDateTime,
    val conditionScore: Int? = null,   // 아침만
    val mealCount: Int? = null,        // 저녁만
)

@Table("CONVERSATIONS")
data class Conversation(
    @Id val id: Long? = null,
    val checkId: Long,                 // 어르신·세션은 체크를 통해 찾습니다
    val startedAt: LocalDateTime,
    val transcript: String? = null,    // JSON [{"role":"ai","text":"..."}]
    val summary: String? = null,       // null 이면 아직 분류 전
    val lonelinessSignal: Boolean = false,
    val crisisFlag: Boolean = false,
    // ↓ schema-core.sql
    val endedAt: LocalDateTime? = null,
    val lonelinessEvidence: String? = null,
    val crisisEvidence: String? = null,
)

@Table("RISK_ASSESSMENTS")
data class RiskAssessment(
    @Id val id: Long? = null,
    val seniorId: Long,
    @Column("ASSESSED_DATE") val assessDate: LocalDate,
    val score: Int,
    @Column("LEVEL") val riskLevel: RiskLevel,
    // {"reasons":[...],"immediateTriggers":[...],"baselineReady":true,"lonelinessDays":1}
    val reasonsJson: String? = null,
    val aiSummary: String? = null,
)

@Table("ALERTS")
data class Alert(
    @Id val id: Long? = null,
    val seniorId: Long,
    @Column("LEVEL") val alertLevel: AlertLevel,
    val reason: String,
    @Column("STAGE") val alertStage: AlertStage,
    @Column("SENT_AT") val createdAt: LocalDateTime,
    val nextActionAt: LocalDateTime? = null,
    val acknowledgedAt: LocalDateTime? = null,
    @Column("STATUS") val alertStatus: AlertStatus,
    val resolvedAt: LocalDateTime? = null, // schema-core.sql
) {
    fun reasonText(): String = AlertReason.text(reason)
}

@Table("TIMELINE_EVENTS")
data class TimelineEvent(
    @Id val id: Long? = null,
    val seniorId: Long,
    val actorType: ActorType,
    val actorName: String? = null,
    @Column("ACTION") val actionType: ActionType,
    val memo: String? = null,
    val createdAt: LocalDateTime,
)
