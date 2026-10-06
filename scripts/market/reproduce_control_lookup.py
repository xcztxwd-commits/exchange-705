"""Disposable local MySQL 5.7 reproduction. No application database or network access."""
import argparse
import json
from pathlib import Path
import secrets
import subprocess
import time
import uuid


def docker(*args, data=None, timeout=120):
    result = subprocess.run(["docker", *args], input=data, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError("Docker command failed (details retained by caller): " + result.stderr.decode(errors="replace"))
    return (result.stdout + (result.stderr if args[0] == "logs" else b"")).decode()


def query(latest):
    predicate = "symbol_id=61 AND received_at<=1701000000000 AND source_time<=1701000000000"
    events = "SELECT symbol_id,source_time,received_at,price,event_sequence FROM market_source_event FORCE INDEX (source_event_time) WHERE tenant_id=1 AND " + predicate + " ORDER BY source_time DESC,received_at DESC,event_sequence DESC LIMIT 1"
    ticks = "SELECT t.symbol_id,t.source_time,t.received_at,t.price,0 AS event_sequence FROM market_source_tick t WHERE t.tenant_id=1 AND " + predicate
    ticks += (" ORDER BY t.source_time DESC LIMIT 1" if latest else " AND NOT EXISTS (SELECT 1 FROM market_source_event e WHERE e.tenant_id=t.tenant_id AND e.symbol_id=t.symbol_id AND e.source_time=t.source_time AND e.received_at=t.received_at AND e.price=t.price) ORDER BY source_time DESC,received_at DESC,event_sequence DESC LIMIT 1")
    return "SELECT price,source_time FROM ((" + events + ") UNION ALL (" + ticks + ")) ticks ORDER BY source_time DESC,received_at DESC,event_sequence DESC LIMIT 1"


def reproduce_fence(sql, output):
    # Minimal SQL transaction, not the full application: reuse the unchanged production hold trigger.
    migration = Path(__file__).resolve().parents[2] / "exchange-backend/src/main/resources/db/migration/V2026100304__market_engine_runtime.sql"
    source = migration.read_text(encoding="utf-8")
    trigger = "CREATE TRIGGER s2_control_hold_u" + source.split("CREATE TRIGGER s2_control_hold_u", 1)[1].split("END$$", 1)[0] + "END$$"
    sql("""USE repro;
CREATE TABLE market_engine_runtime(tenant_id BIGINT,symbol_id BIGINT,owner_id VARCHAR(36),writer_generation BIGINT,lease_until BIGINT,PRIMARY KEY(tenant_id,symbol_id)) ENGINE=InnoDB;
CREATE TABLE market_control_task(tenant_id BIGINT,id VARCHAR(36),symbol_id BIGINT,sampled_until BIGINT,status VARCHAR(16),PRIMARY KEY(tenant_id,id)) ENGINE=InnoDB;
CREATE TABLE market_control_hold(tenant_id BIGINT,task_id VARCHAR(36),activated_at BIGINT,PRIMARY KEY(tenant_id,task_id)) ENGINE=InnoDB;
CREATE TABLE market_control_sample(tenant_id BIGINT,task_id VARCHAR(36),generated_at BIGINT,PRIMARY KEY(tenant_id,task_id,generated_at)) ENGINE=InnoDB;
INSERT INTO market_engine_runtime VALUES(1,61,'fixture-writer',1,0);
INSERT INTO market_control_task VALUES(1,'fixture-task',61,1700099998000,'RUNNING');
INSERT INTO market_control_hold VALUES(1,'fixture-task',NULL);
DELIMITER $$
""" + trigger + "\nDELIMITER ;")
    snapshot = """USE repro; SELECT status,sampled_until,(SELECT COUNT(*) FROM market_control_sample),COALESCE((SELECT activated_at FROM market_control_hold),0) FROM market_control_task;"""
    previous = sql(snapshot).strip()
    begin = """USE repro; START TRANSACTION;
SELECT symbol_id FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=61 FOR UPDATE;
UPDATE market_engine_runtime SET lease_until=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)+15000 WHERE tenant_id=1 AND symbol_id=61;
SET @mt705_s2_owner='fixture-writer',@mt705_s2_fences='{"1:61":1}';
UPDATE market_control_task SET sampled_until=1700099999000,status='COMPLETED' WHERE tenant_id=1 AND id='fixture-task';
INSERT INTO market_control_sample VALUES(1,'fixture-task',1700099999000);
"""
    finish = "UPDATE market_control_hold SET activated_at=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED) WHERE tenant_id=1 AND task_id='fixture-task'; COMMIT;"
    for attempt in range(2):
        # Explicit fault injection: 100k rows alone reproduce the scan, not production's 16-40s load.
        try:
            sql(begin + query(False) + "; DO SLEEP(15.1); " + finish)
        except RuntimeError as error:
            (output / f"fence-before-{attempt}.log").write_text(str(error), encoding="utf-8")
            assert "ENGINE_FENCED" in str(error), "Expected the original production hold trigger to reject an expired writer"
        else:
            raise AssertionError("Expired writer unexpectedly committed")
        assert sql(snapshot).strip() == previous, "Final sample/task/hold changes did not roll back"
    sql(begin + query(True) + "; " + finish)
    committed = sql(snapshot).strip().split("\t")
    assert committed[:3] == ["COMPLETED", "1700099999000", "1"] and int(committed[3]) > 0
    return {"triggerFromProductionMigration": True, "leaseMilliseconds": 15000,
            "injectedDelaySeconds": 15.1, "expiredAttemptsRolledBack": 2,
            "rollbackStatus": previous, "fixedCompletionCommitted": True,
            "fullApplicationEndToEnd": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    name = "control-timeout-repro-" + uuid.uuid4().hex[:12]
    owner = uuid.uuid4().hex
    password = secrets.token_hex(16)
    container = docker("run", "-d", "--name", name, "--network", "none", "--memory", "1g", "--cpus", "2",
                       "--label", "codex.control-timeout-owner=" + owner, "--env", "MYSQL_ROOT_PASSWORD=" + password,
                       "mysql:5.7", "--innodb-use-native-aio=0").strip()

    def sql(source):
        return docker("exec", "-i", container, "sh", "-c",
                      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B --raw', data=(source+"\n").encode())

    result = {"container": container, "name": name, "owner": owner, "rowsPerLedger": 100000,
              "applicationDatabaseAccess": False, "externalNetwork": False}
    try:
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            ready = subprocess.run(["docker", "exec", container, "sh", "-c",
                                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e "SELECT 1"'], capture_output=True)
            if ready.returncode == 0:
                break
            state = json.loads(docker("inspect", container))[0]["State"]
            if not state["Running"]:
                result["startupFailureState"] = state
                (output / "startup.log").write_text(docker("logs", container), encoding="utf-8")
                raise RuntimeError("Owned MySQL exited during startup; see startup.log")
            time.sleep(1)
        else:
            (output / "startup.log").write_text(docker("logs", container), encoding="utf-8")
            result["lastReadinessError"] = ready.stderr.decode(errors="replace")
            raise RuntimeError("Owned MySQL did not become ready")
        sql("""CREATE DATABASE repro;
USE repro;
CREATE TABLE market_source_event(tenant_id BIGINT NOT NULL,event_sequence BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(64) NOT NULL,symbol_id BIGINT NOT NULL,source_time BIGINT NOT NULL,received_at BIGINT NOT NULL,price DECIMAL(32,16) NOT NULL,KEY source_event_time(symbol_id,source_time,received_at),KEY mt_source_event_received(tenant_id,symbol_id,received_at,event_sequence,source_time));
CREATE TABLE market_source_tick(tenant_id BIGINT NOT NULL,symbol_id BIGINT NOT NULL,source_time BIGINT NOT NULL,received_at BIGINT NOT NULL,price DECIMAL(32,16) NOT NULL,PRIMARY KEY(tenant_id,symbol_id,source_time),KEY mt_source_tick_received(tenant_id,symbol_id,received_at,source_time));
CREATE TABLE digit(n INT PRIMARY KEY);
INSERT INTO digit VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price)
SELECT 1,CONCAT('mirror-',n),61,1700000000000+n*1000,1700000000000+n*1000+1,158+n/100000
FROM (SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n n FROM digit a CROSS JOIN digit b CROSS JOIN digit c CROSS JOIN digit d CROSS JOIN digit e) numbers;
INSERT INTO market_source_tick SELECT tenant_id,symbol_id,source_time,received_at,price FROM market_source_event;
ANALYZE TABLE market_source_event,market_source_tick;""")
        for label, fixed in (("before", False), ("after", True)):
            statement = query(fixed)
            explain = sql("USE repro; EXPLAIN " + statement + ";")
            receipt = sql("USE repro; SET @started=NOW(6); " + statement + "; SELECT TIMESTAMPDIFF(MICROSECOND,@started,NOW(6))/1000; SHOW SESSION STATUS LIKE 'Handler_read%';")
            (output / (label + ".tsv")).write_text(explain + "\nMEASUREMENT\n" + receipt, encoding="utf-8")
            lines = receipt.strip().splitlines()
            handlers = dict(line.split("\t") for line in lines[2:])
            result[label] = {"latest": lines[0], "milliseconds": float(lines[1]),
                             "handlerReads": sum(int(value) for value in handlers.values()), "explain": explain}
        assert result["before"]["latest"] == result["after"]["latest"], "Latest price/source time changed"
        assert result["before"]["handlerReads"] > 100000, "Mirrored anti-join scan was not reproduced"
        assert result["after"]["handlerReads"] < 100, "Fixed lookup still scans historical mirrors"
        result["fence"] = reproduce_fence(sql, output)
        result["passed"] = True
    finally:
        # Remove only the exact disposable container created above, never a shared/business container.
        inspected = json.loads(docker("inspect", container))[0]
        if inspected["Id"] != container or inspected["Config"]["Labels"].get("codex.control-timeout-owner") != owner:
            raise RuntimeError("Cleanup identity mismatch")
        docker("rm", "-f", "-v", container)
        result["ownedContainerRemoved"] = True
        (output / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in result.items() if key not in ("before", "after")}, indent=2))
    print(json.dumps({label: {key: value for key, value in result[label].items() if key != "explain"}
                      for label in ("before", "after")}, indent=2))


if __name__ == "__main__":
    main()
