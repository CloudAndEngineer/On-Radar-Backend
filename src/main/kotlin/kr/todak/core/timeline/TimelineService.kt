package kr.todak.core.timeline

import kr.todak.core.common.TodakClock
import kr.todak.core.domain.ActionType
import kr.todak.core.domain.ActorType
import kr.todak.core.domain.TimelineEvent
import kr.todak.core.domain.TimelineRepository
import kr.todak.core.sse.SseEvents
import kr.todak.core.sse.SseHub
import org.springframework.stereotype.Service

@Service
class TimelineService(
    private val repo: TimelineRepository,
    private val clock: TodakClock,
    private val sse: SseHub,
) {
    fun system(seniorId: Long, action: ActionType, memo: String?): TimelineEvent =
        record(seniorId, ActorType.SYSTEM, "토닥", action, memo)

    /** timeline_events 에 alert_id 컬럼이 없어(ERD), 어느 알림에 대한 조치인지는 저장하지 않습니다 */
    fun record(
        seniorId: Long,
        actorType: ActorType,
        actorName: String?,
        action: ActionType,
        memo: String?,
    ): TimelineEvent {
        val saved = repo.save(
            TimelineEvent(
                seniorId = seniorId,
                actorType = actorType,
                actorName = actorName,
                actionType = action,
                memo = memo,
                createdAt = clock.now(),
            ),
        )
        sse.publishForSenior(seniorId, SseEvents.TIMELINE_ADDED, mapOf("seniorId" to seniorId, "eventId" to saved.id))
        return saved
    }

    /** 같은 날 같은 미응답을 두 번 남기지 않기 위한 확인 */
    fun exists(seniorId: Long, action: ActionType, memo: String): Boolean =
        repo.countByActionAndMemo(seniorId, action.name, memo) > 0
}
