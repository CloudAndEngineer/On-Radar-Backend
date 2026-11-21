package com.todak.core.seed

import com.todak.core.risk.CheckRecord
import com.todak.core.risk.Session
import java.time.LocalDate
import java.time.LocalTime

/**
 * 시연용 시드 데이터 설계 (DB와 무관한 순수 Kotlin).
 * 어르신 10명 × 14일. 처음 7일이 기준선, 마지막 날이 DEMO_TODAY.
 *  - 1 김순자: 서서히 나빠짐 → 위험 (점수 81 + 안 좋아요 3일 연속)
 *  - 2 박영철: 아침 2일 연속 미응답 → 위험 (즉시 위험), 가족 없음
 *  - 3 이정희: 끼니·기상이 조금 나빠짐 → 정상 상단
 *  - 4 최말순: 점수 정상, 최근 대화에 외로움 신호
 *  - 5 한복례: 시연 폰. 오늘 체크를 비워 둠 (시연 중 직접 제출)
 *  - 6~10: 평소와 비슷 → 정상
 */
object SeedPlan {

    const val DAYS = 14L

    data class Conversation(
        val date: LocalDate,
        val session: Session,
        val aiLine: String,
        val seniorLine: String,
        val summary: String,
        val loneliness: Boolean = false,
        val lonelinessEvidence: String? = null,
    )

    data class Senior(
        val no: Int,
        val name: String,
        val honorific: String,
        val birthYear: Int,
        val addressDong: String,
        val registrationCode: String,
        val hasFamily: Boolean,
        val checks: List<CheckRecord>,
        val conversations: List<Conversation>,
        val aiSummary: String,
    )

    fun build(today: LocalDate): List<Senior> {
        val start = today.minusDays(DAYS - 1)
        fun d(i: Long) = start.plusDays(i)

        return listOf(
            kimSoonja(today, ::d),
            parkYoungcheol(today, ::d),
            steady(3, "이정희", "할머니", 1948, "왕십리도선동", "LJH-0003", today, ::d, phase = 1, recentWorse = true,
                aiSummary = "최근 끼니가 조금 줄고 아침 응답이 다소 늦어졌어요. 큰 변화는 없어요."),
            steady(4, "최말순", "할머니", 1946, "행당1동", "CMS-0004", today, ::d, phase = 2,
                extraConversations = listOf(
                    Conversation(d(12), Session.MORNING, "요즘 자제분들과 연락은 자주 하세요?", "자식들이 바빠서 통 연락이 없어",
                        "자녀 연락이 뜸하다고 하심", loneliness = true, lonelinessEvidence = "자식들이 바빠서 통 연락이 없어"),
                    Conversation(d(13), Session.MORNING, "어제 저녁엔 뭐 하셨어요?", "말할 사람이 없으니 텔레비전만 봐",
                        "대화할 사람이 없다고 하심", loneliness = true, lonelinessEvidence = "말할 사람이 없으니 텔레비전만 봐"),
                ),
                aiSummary = "생활 패턴은 평소와 비슷해요. 최근 대화에서 외로움 표현이 있었어요."),
            steady(5, "한복례", "할머니", 1947, "마장동", "HBR-0005", today, ::d, phase = 3, skipToday = true,
                aiSummary = "평소와 비슷하게 지내고 계세요."),
            steady(6, "정광수", "할아버지", 1944, "금호2·3가동", "JGS-0006", today, ::d, phase = 4,
                aiSummary = "평소와 비슷하게 지내고 계세요."),
            steady(7, "오금자", "할머니", 1949, "옥수동", "OGJ-0007", today, ::d, phase = 5,
                aiSummary = "평소와 비슷하게 지내고 계세요."),
            steady(8, "윤덕배", "할아버지", 1943, "응봉동", "YDB-0008", today, ::d, phase = 6,
                aiSummary = "평소와 비슷하게 지내고 계세요."),
            steady(9, "서옥분", "할머니", 1950, "사근동", "SOB-0009", today, ::d, phase = 0,
                aiSummary = "평소와 비슷하게 지내고 계세요."),
            steady(10, "장만석", "할아버지", 1941, "송정동", "JMS-0010", today, ::d, phase = 3,
                aiSummary = "평소와 비슷하게 지내고 계세요."),
        )
    }

