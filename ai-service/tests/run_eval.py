"""테스트 문장 세트로 분류 정확도를 잰다. 프롬프트를 고칠 때마다 돌린다.

사용: python tests/run_eval.py [BASE_URL] [쉬는초(기본 4)]
무료 등급은 분당 요청 수 한도가 있어, 문장 사이에 쉬어야 실패(429)로 인한 오답이 안 섞인다.
목표: 위기·응급(C*, E*) 6개 모두 + 나머지 19개 중 18개 이상
"""
import csv
import json
import sys
import time
import urllib.request
from pathlib import Path

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8001").rstrip("/")
PAUSE = float(sys.argv[2]) if len(sys.argv) > 2 else 4.0
CSV_PATH = Path(__file__).with_name("test_sentences.csv")
OPENER = "오늘 하루는 어떠셨어요?"


def post(path: str, body: dict) -> dict:
    req = urllib.request.Request(
        BASE + path,
        data=json.dumps(body, ensure_ascii=False).encode(),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode())


def matches(expected: str, actual: bool) -> bool:
    return expected == "any" or (expected == "true") == actual


rows = list(csv.DictReader(CSV_PATH.open(encoding="utf-8")))
results = []
crisis_total = crisis_hit = other_total = other_hit = 0

for n, row in enumerate(rows):
    if n:
        time.sleep(PAUSE)
    transcript = [{"role": "ai", "text": OPENER}, {"role": "senior", "text": row["utterance"]}]
    started = time.time()
    cls = post("/v1/classify", {"transcript": transcript})
    chat = post(
        "/v1/chat",
        {
            "senior": {"name": "김순자", "honorific": "할머니"},
            "session": "EVENING",
            "history": transcript,
            "turn": 2,
            "maxTurns": 3,
        },
    )
    sec = time.time() - started

    lonely_ok = matches(row["expect_loneliness"], cls["lonelinessSignal"])
    # 위기는 매 턴 판단하는 chat 쪽과 끝에 판단하는 classify 쪽 둘 다 봐야 한다
    crisis_ok = matches(row["expect_crisis"], cls["crisisFlag"]) and matches(row["expect_crisis"], chat["crisisFlag"])
    ok = lonely_ok and crisis_ok

    if row["id"][0] in "CE":
        crisis_total += 1
        crisis_hit += ok
    else:
        other_total += 1
        other_hit += ok

    mark = "OK " if ok else "BAD"
    print(f"{mark} {row['id']:<3} {row['utterance'][:22]:<24} 외로움={cls['lonelinessSignal']!s:<5} "
          f"위기(분류/대화)={cls['crisisFlag']!s}/{chat['crisisFlag']!s}  {sec:.1f}s  답: {chat['reply'][:30]}")
    results.append({**row, "got_loneliness": cls["lonelinessSignal"], "got_crisis_classify": cls["crisisFlag"],
                    "got_crisis_chat": chat["crisisFlag"], "summary": cls["summary"], "reply": chat["reply"], "ok": ok})

fallback_n = sum(1 for r in results if r["reply"].startswith(("그러셨군요. 조금 더", "말씀 고마워요.")))
if fallback_n:
    print(f"\n※ 대화 답 {fallback_n}개가 대체 문장이에요. Gemini 한도(429)나 지연일 수 있으니 서버 로그를 확인하세요.")
print(f"\n위기·응급 {crisis_hit}/{crisis_total} (목표 {crisis_total}/{crisis_total})")
print(f"나머지     {other_hit}/{other_total} (목표 {other_total - 1}/{other_total} 이상)")

out = Path(f"eval_result_{time.strftime('%H%M%S')}.csv")
with out.open("w", encoding="utf-8", newline="") as f:
    w = csv.DictWriter(f, fieldnames=list(results[0].keys()))
    w.writeheader()
    w.writerows(results)
print(f"자세한 결과: {out}")
