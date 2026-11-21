package kr.todak.core.sse

import jakarta.servlet.http.HttpServletResponse
import kr.todak.core.auth.AuthResolver
import kr.todak.core.common.ApiException
import kr.todak.core.domain.SeniorRepository
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** SSE 이벤트 이름 (명세서 표 그대로) */
object SseEvents {
    const val CHECK_SUBMITTED = "check-submitted"
    const val RISK_UPDATED = "risk-updated"
    const val ALERT_CREATED = "alert-created"
    const val ALERT_UPDATED = "alert-updated"
    const val TIMELINE_ADDED = "timeline-added"
    const val PING = "ping"
}

@Component
class SseHub(private val seniors: SeniorRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** caregiverId → 열린 연결들 (탭 여러 개 가능) */
    private val emitters = ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>()

    fun subscribe(caregiverId: Long): SseEmitter {
        val emitter = SseEmitter(0L) // 서버 쪽 타임아웃 없음, 30초 ping 으로 유지
        val list = emitters.computeIfAbsent(caregiverId) { CopyOnWriteArrayList() }
        list.add(emitter)
        val remove = Runnable { list.remove(emitter) }
        emitter.onCompletion(remove)
        emitter.onTimeout(remove)
        emitter.onError { remove.run() }
        // 연결 직후 한 번 보내야 프록시가 응답 헤더를 바로 넘겨 줍니다
        send(emitter, list, "connected", mapOf("caregiverId" to caregiverId))
        return emitter
    }

    /** 어르신 담당 복지사에게 이벤트. 트랜잭션 안이면 커밋 뒤에 보냅니다 */
    fun publishForSenior(seniorId: Long, event: String, data: Any) {
        val caregiverId = seniors.findById(seniorId).map { it.caregiverId }.orElse(null) ?: return
        publish(caregiverId, event, data)
    }

    fun publish(caregiverId: Long, event: String, data: Any) {
        val action = Runnable {
            val list = emitters[caregiverId] ?: return@Runnable
            list.forEach { send(it, list, event, data) }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = action.run()
            })
        } else {
            action.run()
        }
    }

    fun connectionCount(): Int = emitters.values.sumOf { it.size }

    @Scheduled(fixedRate = 30_000)
    fun ping() {
        emitters.forEach { (_, list) -> list.forEach { send(it, list, SseEvents.PING, mapOf("ok" to true)) } }
    }

    private fun send(emitter: SseEmitter, list: MutableList<SseEmitter>, event: String, data: Any) {
        try {
            synchronized(emitter) {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON))
            }
        } catch (e: Exception) {
            log.debug("SSE send failed, dropping emitter: {}", e.message)
            list.remove(emitter)
            runCatching { emitter.complete() }
        }
    }
}

@RestController
@RequestMapping("/api")
class StreamController(private val hub: SseHub, private val auth: AuthResolver) {

    /** GET /stream?token=... (EventSource 는 헤더를 못 넣으므로 쿼리로) */
    @GetMapping("/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(@RequestParam(required = false) token: String?, response: HttpServletResponse): ResponseEntity<SseEmitter> {
        val caregiver = try {
            auth.caregiver(token)
        } catch (e: ApiException) {
            return ResponseEntity.status(e.status).build()
        }
        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("X-Accel-Buffering", "no") // Render/nginx 버퍼링 끄기
        return ResponseEntity.ok(hub.subscribe(caregiver.id))
    }
}
