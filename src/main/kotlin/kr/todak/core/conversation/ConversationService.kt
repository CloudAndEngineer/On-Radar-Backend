package kr.todak.core.conversation

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import kr.todak.core.ai.AiGateway
import kr.todak.core.ai.AiSenior
import kr.todak.core.ai.AiTurn
import kr.todak.core.ai.ChatRequest
import kr.todak.core.ai.ClassifyRequest
import kr.todak.core.ai.CrisisWords
import kr.todak.core.alert.AlertService
import kr.todak.core.auth.AuthSenior
import kr.todak.core.common.ConflictException
import kr.todak.core.common.NotFoundException
import kr.todak.core.common.TodakClock
import kr.todak.core.domain.AlertLevel
import kr.todak.core.domain.AlertReason
import kr.todak.core.domain.AlertStage
import kr.todak.core.domain.CheckRecord
import kr.todak.core.domain.CheckRepository
import kr.todak.core.domain.Conversation
import kr.todak.core.domain.ConversationRepository
import kr.todak.core.domain.Senior
import kr.todak.core.domain.Session
import kr.todak.core.risk.RiskService
import kr.todak.core.senior.RiskLater
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// ---- DTO ----

data class StartRequest(@field:NotNull val checkId: Long?)
data class StartResponse(val conversationId: Long, val aiMessage: String, val turn: Int, val ended: Boolean)
data class MessageRequest(@field:NotBlank val text: String?)
data class MessageResponse(val aiMessage: String, val turn: Int, val ended: Boolean)
data class EndResponse(val conversationId: Long, val saved: Boolean)

const val MAX_TURNS = 3

/** 아침은 {conditionScore}, 저녁은 {mealCount} 만 */
fun todayCheckOf(check: CheckRecord?): Map<String, Int> {
    if (check == null) return emptyMap()
    val pair = if (check.sessionType == Session.MORNING) {
        check.conditionScore?.let { "conditionScore" to it }
    } else {
        check.mealCount?.let { "mealCount" to it }
    }
    return if (pair == null) emptyMap() else mapOf(pair)
}

/** conversations.transcript(JSON) 읽고 쓰기 */
@Component
class Transcripts(private val mapper: ObjectMapper) {
    fun read(c: Conversation): List<AiTurn> =
        c.transcript?.takeIf { it.isNotBlank() }
            ?.let { runCatching { mapper.readValue(it, object : TypeReference<List<AiTurn>>() {}) }.getOrNull() }
            ?: emptyList()

    fun write(turns: List<AiTurn>): String = mapper.writeValueAsString(turns)

    /** AI 말 수 = 턴 */
    fun turnOf(turns: List<AiTurn>): Int = turns.count { it.role == "ai" }
}

@Service
class ConversationService(
    private val conversations: ConversationRepository,
    private val checks: CheckRepository,
    private val transcripts: Transcripts,
    private val ai: AiGateway,
    private val alerts: AlertService,
    private val finisher: ConversationFinisher,
    private val riskLater: RiskLater,
    private val clock: TodakClock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 대화 시작 — AI가 오늘 체크 내용으로 먼저 말을 건넵니다 */
    fun start(senior: Senior, checkId: Long): StartResponse {
        val check = checks.findById(checkId).orElse(null)
        if (check == null || check.seniorId != senior.id) throw NotFoundException("CHECK_NOT_FOUND", "체크 기록을 찾을 수 없어요")

        val chat = ai.chat(chatRequest(senior, check, emptyList(), turn = 1))
        val ended = chat.endConversation || 1 >= MAX_TURNS
        var conv = conversations.save(
            Conversation(
                checkId = checkId,
                startedAt = clock.now(),
                transcript = transcripts.write(listOf(AiTurn("ai", chat.reply))),
                endedAt = if (ended) clock.now() else null,
            ),
        )
        if (chat.crisisFlag) conv = raiseCrisis(conv, senior.id!!, null)
        if (ended) finisher.finishAsync(conv.id!!)
        return StartResponse(conv.id!!, chat.reply, 1, ended)
    }

    /** 어르신 말 보내고 AI 답 받기. 3턴째 AI 말은 마무리 인사 + ended: true */
    fun message(senior: Senior, conversationId: Long, text: String): MessageResponse {
        val (found, check) = find(senior, conversationId)
        var conv = found
        if (conv.endedAt != null || conv.summary != null) throw ConflictException("CONVERSATION_ENDED", "이미 끝난 대화예요")

        val history = transcripts.read(conv) + AiTurn("senior", text.trim().take(1000))
        val nextTurn = transcripts.turnOf(history) + 1
        val chat = ai.chat(chatRequest(senior, check, history, nextTurn))

        val ended = chat.endConversation || nextTurn >= MAX_TURNS
        conv = conversations.save(
            conv.copy(
                transcript = transcripts.write(history + AiTurn("ai", chat.reply)),
                endedAt = if (ended) clock.now() else null,
            ),
        )

        // 위기 표현: AI 판단 + 단어 규칙 이중 확인 → 대화가 끝나기 전에 바로 EMERGENCY
        val keyword = CrisisWords.find(text)
        if (chat.crisisFlag || keyword != null) {
            log.warn("대화 중 위기 표현 senior={} conv={} ai={} keyword={}", senior.id, conv.id, chat.crisisFlag, keyword)
            conv = raiseCrisis(conv, senior.id!!, text.trim())
        }
        if (ended) finisher.finishAsync(conv.id!!)
        return MessageResponse(chat.reply, nextTurn, ended)
    }

    /** [다음에 할게요] — 분류·요약까지 마치고 돌려줍니다 (AI 최대 13초) */
    fun end(senior: Senior, conversationId: Long): EndResponse {
        var conv = find(senior, conversationId).first
        if (conv.endedAt == null) conv = conversations.save(conv.copy(endedAt = clock.now()))
        finisher.finish(conv.id!!)
        return EndResponse(conv.id!!, true)
    }

    private fun raiseCrisis(conv: Conversation, seniorId: Long, evidence: String?): Conversation {
        val saved = if (conv.crisisFlag) conv else conversations.save(conv.copy(crisisFlag = true, crisisEvidence = evidence))
        alerts.open(seniorId, AlertLevel.EMERGENCY, AlertReason.CRISIS_TALK, AlertStage.CAREGIVER, evidence?.let { "\"$it\"" })
        riskLater.reassess(seniorId)
        return saved
    }

    private fun chatRequest(senior: Senior, check: CheckRecord, history: List<AiTurn>, turn: Int) =
        ChatRequest(
            senior = AiSenior(senior.name, senior.honorificOrDefault()),
            session = check.sessionType.name,
            todayCheck = todayCheckOf(check),
            memory = conversations.findRecentSummarized(senior.id!!, 3).mapNotNull { it.summary },
            history = history,
            turn = turn,
            maxTurns = MAX_TURNS,
        )

    /** 대화와 그 체크. 체크가 이 어르신 것이어야 함 */
    private fun find(senior: Senior, id: Long): Pair<Conversation, CheckRecord> {
        val conv = conversations.findById(id).orElse(null)
        val check = conv?.let { checks.findById(it.checkId).orElse(null) }
        if (conv == null || check == null || check.seniorId != senior.id) {
            throw NotFoundException("CONVERSATION_NOT_FOUND", "대화를 찾을 수 없어요")
        }
        return conv to check
    }
}

