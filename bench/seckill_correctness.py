#!/usr/bin/env python3
"""Create isolated seckill data, issue concurrent orders, and write client metrics.

Only Python's standard library is used. The script deliberately creates fresh test
users and a timestamped voucher so that it never mixes results with demo data.
"""
import concurrent.futures
import json
import os
import statistics
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta

BASE_URL = os.getenv("HMDP_BASE_URL", "http://localhost:8081")
USERS = int(os.getenv("HMDP_BENCH_USERS", "1000"))
STOCK = int(os.getenv("HMDP_BENCH_STOCK", "100"))
WORKERS = int(os.getenv("HMDP_BENCH_WORKERS", "200"))
STAMP = str(int(time.time() * 1000))


def request(path, method="GET", data=None, token=None):
    body = None if data is None else json.dumps(data).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["authorization"] = token
    req = urllib.request.Request(BASE_URL + path, data=body, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=20) as response:
        return json.loads(response.read().decode("utf-8"))


def create_voucher():
    # The deployed JVM and MySQL containers use UTC. Keep benchmark time windows
    # in that same clock to avoid rejecting every request as "not started".
    now = datetime.utcnow()
    payload = {
        "shopId": 1,
        "title": "BENCH-SECKILL-" + STAMP,
        "subTitle": "automated correctness benchmark",
        "rules": "benchmark only",
        "payValue": 1,
        "actualValue": 1,
        "type": 1,
        "status": 1,
        "stock": STOCK,
        "beginTime": (now - timedelta(minutes=1)).isoformat(timespec="seconds"),
        "endTime": (now + timedelta(minutes=10)).isoformat(timespec="seconds"),
    }
    result = request("/voucher/seckill", "POST", payload)
    if not result.get("success"):
        raise RuntimeError("voucher creation failed: " + str(result))
    return int(result["data"])


def phone_for(index):
    # 11-digit valid mainland mobile number; timestamp keeps repeated runs isolated.
    return "139" + (STAMP[-5:] + f"{index:03d}")[-8:]


def request_code(phone):
    code_result = request("/user/code?" + urllib.parse.urlencode({"phone": phone}), "POST")
    if not code_result.get("success"):
        raise RuntimeError("code failed for " + phone)


def read_codes(phones):
    command = ["docker", "compose", "exec", "-T", "redis", "redis-cli", "--raw", "MGET"]
    command.extend("login:code:" + phone for phone in phones)
    completed = subprocess.run(command, check=True, capture_output=True, text=True)
    codes = completed.stdout.splitlines()
    if len(codes) != len(phones) or any(not code for code in codes):
        raise RuntimeError("failed to read one or more benchmark verification codes")
    return dict(zip(phones, codes))


def login(phone, code):
    login_result = request("/user/login", "POST", {"phone": phone, "code": code})
    if not login_result.get("success"):
        raise RuntimeError("login failed for " + phone + ": " + str(login_result))
    return phone, login_result["data"]


def order(voucher_id, phone, token):
    started = time.perf_counter()
    try:
        result = request(f"/voucher-order/seckill/{voucher_id}", "POST", token=token)
        return {"phone": phone, "elapsed_ms": (time.perf_counter() - started) * 1000, "result": result}
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, ValueError) as error:
        return {"phone": phone, "elapsed_ms": (time.perf_counter() - started) * 1000, "error": str(error)}


def percentile(samples, point):
    if not samples:
        return None
    samples = sorted(samples)
    return samples[min(len(samples) - 1, int(len(samples) * point + 0.999999) - 1)]


def main():
    voucher_id = create_voucher()
    phones = [phone_for(index) for index in range(USERS)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        list(pool.map(request_code, phones))
    codes = read_codes(phones)
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        credentials = list(pool.map(lambda phone: login(phone, codes[phone]), phones))
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        futures = [pool.submit(order, voucher_id, phone, token) for phone, token in credentials]
        results = [future.result() for future in futures]
    elapsed = time.perf_counter() - started
    latencies = [item["elapsed_ms"] for item in results]
    successes = [item for item in results if item.get("result", {}).get("success")]
    report = {
        "timestamp": STAMP,
        "voucher_id": voucher_id,
        "users": USERS,
        "stock": STOCK,
        "workers": WORKERS,
        "accepted_orders": len(successes),
        "request_errors": sum("error" in item for item in results),
        "elapsed_seconds": round(elapsed, 3),
        "qps": round(len(results) / elapsed, 2),
        "latency_ms": {
            "p50": round(percentile(latencies, 0.50), 2),
            "p95": round(percentile(latencies, 0.95), 2),
            "p99": round(percentile(latencies, 0.99), 2),
            "max": round(max(latencies), 2),
        },
        "order_ids": [item["result"].get("data") for item in successes],
    }
    os.makedirs("bench/results", exist_ok=True)
    path = f"bench/results/seckill-{STAMP}.json"
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print("RESULT_FILE=" + path)


if __name__ == "__main__":
    main()
