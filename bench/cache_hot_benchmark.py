#!/usr/bin/env python3
import concurrent.futures, json, statistics, time, urllib.request

TOTAL, WORKERS, URL = 1000, 100, "http://localhost:8081/shop/1"
def hit(_):
    start = time.perf_counter()
    with urllib.request.urlopen(URL, timeout=10) as response:
        response.read()
        assert response.status == 200
    return (time.perf_counter() - start) * 1000

started = time.perf_counter()
with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
    samples = list(pool.map(hit, range(TOTAL)))
elapsed = time.perf_counter() - started
samples.sort()
report = {"requests": TOTAL, "workers": WORKERS, "qps": round(TOTAL / elapsed, 2),
          "p50_ms": round(samples[499], 2), "p95_ms": round(samples[949], 2),
          "p99_ms": round(samples[989], 2), "max_ms": round(samples[-1], 2)}
print(json.dumps(report, ensure_ascii=False, indent=2))
