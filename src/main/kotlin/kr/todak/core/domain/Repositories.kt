package kr.todak.core.domain

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.LocalDateTime

// SQL 은 팀 schema.sql 의 테이블·컬럼 이름 그대로 (따옴표 없이 쓰면 H2 가 대문자로 맞춰 줌)
// TEXT(CLOB) 컬럼은 = 비교가 안 되므로 CAST 해서 비교합니다

interface CaregiverRepository : CrudRepository<Caregiver, Long> {
    fun findByLoginId(loginId: String): Caregiver?
}

interface SeniorRepository : CrudRepository<Senior, Long> {
    fun findByRegistrationCode(registrationCode: String): Senior?
    fun findByDeviceToken(deviceToken: String): Senior?
    fun findByCaregiverId(caregiverId: Long): List<Senior>
}

interface CheckRepository : CrudRepository<CheckRecord, Long> {

    @Query("SELECT * FROM daily_checks WHERE senior_id = :seniorId AND check_date = :date AND session = :session")
    fun findOne(
        @Param("seniorId") seniorId: Long,
        @Param("date") date: LocalDate,
        @Param("session") session: String,
    ): CheckRecord?

    @Query("SELECT * FROM daily_checks WHERE senior_id = :seniorId AND check_date BETWEEN :from AND :to ORDER BY check_date, session DESC")
    fun findRange(
        @Param("seniorId") seniorId: Long,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
    ): List<CheckRecord>
}

/** conversations 에는 senior_id 가 없어 daily_checks 를 거쳐 찾습니다 */
interface ConversationRepository : CrudRepository<Conversation, Long> {

    @Query(
        """SELECT c.* FROM conversations c JOIN daily_checks d ON d.id = c.check_id
           WHERE d.senior_id = :seniorId ORDER BY c.started_at DESC, c.id DESC LIMIT :limit""",
    )
    fun findRecent(@Param("seniorId") seniorId: Long, @Param("limit") limit: Int): List<Conversation>

    @Query(
        """SELECT c.* FROM conversations c JOIN daily_checks d ON d.id = c.check_id
           WHERE d.senior_id = :seniorId AND c.summary IS NOT NULL AND LENGTH(c.summary) > 0
           ORDER BY c.started_at DESC, c.id DESC LIMIT :limit""",
    )
    fun findRecentSummarized(@Param("seniorId") seniorId: Long, @Param("limit") limit: Int): List<Conversation>

    @Query(
        """SELECT c.* FROM conversations c JOIN daily_checks d ON d.id = c.check_id
           WHERE d.senior_id = :seniorId AND c.started_at >= :from AND c.started_at < :to""",
    )
    fun findStartedBetween(
        @Param("seniorId") seniorId: Long,
        @Param("from") from: LocalDateTime,
        @Param("to") to: LocalDateTime,
    ): List<Conversation>
}

interface RiskAssessmentRepository : CrudRepository<RiskAssessment, Long> {
    @Query("SELECT * FROM risk_assessments WHERE senior_id = :seniorId AND assessed_date = :date")
    fun findOne(@Param("seniorId") seniorId: Long, @Param("date") date: LocalDate): RiskAssessment?

    @Query("SELECT * FROM risk_assessments WHERE senior_id = :seniorId ORDER BY assessed_date DESC LIMIT 1")
    fun findLatest(@Param("seniorId") seniorId: Long): RiskAssessment?
}

interface AlertRepository : CrudRepository<Alert, Long> {

    @Query("SELECT * FROM alerts WHERE senior_id = :seniorId AND status = 'IN_PROGRESS' ORDER BY sent_at DESC, id DESC")
    fun findOpenBySenior(@Param("seniorId") seniorId: Long): List<Alert>

    @Query("SELECT * FROM alerts WHERE senior_id = :seniorId AND reason = :reason AND status = 'IN_PROGRESS' ORDER BY id DESC LIMIT 1")
    fun findOpen(@Param("seniorId") seniorId: Long, @Param("reason") reason: String): Alert?

    @Query(
        """SELECT * FROM alerts WHERE senior_id = :seniorId AND reason = :reason
           AND status = 'RESOLVED' AND resolved_at >= :since ORDER BY resolved_at DESC LIMIT 1""",
    )
    fun findResolvedSince(
        @Param("seniorId") seniorId: Long,
        @Param("reason") reason: String,
        @Param("since") since: LocalDateTime,
    ): Alert?

    @Query("SELECT * FROM alerts WHERE status = 'IN_PROGRESS' AND next_action_at IS NOT NULL AND next_action_at <= :now ORDER BY id")
    fun findDueForEscalation(@Param("now") now: LocalDateTime): List<Alert>
}

interface TimelineRepository : CrudRepository<TimelineEvent, Long> {
    @Query("SELECT * FROM timeline_events WHERE senior_id = :seniorId ORDER BY created_at DESC, id DESC LIMIT :limit")
    fun findLatest(@Param("seniorId") seniorId: Long, @Param("limit") limit: Int): List<TimelineEvent>

    @Query("SELECT COUNT(*) FROM timeline_events WHERE senior_id = :seniorId AND action = :action AND CAST(memo AS VARCHAR(1000)) = :memo")
    fun countByActionAndMemo(
        @Param("seniorId") seniorId: Long,
        @Param("action") action: String,
        @Param("memo") memo: String,
    ): Long
}
