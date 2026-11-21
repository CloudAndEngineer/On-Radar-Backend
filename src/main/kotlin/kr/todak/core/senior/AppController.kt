package kr.todak.core.senior

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import kr.todak.core.alert.AlertService
import kr.todak.core.auth.AuthSenior
import kr.todak.core.auth.Tokens
import kr.todak.core.common.NotFoundException
import kr.todak.core.domain.AlertLevel
import kr.todak.core.domain.AlertReason
import kr.todak.core.domain.AlertStage
import kr.todak.core.domain.SeniorRepository
import kr.todak.core.risk.RiskService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RegisterRequest(@field:NotBlank val registrationCode: String?, val fcmToken: String? = null)
data class RegisterResponse(val seniorId: Long, val deviceToken: String, val name: String, val honorific: String)
data class FcmTokenRequest(@field:NotBlank val fcmToken: String?)
data class EmergencyRequest(val source: String? = "BUTTON")
data class EmergencyResponse(val alertId: Long, val level: AlertLevel, val message: String)

@RestController
@RequestMapping("/api")
class AppController(
    private val seniors: SeniorRepository,
    private val alerts: AlertService,
    private val riskLater: RiskLater,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 등록 코드로 기기 등록, 토큰 발급 (다시 등록하면 이전 기기 토큰은 무효) */
    @PostMapping("/devices/register")
    fun register(@Valid @RequestBody req: RegisterRequest): RegisterResponse {
        val code = req.registrationCode!!.trim().uppercase()
        val senior = seniors.findByRegistrationCode(code)
            ?: throw NotFoundException("REGISTRATION_CODE_NOT_FOUND", "등록 코드가 맞지 않아요. 복지사 선생님께 확인해 주세요")
        val token = Tokens.random("dvc_")
        val saved = seniors.save(senior.copy(deviceToken = token, fcmToken = req.fcmToken?.takeIf { it.isNotBlank() } ?: senior.fcmToken))
        log.info("기기 등록 senior={}", saved.id)
        return RegisterResponse(saved.id!!, token, saved.name, saved.honorificOrDefault())
    }

    /** 앱 시작 시 FCM 토큰 갱신 → 204 */
    @PutMapping("/devices/fcm-token")
    fun fcmToken(me: AuthSenior, @Valid @RequestBody req: FcmTokenRequest): ResponseEntity<Void> {
        seniors.save(me.senior.copy(fcmToken = req.fcmToken!!.trim()))
        return ResponseEntity.noContent().build()
    }

    /** [도와주세요] — 분석 없이 바로 EMERGENCY 알림 (담당 복지사부터) */
    @PostMapping("/emergency")
    fun emergency(me: AuthSenior, @RequestBody(required = false) req: EmergencyRequest?): EmergencyResponse {
        val source = req?.source ?: "BUTTON"
        // 앱은 119 다이얼 화면만 띄우고 통화는 어르신이 직접 누릅니다. 서버는 통화 여부를 모르므로
        // 알림 사유(타임라인·SSE reasonText)는 "어르신이 [도와주세요]를 눌러 119 전화 화면이 열렸어요" 로 사실만 적습니다.
        val alert = alerts.open(me.id, AlertLevel.EMERGENCY, AlertReason.HELP_BUTTON, AlertStage.CAREGIVER, source.takeIf { it != "BUTTON" })
        riskLater.reassess(me.id)
        return EmergencyResponse(alert.id!!, AlertLevel.EMERGENCY, "복지사 선생님께 바로 알렸어요")
    }
}

/** 응답을 늦추지 않도록 위험 점수 재계산을 뒤에서 */
@Component
class RiskLater(private val risk: RiskService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    fun reassess(seniorId: Long) {
        try {
            risk.reassess(seniorId)
        } catch (e: Exception) {
            log.error("위험 점수 재계산 실패 senior={}", seniorId, e)
        }
    }
}