/** 대화가 끝나면 AI classify 1번 → 요약·외로움·위기 저장 → 위험 점수 다시 계산 */
@Component
class ConversationFinisher(
    private val conversations: ConversationRepository,
    private val checks: CheckRepository,
    private val transcripts: Transcripts,
    private val ai: AiGateway,
    private val alerts: AlertService,
    private val risk: RiskService,
    private val clock: TodakClock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 분류 중인 대화. classified 컬럼이 없어 메모리로 한 번만 돌게 막습니다 (끝나면 summary 가 채워짐) */
    private val running: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    @Async
    fun finishAsync(conversationId: Long) {
        try {
            finish(conversationId)
        } catch (e: Exception) {
            log.error("대화 분류 실패 conv={}", conversationId, e)
        }
    }

    fun finish(conversationId: Long) {
        if (!running.add(conversationId)) return // 하는 중
        try {
            val conv = conversations.findById(conversationId).orElse(null) ?: return
            if (conv.summary != null) return // 이미 분류됨
            val check = checks.findById(conv.checkId).orElse(null) ?: return
            val transcript = transcripts.read(conv)

            val result = ai.classify(ClassifyRequest(transcript, todayCheckOf(check)))
            // AI가 놓쳐도(또는 실패해도) 위기 단어 목록으로 한 번 더
            val keywordLine = transcript.filter { it.role == "senior" }.firstOrNull { CrisisWords.contains(it.text) }?.text
            val crisis = result.crisisFlag || keywordLine != null

            conversations.save(
                conv.copy(
                    summary = result.summary?.trim().orEmpty(), // 실패해도 "" 로 남겨 '분류 끝'을 표시
                    lonelinessSignal = result.lonelinessSignal,
                    lonelinessEvidence = result.lonelinessEvidence?.take(500),
                    crisisFlag = conv.crisisFlag || crisis,
                    crisisEvidence = conv.crisisEvidence ?: (result.crisisEvidence ?: keywordLine)?.take(500),
                    endedAt = conv.endedAt ?: clock.now(),
                ),
            )
            if (crisis && !conv.crisisFlag) {
                val evidence = result.crisisEvidence ?: keywordLine
                alerts.open(check.seniorId, AlertLevel.EMERGENCY, AlertReason.CRISIS_TALK, AlertStage.CAREGIVER, evidence?.let { "\"$it\"" })
            }
            risk.reassess(check.seniorId)
        } finally {
            running.remove(conversationId)
        }
    }
}

@RestController
@RequestMapping("/api/conversations")
class ConversationController(private val service: ConversationService) {

    @PostMapping
    fun start(me: AuthSenior, @Valid @RequestBody req: StartRequest): StartResponse = service.start(me.senior, req.checkId!!)

    @PostMapping("/{id}/messages")
    fun message(me: AuthSenior, @PathVariable id: Long, @Valid @RequestBody req: MessageRequest): MessageResponse =
        service.message(me.senior, id, req.text!!)

    @PostMapping("/{id}/end")
    fun end(me: AuthSenior, @PathVariable id: Long): EndResponse = service.end(me.senior, id)
}
