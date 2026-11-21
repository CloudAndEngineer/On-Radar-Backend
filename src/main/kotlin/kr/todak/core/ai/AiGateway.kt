package kr.todak.core.ai

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 위기 표현 단어 목록 — AI 분류 실패 때, 그리고 AI 판정과 이중 확인용.
 * ai-service/app/safety.py 와 같은 범위로 맞춤 (AI 서비스가 죽어도 같은 문장을 잡도록)
 */
object CrisisWords {
    /** 몸의 응급 → 119 안내 */
    private val body = listOf(
        "숨이 안 쉬어", "숨을 못 쉬", "숨이 막", "숨쉬기가 힘들",
        "쓰러졌", "넘어져서 못 일어", "넘어졌는데 못 일어", "넘어져서 안 일어",
        "가슴이 너무 아파", "가슴이 너무 답답", "가슴이 조여",
    )

    /** 마음의 위기 */
    private val mind = listOf(
        "죽고 싶", "죽어야", "죽을래", "죽어 버리", "죽어버리",
        "살기 싫", "그만 살", "더 살아서", "살아서 뭐", "살 이유", "사는 게 의미", "살 만큼 살았",
        "자살", "목숨을 끊", "세상 떠나고", "세상 뜨고", "먼저 가고 싶", "따라가고 싶", "따라가야",
        "안 일어났으면", "눈 안 떴으면", "자는 듯이 가", "데려갔으면",
        "없어지는 게 나", "없어졌으면", "사라지고 싶", "끝내고 싶", "뛰어내리",
    )

    const val REPLY_MIND = "말씀해 주셔서 고마워요. 지금 복지사 선생님께 바로 알려서 연락드리도록 할게요."
    const val REPLY_BODY = "많이 불편하시군요. 복지사 선생님께 바로 알릴게요. 급하시면 119에 전화하세요."

    fun contains(text: String?): Boolean = find(text) != null

    fun find(text: String?): String? = match(text, body) ?: match(text, mind)

    /** 위기 문장이면 고정 대사, 아니면 null. 몸의 응급을 먼저 봅니다 */
    fun replyFor(text: String?): String? = when {
        match(text, body) != null -> REPLY_BODY
        match(text, mind) != null -> REPLY_MIND
        else -> null
    }

    private fun match(text: String?, patterns: List<String>): String? {
        if (text.isNullOrBlank()) return null
        val compact = text.replace(" ", "")
        return patterns.firstOrNull { p -> text.contains(p) || compact.contains(p.replace(" ", "")) }
    }
}

object FallbackText {
    const val CHAT = "말씀 고마워요. 오늘도 편안한 하루 보내세요."

    private val itemText = mapOf(
        "NO_RESPONSE" to "무응답 늘어남",
        "WAKE_TIME" to "기상 늦어짐",
        "CONDITION" to "컨디션 ↓",
        "MEAL" to "식사 ↓",
    )

    /** risk-summary 실패 때 reasons 로 만드는 고정 문장 (예: "식사 ↓ · 기상 늦어짐") */
    fun riskSummary(reasons: List<RiskSummaryReason>, triggers: List<String>): String {
        val parts = triggers.map { kr.todak.core.domain.AlertReason.text(it) } +
            reasons.filter { it.contribution > 0.0 }
                .sortedByDescending { it.contribution }
                .mapNotNull { itemText[it.item] }
        return if (parts.isEmpty()) "평소와 비슷해요" else parts.joinToString(" · ")
    }
}

/** AI 호출 + 명세서의 '실패할 때' 대체 동작 */
@Service
class AiGateway(factory: AiClientFactory) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = factory.client
    val mode: String get() = client.mode

    data class ChatResult(val reply: String, val endConversation: Boolean, val crisisFlag: Boolean, val fallback: Boolean)

    fun chat(req: ChatRequest): ChatResult {
        val started = System.currentTimeMillis()
        val lastSenior = req.history.lastOrNull { it.role == "senior" }?.text
        return try {
            val res = client.chat(req)
            val reply = res.reply?.trim().orEmpty()
            if (reply.isEmpty()) throw IllegalStateException("빈 reply")
            log.info(
                "[AI chat] {}ms turn={}/{} senior=\"{}\" → reply=\"{}\" end={} crisis={}",
                System.currentTimeMillis() - started, req.turn, req.maxTurns, lastSenior, reply, res.endConversation, res.crisisFlag,
            )
            ChatResult(reply, res.endConversation, res.crisisFlag, fallback = false)
        } catch (e: Exception) {
            log.warn("[AI chat] {}ms 실패 → 대체 문장: {}", System.currentTimeMillis() - started, e.message)
            ChatResult(FallbackText.CHAT, endConversation = true, crisisFlag = false, fallback = true)
        }
    }

    fun classify(req: ClassifyRequest): ClassifyResponse = try {
        client.classify(req).also {
            log.info("[AI classify] summary=\"{}\" lonely={} crisis={} evidence=\"{}\"", it.summary, it.lonelinessSignal, it.crisisFlag, it.crisisEvidence)
        }
    } catch (e: Exception) {
        log.warn("AI classify 실패 → 빈 요약, 신호 false: {}", e.message)
        ClassifyResponse(summary = "", lonelinessSignal = false, crisisFlag = false)
    }

    fun riskSummary(req: RiskSummaryRequest): String = try {
        client.riskSummary(req).summary?.trim()?.takeIf { it.isNotEmpty() }
            ?: FallbackText.riskSummary(req.reasons, req.immediateTriggers)
    } catch (e: Exception) {
        log.warn("AI risk-summary 실패 → 고정 문장: {}", e.message)
        FallbackText.riskSummary(req.reasons, req.immediateTriggers)
    }
}
