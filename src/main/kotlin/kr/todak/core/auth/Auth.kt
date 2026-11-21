package kr.todak.core.auth

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import kr.todak.core.common.TodakClock
import kr.todak.core.common.UnauthorizedException
import kr.todak.core.domain.Caregiver
import kr.todak.core.domain.CaregiverRepository
import kr.todak.core.domain.Senior
import kr.todak.core.domain.SeniorRepository
import org.springframework.core.MethodParameter
import kr.todak.core.config.TodakProperties
import org.springframework.stereotype.Component
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.security.SecureRandom

/** 컨트롤러 파라미터로 받으면 X-Device-Token 으로 인증된 어르신 */
data class AuthSenior(val senior: Senior) {
    val id: Long get() = senior.id!!
}

/** 컨트롤러 파라미터로 받으면 Bearer 토큰(SSE는 ?token=)으로 인증된 복지사 */
data class AuthCaregiver(val caregiver: Caregiver) {
    val id: Long get() = caregiver.id!!
}

object Tokens {
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    fun random(prefix: String, length: Int = 32): String =
        prefix + (1..length).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
}

/** Caregiver passwords are stored and verified as BCrypt hashes. */
object Passwords {
    private val encoder = BCryptPasswordEncoder()

    fun hash(raw: String): String = encoder.encode(raw)

    fun matches(raw: String, stored: String?): Boolean =
        stored != null && runCatching { encoder.matches(raw, stored) }.getOrDefault(false)
}

/**
 * 복지사 로그인 토큰 — 서명된 토큰이라 DB 테이블이 필요 없습니다.
 * 형식: cg_<caregiverId>.<발급초>.<HMAC>. AUTH_SECRET 이 없으면 기동할 때마다 새 키 (재시작하면 다시 로그인)
 */
@Component
class CaregiverTokenStore(props: TodakProperties, private val clock: TodakClock) {
    private val key: ByteArray = props.authSecret.takeIf { it.isNotBlank() }?.toByteArray()
        ?: ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun sign(payload: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.toByteArray()))
    }

    fun issue(caregiverId: Long): String {
        val payload = "$caregiverId.${clock.now().toEpochSecond(java.time.ZoneOffset.ofHours(9))}"
        return "cg_$payload.${sign(payload)}"
    }

    fun caregiverIdOf(token: String): Long? {
        val body = token.removePrefix("cg_")
        val i = body.lastIndexOf('.')
        if (i <= 0) return null
        val payload = body.substring(0, i)
        if (!java.security.MessageDigest.isEqual(sign(payload).toByteArray(), body.substring(i + 1).toByteArray())) return null
        return payload.substringBefore('.').toLongOrNull()
    }
}

@Component
class AuthResolver(
    private val seniors: SeniorRepository,
    private val caregivers: CaregiverRepository,
    private val tokens: CaregiverTokenStore,
) {
    fun senior(deviceToken: String?): AuthSenior {
        if (deviceToken.isNullOrBlank()) throw UnauthorizedException(message = "X-Device-Token 헤더가 필요해요")
        val senior = seniors.findByDeviceToken(deviceToken.trim())
            ?: throw UnauthorizedException("INVALID_DEVICE_TOKEN", "등록되지 않은 기기예요. 다시 등록해 주세요")
        return AuthSenior(senior)
    }

    fun caregiver(bearerOrToken: String?): AuthCaregiver {
        val token = bearerOrToken?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrBlank()) throw UnauthorizedException()
        val id = tokens.caregiverIdOf(token)
            ?: throw UnauthorizedException("INVALID_TOKEN", "로그인이 만료됐어요. 다시 로그인해 주세요")
        val caregiver = caregivers.findById(id).orElseThrow { UnauthorizedException("INVALID_TOKEN", "로그인이 만료됐어요") }
        return AuthCaregiver(caregiver)
    }
}

@Component
class AuthArgumentResolver(private val auth: AuthResolver) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == AuthSenior::class.java || parameter.parameterType == AuthCaregiver::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any = when (parameter.parameterType) {
        AuthSenior::class.java -> auth.senior(webRequest.getHeader("X-Device-Token"))
        else -> auth.caregiver(webRequest.getHeader("Authorization") ?: webRequest.getParameter("token"))
    }
}

// ---- 로그인 ----

data class LoginRequest(@field:NotBlank val loginId: String?, @field:NotBlank val password: String?)
data class LoginResponse(val token: String, val caregiverId: Long, val name: String, val centerName: String?)

@RestController
@RequestMapping("/api/auth")
class AuthController(private val caregivers: CaregiverRepository, private val tokens: CaregiverTokenStore) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest): LoginResponse {
        val cg = caregivers.findByLoginId(req.loginId!!.trim())
        if (cg == null || !Passwords.matches(req.password!!, cg.passwordHash)) {
            throw UnauthorizedException("INVALID_CREDENTIALS", "아이디 또는 비밀번호가 맞지 않아요")
        }
        return LoginResponse(tokens.issue(cg.id!!), cg.id, cg.name, cg.centerName)
    }
}