    // ── 1. 김순자: 서서히 나빠짐 ────────────────────────────────────────────

    private fun kimSoonja(today: LocalDate, d: (Long) -> LocalDate): Senior {
        val checks = mutableListOf<CheckRecord>()
        val baselineCondition = listOf(2, 2, 1, 2, 1, 2, 1)
        for (i in 0L..10L) {
            val idx = (i % 7).toInt()
            val (time, cond, meal) = when (i) {
                in 0L..6L -> Triple("08:00", baselineCondition[idx], 3)   // 평소: 8시 · 3끼 · 컨디션 1.6
                7L -> Triple("08:30", 1, 3)   // 11/15부터 조금씩 늦어짐 (기준선 밖이라 점수엔 안 들어감)
                8L -> Triple("09:00", 1, 2)
                9L -> Triple("09:20", 1, 2)
                else -> Triple("09:50", 1, 2)
            }
            checks += morning(d(i), time, cond)
            checks += evening(d(i), meal)
        }
        for (i in 11L..13L) checks += morning(d(i), "10:46", 0)
        checks += evening(d(11), 1)   // 11/20 · 11/21 저녁은 놓침

        val conversations = listOf(
            Conversation(d(2), Session.MORNING, "오늘은 뭐 하실 계획이세요?", "경로당에 좀 가볼까 해", "경로당에 가신다고 하심"),
            Conversation(d(5), Session.EVENING, "오늘 하루 어떠셨어요?", "무릎이 좀 쑤시더라고", "무릎이 아프다고 하심"),
            Conversation(d(9), Session.MORNING, "요즘 입맛은 어떠세요?", "예전 같지 않아", "입맛이 예전 같지 않다고 하심"),
            Conversation(d(11), Session.MORNING, "오늘은 몸이 좀 어떠세요?", "그냥 그래. 입맛도 없고", "몸이 안 좋고 입맛이 없다고 하심"),
            Conversation(d(12), Session.MORNING, "요즘 누구 만나신 적 있으세요?", "요즘 아무도 안 와",
                "찾아오는 사람이 없다고 하심, 입맛 없음", loneliness = true, lonelinessEvidence = "요즘 아무도 안 와"),
            Conversation(d(13), Session.MORNING, "어젯밤엔 잘 주무셨어요?", "밤새 뒤척였어. 혼자 있으니 적적해",
                "잠을 설치고 적적하다고 하심", loneliness = true, lonelinessEvidence = "혼자 있으니 적적해"),
        )
        return Senior(1, "김순자", "할머니", 1945, "성수1가2동", "KSJ-0001", hasFamily = true, checks, conversations,
            aiSummary = "'안 좋아요'가 3일 이어지고 아침 응답이 8시대에서 10시대로 늦어졌어요. 방문 상담을 권장해요.")
    }

    // ── 2. 박영철: 아침 2일 연속 미응답 ─────────────────────────────────────

    private fun parkYoungcheol(today: LocalDate, d: (Long) -> LocalDate): Senior {
        val checks = mutableListOf<CheckRecord>()
        val offsets = listOf(10, 15, 5, 12, 8, 14, 10)
        for (i in 0L..11L) {
            checks += morning(d(i), at(offsets[(i % 7).toInt()]), if (i % 3 == 2L) 1 else 2)
            checks += evening(d(i), if (i % 4 == 3L) 2 else 3)
        }
        checks += evening(d(12), 2)   // 11/20 저녁만 응답, 11/20 · 11/21 아침은 없음
        val conversations = listOf(
            Conversation(d(3), Session.MORNING, "오늘 날씨가 쌀쌀해요. 따뜻하게 입으셨어요?", "응, 내복 입었어", "따뜻하게 입으셨다고 하심"),
            Conversation(d(8), Session.EVENING, "저녁은 뭐 드셨어요?", "된장국에 밥 먹었지", "된장국으로 식사하심"),
            Conversation(d(11), Session.MORNING, "요즘 어디 불편한 데는 없으세요?", "허리가 좀 결려", "허리가 결린다고 하심"),
        )
        return Senior(2, "박영철", "할아버지", 1942, "용답동", "PYC-0002", hasFamily = false, checks, conversations,
            aiSummary = "이틀 연속 아침 체크가 없어요. 연락 확인이 필요해요.")
    }

