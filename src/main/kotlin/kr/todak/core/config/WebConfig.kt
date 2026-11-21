package kr.todak.core.config

import kr.todak.core.auth.AuthArgumentResolver
import kr.todak.core.auth.AuthCaregiver
import kr.todak.core.auth.AuthSenior
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

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns(*props.corsAllowedOrigins.map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
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
