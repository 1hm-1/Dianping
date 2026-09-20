#!/usr/bin/env python3
"""Verify a database outage preserves an order message in the durable failure queue."""
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import seckill_correctness as bench

FAILURE_QUEUE = "seckill.order.failure.queue.v2"


def compose(*args, capture=False):
    return subprocess.run(["docker", "compose", *args], check=True, text=True, capture_output=capture)


def queue_messages():
    output = compose("exec", "-T", "rabbitmq", "rabbitmqctl", "list_queues", "name", "messages", capture=True).stdout
    for line in output.splitlines():
        if line.startswith(FAILURE_QUEUE):
            return int(line.split()[-1])
    raise RuntimeError("failure queue not found")


def order_count(order_id):
    sql = "SELECT COUNT(*) FROM tb_voucher_order WHERE id = %s;" % order_id
    command = ["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
               'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" hmdp -N -e "' + sql + '"']
    return int(subprocess.run(command, check=True, text=True, capture_output=True).stdout.strip().splitlines()[-1])


def main():
    voucher_id = bench.create_voucher()
    order_id = int(time.time() * 1000)
    before = queue_messages()
    stopped = False
    try:
        compose("stop", "mysql")
        stopped = True
        # This directly injects an Outbox record to isolate the consumer/database
        # failure path; normal production records are created atomically by Lua.
        compose("exec", "-T", "redis", "redis-cli", "XADD", "stream.orders", "*",
                "userId", "900000001", "voucherId", str(voucher_id), "id", str(order_id))
        for _ in range(12):
            if queue_messages() > before:
                break
            time.sleep(1)
        retained = queue_messages() > before
    finally:
        if stopped:
            compose("start", "mysql")
    for _ in range(30):
        if "healthy" in compose("ps", "mysql", capture=True).stdout:
            break
        time.sleep(1)
    persisted = False
    for _ in range(45):
        persisted = order_count(order_id) == 1
        retained = retained or queue_messages() > before
        if persisted or retained:
            break
        time.sleep(1)
    result = {"voucher_id": voucher_id, "injected_order_id": order_id,
              "failure_queue_before": before, "failure_queue_after": queue_messages(),
              "message_retained": retained, "persisted_after_recovery": persisted}
    os.makedirs("bench/results", exist_ok=True)
    path = "bench/results/mysql-fault-%d.json" % int(time.time() * 1000)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print("RESULT_FILE=" + path)
    if not (retained or persisted):
        raise SystemExit("MySQL fault-injection assertion failed: message was neither persisted nor retained")


if __name__ == "__main__":
    main()