    // ── 3~10. 평소와 비슷한 어르신 ───────────────────────────────────────────

    private val conditionCycle = listOf(2, 2, 1, 2, 2, 1, 2)
    private val mealCycle = listOf(3, 3, 2, 3, 3, 3, 2)
    private val wakeCycle = listOf(5, 12, 3, 9, 15, 7, 10)   // 8:00 이후 분

    private val dailyTalks = listOf(
        Triple("오늘은 뭐 하실 계획이세요?", "시장에 좀 다녀오려고", "시장에 다녀오신다고 하심"),
        Triple("요즘 운동은 좀 하세요?", "아침에 공원 한 바퀴 돌아", "아침마다 공원 산책하심"),
        Triple("손주들 소식은 있으세요?", "어제 전화 왔더라고", "손주에게 전화가 왔다고 기뻐하심"),
        Triple("오늘 날이 춥네요. 어떻게 지내세요?", "집에서 뜨개질했어", "집에서 뜨개질하셨다고 하심"),
        Triple("요즘 경로당엔 가세요?", "어제 화투 치고 왔지", "경로당에서 화투 치셨다고 하심"),
        Triple("저녁은 맛있게 드셨어요?", "김치찌개 끓여 먹었어", "김치찌개로 식사하심"),
    )

    private fun steady(
        no: Int, name: String, honorific: String, birthYear: Int, dong: String, code: String,
        today: LocalDate, d: (Long) -> LocalDate,
        phase: Int,
        aiSummary: String,
        recentWorse: Boolean = false,
        skipToday: Boolean = false,
        extraConversations: List<Conversation> = emptyList(),
    ): Senior {
        val checks = mutableListOf<CheckRecord>()
        val base = (no * 3) % 10   // 사람마다 기상 시각이 조금씩 다름
        for (i in 0L until DAYS) {
            if (skipToday && i == DAYS - 1) break
            val k = ((i + phase) % 7).toInt()
            val worse = recentWorse && i >= DAYS - 3
            val wakeMin = base + wakeCycle[k] + if (worse) 40 else 0
            val meal = if (worse) 2 else mealCycle[k]
            val cond = if (worse && i == DAYS - 1) 1 else conditionCycle[k]
            checks += morning(d(i), at(wakeMin), cond)
            checks += evening(d(i), meal)
        }
        val talkDays = listOf(1L, 4L, 7L, 10L).filter { i -> extraConversations.none { it.date == d(i) } }
        val conversations = talkDays.mapIndexed { idx, i ->
            val (ai, senior, summary) = dailyTalks[(idx + no) % dailyTalks.size]
            Conversation(d(i), if (idx % 2 == 0) Session.MORNING else Session.EVENING, ai, senior, summary)
        } + extraConversations
        return Senior(no, name, honorific, birthYear, dong, code, hasFamily = true, checks, conversations, aiSummary)
    }

    // ── 도우미 ───────────────────────────────────────────────────────────────

    private fun at(minutesAfter8: Int): String = LocalTime.of(8, 0).plusMinutes(minutesAfter8.toLong()).toString()

    private fun morning(date: LocalDate, time: String, condition: Int) =
        CheckRecord(date, Session.MORNING, date.atTime(LocalTime.parse(time)), conditionScore = condition)

    private fun evening(date: LocalDate, meals: Int) =
        CheckRecord(date, Session.EVENING, date.atTime(18, 20 + meals * 3), mealCount = meals)
}
