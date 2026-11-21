package kr.todak.core.ai

import kr.todak.core.config.TodakProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

// ---- AI 서비스 입출력 (명세서 'AI 서비스 입출력' 그대로) ----

data class AiSenior(val name: String, val honorific: String)
data class AiTurn(val role: String, val text: String) // role = "ai" | "senior"

data class ChatRequest(
    val senior: AiSenior,
    val session: String,
    val todayCheck: Map<String, Int>,
    val memory: List<String>,
    val history: List<AiTurn>,
    val turn: Int,
    val maxTurns: Int,
)

data class ChatResponse(
    val reply: String? = null,
    val endConversation: Boolean = false,
    val crisisFlag: Boolean = false,
)

data class ClassifyRequest(val transcript: List<AiTurn>, val todayCheck: Map<String, Int>)

data class ClassifyResponse(
    val summary: String? = null,
    val lonelinessSignal: Boolean = false,
    val lonelinessEvidence: String? = null,
    val crisisFlag: Boolean = false,
    val crisisEvidence: String? = null,
)

data class RiskSummaryReason(val item: String, val baseline: Any?, val recent: Any?, val contribution: Double)

data class RiskSummaryRequest(
    val seniorName: String,
    val riskLevel: String,
    val score: Int,
    val reasons: List<RiskSummaryReason>,
    val immediateTriggers: List<String>,
    val lonelinessDays: Int,
)

data class RiskSummaryResponse(val summary: String? = null)

/** 실패하면 예외를 던집니다. 대체 동작은 AiGateway 에서. */
interface AiClient {
    fun chat(req: ChatRequest): ChatResponse
    fun classify(req: ClassifyRequest): ClassifyResponse
    fun riskSummary(req: RiskSummaryRequest): RiskSummaryResponse
    val mode: String
}

/** AI 서비스 HTTP 호출. 타임아웃: chat 5초 · classify·risk-summary 13초 */
class HttpAiClient(baseUrl: String) : AiClient {
    override val mode = "http"
    private val base = baseUrl.trimEnd('/')

    private fun client(readTimeout: Duration): RestClient {
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(readTimeout) }
        return RestClient.builder().baseUrl(base).requestFactory(factory).build()
    }

    private val fast = client(Duration.ofSeconds(5))
    private val slow = client(Duration.ofSeconds(13))

    override fun chat(req: ChatRequest): ChatResponse =
        fast.post().uri("/chat").contentType(MediaType.APPLICATION_JSON).body(req).retrieve()
            .body(ChatResponse::class.java) ?: error("empty chat response")

    override fun classify(req: ClassifyRequest): ClassifyResponse =
        slow.post().uri("/classify").contentType(MediaType.APPLICATION_JSON).body(req).retrieve()
            .body(ClassifyResponse::class.java) ?: error("empty classify response")

    override fun riskSummary(req: RiskSummaryRequest): RiskSummaryResponse =
        slow.post().uri("/risk-summary").contentType(MediaType.APPLICATION_JSON).body(req).retrieve()
            .body(RiskSummaryResponse::class.java) ?: error("empty risk-summary response")

    /** AI 서비스 주소의 /v1 앞에 있는 /health */
    fun health(): Boolean = try {
        val root = base.removeSuffix("/v1")
        fast.get().uri("$root/health").retrieve().toBodilessEntity().statusCode.is2xxSuccessful
    } catch (e: Exception) {
        false
    }
}

/**
 * AI_BASE_URL 이 없을 때 쓰는 모의 AI. 앱·웹 개발을 AI 서비스 없이 시작할 수 있게 합니다.
 */
class MockAiClient : AiClient {
    override val mode = "mock"

    override fun chat(req: ChatRequest): ChatResponse {
        val h = req.senior.honorific
        val reply = when {
            req.turn >= req.maxTurns -> "이야기 나눠 주셔서 고마워요, $h. 오늘도 편안히 보내세요."
            req.history.isEmpty() && req.session == "MORNING" -> when (req.todayCheck["conditionScore"]) {
                2 -> "$h, 오늘 몸이 괜찮으시다니 다행이에요. 오늘은 뭐 하실 계획이세요?"
                0 -> "$h, 오늘 몸이 안 좋으시군요. 어디가 제일 불편하세요?"
                else -> "$h, 오늘은 그저 그러시군요. 밤에 잠은 좀 주무셨어요?"
            }
            req.history.isEmpty() -> "$h, 오늘 식사는 ${req.todayCheck["mealCount"] ?: 0}번 하셨네요. 입맛은 어떠셨어요?"
            else -> "그러셨군요, $h. 오늘은 누구랑 이야기 나누셨어요?"
        }
        val lastSenior = req.history.lastOrNull { it.role == "senior" }?.text.orEmpty()
        return ChatResponse(reply, endConversation = req.turn >= req.maxTurns, crisisFlag = CrisisWords.contains(lastSenior))
    }

    override fun classify(req: ClassifyRequest): ClassifyResponse {
        val seniorLines = req.transcript.filter { it.role == "senior" }.map { it.text }
        val lonely = seniorLines.firstOrNull { s -> listOf("혼자", "외로", "아무도", "적적", "심심").any { it in s } }
        val crisis = seniorLines.firstOrNull { CrisisWords.contains(it) }
        val summary = seniorLines.joinToString(", ").take(40).ifBlank { "" }
        return ClassifyResponse(summary, lonely != null, lonely, crisis != null, crisis)
    }

    override fun riskSummary(req: RiskSummaryRequest): RiskSummaryResponse =
        RiskSummaryResponse(FallbackText.riskSummary(req.reasons, req.immediateTriggers))
}

@Component
class AiClientFactory(props: TodakProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    val client: AiClient = if (props.aiBaseUrl.isBlank()) {
        log.warn("AI_BASE_URL 없음 → 내장 모의 AI 로 동작합니다")
        MockAiClient()
    } else {
        log.info("AI 서비스: {}", props.aiBaseUrl)
        HttpAiClient(props.aiBaseUrl)
    }

    /** Render 무료 인스턴스가 잠들지 않도록 10분마다 AI /health 를 두드립니다 */
    @Scheduled(fixedRate = 600_000, initialDelay = 60_000)
    fun keepAiAwake() {
        val c = client as? HttpAiClient ?: return
        if (!c.health()) log.warn("AI 서비스 /health 응답 없음")
    }
}
