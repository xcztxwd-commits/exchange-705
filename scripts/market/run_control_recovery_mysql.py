"""Owned loopback MySQL 5.7 application/JDBC acceptance. Never connects to production."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET


def docker(*args, data=None, timeout=120):
    result = subprocess.run(["docker", *args], input=data, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors="replace"))
    return (result.stdout + (result.stderr if args[0] == "logs" else b"")).decode(errors="replace")


def atomic_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2), encoding="utf-8")
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--maven-root", help="Existing ASCII junction to this same working tree")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    maven_root = Path(args.maven_root).resolve() if args.maven_root else root
    if maven_root != root:
        raise ValueError("Maven root must resolve to the same existing working tree")
    output = Path(args.output).absolute()
    output.mkdir(parents=True, exist_ok=False)
    owner, run = str(uuid.uuid4()), "control-recovery-" + secrets.token_hex(8)
    name = "mt705-" + run + "-mysql"
    password, account_password = secrets.token_hex(24), secrets.token_hex(24)
    database = "mt705_control_recovery_" + secrets.token_hex(8)
    with socket.socket() as free:
        free.bind(("127.0.0.1", 0))
        port = free.getsockname()[1]
    container = None
    result = {"run": run, "owner": owner, "applicationJdbcAcceptance": True,
              "productionSecurityAcceptance": False, "millionRowLoadAcceptance": False,
              "skipped": ["production authentication/JPA repository wiring", "million-row sustained load/p99", "production container recreation/rollback"]}
    try:
        container = docker("run", "-d", "--name", name, "--memory", "768m", "--cpus", "1",
                           "--label", "com.gtcfesk.multitenant.test=true",
                           "--label", "com.gtcfesk.control-recovery.owner=" + owner,
                           "--label", "com.gtcfesk.control-recovery.run=" + run,
                           "-p", f"127.0.0.1:{port}:3306", "-e", "MYSQL_ROOT_PASSWORD=" + password,
                           "mysql:5.7", "--innodb-use-native-aio=0", "--max-connections=50").strip()
        if len(container) != 64:
            raise ValueError("Full owned container ID required")
        def sql(text):
            return docker("exec", "-i", "-e", "MYSQL_PWD=" + password, container,
                          "mysql", "-h127.0.0.1", "--protocol=TCP", "-uroot", "--batch", "--skip-column-names", data=text.encode())
        deadline = time.monotonic() + 120
        while True:
            try:
                sql("SELECT 1;")
                break
            except RuntimeError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(1)
        resources = root / "exchange-backend/src/main/resources/db/migration"
        schema = (root / "exchange-backend/src/test/resources/multitenant-market-test.sql").read_text(encoding="utf-8")
        # Start at the old application schema, then execute the exact forward-only release ALTERs.
        for columns in (" retry_count INT NOT NULL DEFAULT 0,retry_at BIGINT NOT NULL DEFAULT 0,\n",
                        ",\n history_pending_until BIGINT NULL,history_retry_at BIGINT NULL,history_error VARCHAR(64) NULL"):
            if schema.count(columns) != 1:
                raise ValueError("Expected additive fixture column block changed; review baseline upgrade setup")
            schema = schema.replace(columns, "", 1)
        # Native legacy command text is latin1; UTF-8-only fixtures missed durable Chinese receipts.
        schema, count = re.subn(r"(CREATE TABLE IF NOT EXISTS market_control_command\(.*?\) ENGINE=InnoDB);", r"\1 DEFAULT CHARSET=latin1 COLLATE=latin1_swedish_ci;", schema, flags=re.S)
        if count != 1:
            raise ValueError("Expected one explicit native latin1 command table")
        fences = (resources / "V2026100304__market_engine_runtime.sql").read_text(encoding="utf-8").split("DELIMITER $$", 1)[1]
        constraints = (resources / "V2026100305__s2_runtime_tenant_constraints.sql").read_text(encoding="utf-8").split("INSERT INTO tenant_schema_version", 1)[0]
        # Reuse test table mirror, all unchanged formal engine fences, and formal runtime/command FKs.
        setup = f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; USE `{database}`;\n"
        setup += "CREATE TABLE tenant(id BIGINT PRIMARY KEY,status VARCHAR(16),config_ready BOOLEAN); INSERT INTO tenant VALUES(1,'ACTIVE',1),(2,'ACTIVE',1);\n"
        # Exact formal metadata table and local 0603 baseline, not a production receipt.
        setup += "CREATE TABLE tenant_schema_version(version BIGINT NOT NULL PRIMARY KEY,applied_at DATETIME(6) NOT NULL,minimum_application_epoch BIGINT NOT NULL,business_activation_ready BIT(1) NOT NULL DEFAULT b'0') ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;\n"
        setup += "INSERT INTO tenant_schema_version VALUES(2026100603,UTC_TIMESTAMP(6),2026100603,0);\n"
        setup += schema + "\nALTER TABLE trading_symbol ADD UNIQUE KEY fixture_tenant_symbol(tenant_id,id);\nDELIMITER $$" + fences + constraints
        setup += f"\nCREATE USER 'control_recovery'@'%' IDENTIFIED BY '{account_password}'; GRANT ALL ON `{database}`.* TO 'control_recovery'@'%';\n"
        sql(setup)
        use = f"USE `{database}`; "
        sql(use + "INSERT INTO trading_symbol(id,tenant_id) VALUES(900000000000000001,1); "
            "INSERT INTO market_control_command(tenant_id,id,symbol_id,request_key,parameter_hash,parameters_json,state,actor_id,config_revision,control_revision,seed,accepted_at,expires_at) "
            "VALUES(1,'fixture-upgrade-cancelled',900000000000000001,'fixture-upgrade-cancelled',REPEAT('0',64),'{\"action\":\"CANCEL\"}','CANCELLED',7,0,0,0,0,0);")
        trigger_query = use + "SELECT CONCAT(trigger_name,'|',action_timing,'|',event_manipulation,'|',action_statement) FROM information_schema.triggers WHERE trigger_schema=DATABASE() ORDER BY trigger_name;"
        trigger_before = hashlib.sha256(sql(trigger_query).encode()).hexdigest()
        old_receipt = sql(use + "SELECT id,tenant_id,symbol_id,request_key,parameter_hash,parameters_json,state,actor_id,config_revision,control_revision,seed,accepted_at,expires_at FROM market_control_command WHERE id='fixture-upgrade-cancelled';")
        applied = []
        for filename in ("V2026100701__control_command_retry.sql", "V2026100702__control_history_finalization.sql"):
            migration = resources / filename
            sql(use + migration.read_text(encoding="utf-8"))
            applied.append({"file": filename, "sha256": hashlib.sha256(migration.read_bytes()).hexdigest()})
        trigger_after = hashlib.sha256(sql(trigger_query).encode()).hexdigest()
        if trigger_before != trigger_after or old_receipt != sql(use + "SELECT id,tenant_id,symbol_id,request_key,parameter_hash,parameters_json,state,actor_id,config_revision,control_revision,seed,accepted_at,expires_at FROM market_control_command WHERE id='fixture-upgrade-cancelled';"):
            raise AssertionError("Forward-only migrations changed old receipt or existing isolation triggers")
        if sql(use + "SELECT retry_count,retry_at FROM market_control_command WHERE id='fixture-upgrade-cancelled';").strip() != "0\t0":
            raise AssertionError("New retry fields must preserve old terminal receipt defaults")
        if sql(use + "SELECT version,minimum_application_epoch,business_activation_ready+0 FROM tenant_schema_version ORDER BY version;").strip() != "2026100603\t2026100603\t0\n2026100702\t2026100603\t0":
            raise AssertionError("Additive 0702 must retain inactive 0603-compatible receipt without changing baseline")
        command_columns = sql(use + "SELECT column_name,character_set_name,collation_name,column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='market_control_command' AND character_set_name IS NOT NULL ORDER BY ordinal_position;").strip().splitlines()
        if not command_columns or any("\tlatin1\tlatin1_swedish_ci\t" not in column for column in command_columns):
            raise AssertionError("Every native command textual column must remain latin1")
        if "message\tlatin1\tlatin1_swedish_ci\tvarchar(255)" not in command_columns:
            raise AssertionError("Native command message limit must remain VARCHAR(255)")
        result["nativeCommandTextColumns"] = command_columns
        result["legacyLatin1CommandFixture"] = True
        result.update({"migrationsApplied": applied, "existingTriggersUnchanged": True,
                       "triggersBeforeSha256": trigger_before, "triggersAfterSha256": trigger_after,
                       "oldCancelledReceiptPreserved": True, "inactiveEpoch0702MinimumApplication0603": True})
        fixture = {"kind": "OWNED_CONTROL_RECOVERY_MYSQL_57", "owner": owner, "run": run, "containerId": container,
                   "name": name, "port": port, "database": database, "serverUuid": sql("SELECT @@server_uuid;").strip(),
                   "url": f"jdbc:mysql://127.0.0.1:{port}/{database}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8",
                   "username": "control_recovery", "password": account_password}
        identity = output / "fixture.private.json"
        atomic_json(identity, fixture)
        migration_hash = hashlib.sha256((resources / "V2026100304__market_engine_runtime.sql").read_bytes()).hexdigest()
        result.update({"serverUuid": fixture["serverUuid"], "containerId": container,
                       "engineMigrationSha256": migration_hash, "engineTriggerCount": int(sql(f"SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='{database}' AND trigger_name LIKE 's2_%';").strip())})
        environment = os.environ.copy()
        environment["CONTROL_RECOVERY_FIXTURE"] = str(identity)
        environment["CONTROL_RECOVERY_OUTPUT"] = str(output)
        started = time.time()
        command = [shutil.which("mvn.cmd") or "mvn", "-B", "-f", "exchange-backend/pom.xml", "-Dtest=ControlRecoveryMysqlTest,ControlRecoveryTransportMysqlTest,ControlRecoveryFreshClaimMysqlTest", "test"]
        with (output / "application.log").open("w", encoding="utf-8") as log:
            completed = subprocess.run(command, cwd=args.maven_root or root, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=300)
        result["exitCode"] = completed.returncode
        reports = output / "surefire"
        reports.mkdir()
        totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        cases = []
        expected = {"com.gtcfesk.exchange.market.ControlRecoveryMysqlTest", "com.gtcfesk.exchange.market.ControlRecoveryTransportMysqlTest", "com.gtcfesk.exchange.market.ControlRecoveryFreshClaimMysqlTest"}
        observed = set()
        for report in (root / "exchange-backend/target/surefire-reports").glob("*ControlRecovery*MysqlTest*"):
            if report.stat().st_mtime >= started - 1:
                shutil.copy2(report, reports / report.name)
                if report.suffix == ".xml":
                    suite = ET.parse(report).getroot()
                    observed.add(suite.attrib["name"])
                    for key in totals:
                        totals[key] += int(suite.attrib.get(key, "0"))
                    for case in suite.findall("testcase"):
                        outcome = "FAIL" if case.find("failure") is not None or case.find("error") is not None else "SKIP" if case.find("skipped") is not None else "PASS"
                        cases.append({"class": case.attrib["classname"], "name": case.attrib["name"], "result": outcome, "seconds": float(case.attrib.get("time", "0"))})
        result["tests"] = totals
        result["scenarios"] = cases
        result["expectedSuites"] = sorted(expected)
        result["observedSuites"] = sorted(observed)
        result["result"] = "PASS" if completed.returncode == 0 and observed == expected and totals["tests"] > 0 and not any(totals[key] for key in ("failures", "errors", "skipped")) else "FAIL"
    except BaseException as error:
        result.update({"result": "FAIL", "error": str(error)})
        raise
    finally:
        if container:
            row = json.loads(docker("inspect", "--type", "container", container))[0]
            labels = row["Config"]["Labels"]
            if row["Id"] != container or row["Name"] != "/" + name or labels.get("com.gtcfesk.control-recovery.owner") != owner or labels.get("com.gtcfesk.control-recovery.run") != run:
                result["cleanup"] = "REFUSED: owned identity changed"
            else:
                (output / "container.log").write_text(docker("logs", container), encoding="utf-8")
                docker("rm", "-f", "-v", container)
                result["cleanup"] = "removed exact owned container and anonymous fixture volume"
        atomic_json(output / "result.json", result)
        print(json.dumps(result, indent=2))
    return 0 if result["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
