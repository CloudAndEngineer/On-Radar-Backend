package kr.todak.core.config

import kr.todak.core.auth.AuthArgumentResolver
import kr.todak.core.auth.AuthCaregiver
import kr.todak.core.auth.AuthSenior
import org.slf4j.LoggerFactory
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val props: TodakProperties,
    private val authArgumentResolver: AuthArgumentResolver,
) : WebMvcConfigurer {

    init {
        // Swagger 문서(/api/docs)에 인증 파라미터가 쿼리처럼 보이지 않도록
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(AuthSenior::class.java, AuthCaregiver::class.java)
    }

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 환경변수로 들어온 허용 origin 을 정리합니다.
     * 쉼표로 이어 붙인 값, 앞뒤 공백·따옴표, 끝의 '/' 는 브라우저가 보내는 Origin 과 달라
     * 조용히 403 이 나므로 여기서 맞춰 줍니다. 비어 있으면 '*' 로 둡니다.
     */
    internal fun corsOrigins(): List<String> =
        props.corsAllowedOrigins
            .flatMap { it.split(",") }
            .map { it.trim().trim('"', '\'').trim().removeSuffix("/") }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { listOf("*") }

    override fun addCorsMappings(registry: CorsRegistry) {
        val origins = corsOrigins()
        log.info("CORS 허용 origin: {}", origins)
        registry.addMapping("/api/**")
            .allowedOriginPatterns(*origins.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(false)
            .maxAge(3600)
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(authArgumentResolver)
    }

    /** AI 요약·분류 같은 뒤처리용 (@Async) */
    @Bean(name = ["taskExecutor"])
    fun taskExecutor(): TaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 4
        maxPoolSize = 8
        queueCapacity = 200
        setThreadNamePrefix("todak-async-")
        initialize()
    }

    /** 1분 알림 스케줄러, 30초 ping, 11시/21시 작업이 서로 막지 않도록 */
    @Bean
    fun taskScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 4
        setThreadNamePrefix("todak-sched-")
        initialize()
    }
}
