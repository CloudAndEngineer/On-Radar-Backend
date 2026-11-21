package kr.todak.core.alert

import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import kr.todak.core.config.TodakProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * 어르신 앱 FCM 푸시 (FCM HTTP v1 직접 호출 — firebase-admin 보다 가벼워 Render 무료 메모리에 맞음).
 * FIREBASE_CREDENTIALS (서비스 계정 JSON 원문 또는 base64) 가 없으면 보내지 않고 로그만 남깁니다.
 */
@Component
class PushSender(props: TodakProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val credentials: GoogleCredentials?
    private val projectId: String?
    private val http = RestClient.create()

    init {
        val (c, p) = load(props.firebaseCredentials)
        credentials = c
        projectId = p
    }

    val enabled: Boolean get() = credentials != null && projectId != null

    private fun load(raw: String): Pair<GoogleCredentials?, String?> {
        val text = raw.trim()
        if (text.isEmpty()) {
            log.warn("FIREBASE_CREDENTIALS 없음 → FCM 푸시는 로그로만 남깁니다")
            return null to null
        }
        return try {
            val json = if (text.startsWith("{")) text.toByteArray() else Base64.getDecoder().decode(text)
            val creds = GoogleCredentials.fromStream(ByteArrayInputStream(json))
                .createScoped(listOf("https://www.googleapis.com/auth/firebase.messaging"))
            val project = (creds as? ServiceAccountCredentials)?.projectId
            log.info("FCM 준비 완료 (project={})", project)
            creds to project
        } catch (e: Exception) {
            log.error("FCM 초기화 실패 → 로그로만 남깁니다: {}", e.message)
            null to null
        }
    }

    /** 성공하면 true. 토큰이 없거나 실패해도 예외를 던지지 않습니다 (알림 단계는 계속 진행) */
    fun send(fcmToken: String?, title: String, body: String, data: Map<String, String> = emptyMap()): Boolean {
        if (fcmToken.isNullOrBlank()) {
            log.warn("[FCM] 기기 토큰 없음 — 보내지 못함: {} / {}", title, body)
            return false
        }
        val creds = credentials
        if (creds == null || projectId == null) {
            log.info("[FCM 모의] to={}… title={} body={} data={}", fcmToken.take(12), title, body, data)
            return true
        }
        return try {
            creds.refreshIfExpired()
            val payload = mapOf(
                "message" to mapOf(
                    "token" to fcmToken,
                    "notification" to mapOf("title" to title, "body" to body),
                    "data" to data,
                    "android" to mapOf("priority" to "HIGH"),
                ),
            )
            http.post()
                .uri("https://fcm.googleapis.com/v1/projects/$projectId/messages:send")
                .header("Authorization", "Bearer ${creds.accessToken.tokenValue}")
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity()
            log.info("[FCM] sent: {}", title)
            true
        } catch (e: Exception) {
            log.error("[FCM] 실패: {}", e.message)
            false
        }
    }
}

/** 동주민센터 단계 — 시연에서는 모의 발송(로그 + 타임라인) */
@Component
class CenterDispatcher {
    private val log = LoggerFactory.getLogger(javaClass)
    fun dispatch(seniorName: String, addressDong: String?, reasonText: String) {
        log.info("[동주민센터 모의 발송] {} ({}) — {}", seniorName, addressDong ?: "-", reasonText)
    }
}
