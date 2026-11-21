package kr.todak.core.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "todak")
data class TodakProperties(
    val demoToday: String = "",
    val alertStepMinutes: Long = 120,
    val aiBaseUrl: String = "",
    val corsAllowedOrigins: List<String> = listOf("*"),
    val firebaseCredentials: String = "",
    val seedEnabled: Boolean = true,
    val demoCaregiverLoginId: String = "demo",
    val demoCaregiverPassword: String = "todak1234",
    val authSecret: String = "",
    val morningOpensAt: String = "08:00",
    val morningDeadline: String = "11:00",
    val eveningOpensAt: String = "18:00",
    val eveningDeadline: String = "21:00",
    val checkTimeEnforced: Boolean = true,
)
