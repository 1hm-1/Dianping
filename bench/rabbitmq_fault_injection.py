#!/usr/bin/env python3
"""Verify that a RabbitMQ outage leaves the accepted order in Redis Stream PEL,
then that the order is eventually persisted after RabbitMQ recovers."""
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(__file__))
import seckill_correctness as bench


def compose(*args, capture=False):
    return subprocess.run(["docker", "compose", *args], check=True, text=True, capture_output=capture)


def redis(*args):
    return compose("exec", "-T", "redis", "redis-cli", *args, capture=True).stdout.strip()


def order_count(voucher_id):
    sql = "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = %s;" % voucher_id
    command = ["docker", "compose", "exec", "-T", "mysql", "sh", "-c",
               'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" hmdp -N -e "' + sql + '"']
    return int(subprocess.run(command, check=True, text=True, capture_output=True).stdout.strip().splitlines()[-1])


def main():
    voucher_id = bench.create_voucher()
    phone = bench.phone_for(9001)
    bench.request_code(phone)
    token = bench.read_codes([phone])[phone]
    _, token = bench.login(phone, token)
    result = {"voucher_id": voucher_id, "accepted": False, "pending_during_outage": None, "persisted_after_recovery": False}
    stopped = False
    try:
        compose("stop", "rabbitmq")
        stopped = True
        response = bench.order(voucher_id, phone, token)
        result["accepted"] = bool(response.get("result", {}).get("success"))
        time.sleep(2)
        result["pending_during_outage"] = int(redis("XPENDING", "stream.orders", "g1").splitlines()[0])
    finally:
        if stopped:
            compose("start", "rabbitmq")
    for _ in range(30):
        if order_count(voucher_id) == 1:
            result["persisted_after_recovery"] = True
            break
        time.sleep(1)
    os.makedirs("bench/results", exist_ok=True)
    path = "bench/results/rabbitmq-fault-%d.json" % int(time.time() * 1000)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(result, handle, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print("RESULT_FILE=" + path)
    if not (result["accepted"] and result["pending_during_outage"] >= 1 and result["persisted_after_recovery"]):
        raise SystemExit("RabbitMQ fault-injection assertion failed")


if __name__ == "__main__":
    main()
