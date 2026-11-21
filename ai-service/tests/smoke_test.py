"""엔드포인트 4개가 명세서 모양대로 답하는지 확인한다. 표준 라이브러리만 쓴다.

사용: python tests/smoke_test.py [BASE_URL]
  BASE_URL 기본값 http://localhost:8001  (배포 후에는 Render 주소)
"""
import json
import sys
import time
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8001").rstrip("/")


def call(method: str, path: str, body=None):
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    req = urllib.request.Request(
        BASE + path, data=data, method=method, headers={"Content-Type": "application/json"}
    )
    started = time.time()
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode()), time.time() - started


CASES = [
    ("GET", "/health", None, ["status", "geminiConfigured", "promptsReady"]),
    (
        "POST",
        "/v1/chat",
        {
            "senior": {"name": "김순자", "honorific": "할머니"},
            "session": "EVENING",
            "todayCheck": {"mealCount": 1},
            "memory": ["어제 경로당에 다녀오심"],
            "history": [],
            "turn": 1,
            "maxTurns": 3,
        },
        ["reply", "endConversation", "crisisFlag"],
    ),
    (
        "POST",
        "/v1/classify",
        {
            "transcript": [
                {"role": "ai", "text": "오늘은 밥을 한 번 드셨네요. 입맛이 없으셨어요?"},
                {"role": "senior", "text": "요즘 아무도 안 와. 혼자 먹으니까 귀찮아."},
            ],
            "todayCheck": {"mealCount": 1},
        },
        ["summary", "lonelinessSignal", "crisisFlag"],
    ),
    (
        "POST",
        "/v1/risk-summary",
        {
            "seniorName": "김순자",
            "riskLevel": "DANGER",
            "score": 78,
            "reasons": [
                {"item": "MEAL", "baseline": 3.0, "recent": 1.0, "contribution": 26},
                {"item": "WAKE_TIME", "baseline": "07:05", "recent": "10:10", "contribution": 20},
                {"item": "CONDITION", "baseline": 1.9, "recent": 0.0, "contribution": 15},
            ],
            "lonelinessDays": 2,
        },
        ["summary"],
    ),
]

failed = 0
for method, path, body, keys in CASES:
    try:
        res, sec = call(method, path, body)
        missing = [k for k in keys if k not in res]
        ok = not missing
        print(f"{'OK ' if ok else 'BAD'} {method} {path} ({sec:.1f}s) {json.dumps(res, ensure_ascii=False)}")
        if missing:
            print(f"    빠진 필드: {missing}")
            failed += 1
    except Exception as e:  # noqa: BLE001
        print(f"ERR {method} {path}: {e}")
        failed += 1

print("\n모두 통과" if failed == 0 else f"\n실패 {failed}개")
sys.exit(1 if failed else 0)
