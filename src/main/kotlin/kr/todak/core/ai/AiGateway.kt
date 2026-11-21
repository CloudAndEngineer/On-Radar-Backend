package kr.todak.core.ai

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** 위기 표현 단어 목록 — AI 분류 실패 때, 그리고 AI 판정과 이중 확인용 */
object CrisisWords {
    private val patterns = listOf(
        "죽고 싶", "죽고싶", "죽어야", "죽을래", "죽어 버리", "죽어버리",
        "살기 싫", "살기싫", "그만 살", "더 살아서", "살 이유", "사는 게 의미",
        "자살", "목숨을 끊", "세상 떠나고", "세상 뜨고", "먼저 가고 싶", "따라가고 싶",
        "숨이 안 쉬어", "숨을 못 쉬", "쓰러졌", "넘어져서 못 일어", "가슴이 너무 아파",
    )

    fun contains(text: String?): Boolean = find(text) != null

    fun find(text: String?): String? {
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
