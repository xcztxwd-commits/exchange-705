"""Stage2 owns a new run; stage1-compatible resource labels are NOT prior stage1 resources."""
import argparse
import json
import secrets
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/stage1"))
import environment as fixtures


def money_fixture(run):
    home = run / "fixture" / ("stage1-" + run.name.removeprefix("stage2-"))
    spec = json.loads((home / "environment.json").read_text(encoding="utf-8"))
    migration = fixtures.migration
    private = home / "private"
    connection_file = private / ("money-connection-" + secrets.token_hex(4) + ".json")
    source = migration.Database(spec["containers"]["mysql"], spec.get("moneySourceDatabase",spec["database"]))
    if "moneySourceDatabase" in spec:
        proofpath=Path(spec["moneySourceProof"]).resolve()
        if run.resolve() not in proofpath.parents or migration.file_hash(proofpath)!=spec["moneySourceProofSha256"]:
            raise RuntimeError("Immutable baseline proof ownership/hash mismatch")
        proof=json.loads(proofpath.read_text(encoding="utf-8"))
        if (not source.test or proof["result"]!="PASS" or proof["runId"]!=spec["run"]
                or proof["baselineDatabase"]!=source.database or proof["baselineContainerId"]!=source.identity["container_id"]
                or proof["baselineServerUuid"]!=source.query("select @@server_uuid")[0]
                or proof["independentRestore"]["result"]!="PASS"
                or migration.fingerprint(source,proof["fingerprintAllTables"]["columns"])!=proof["fingerprintAllTables"]):
            raise RuntimeError("Immutable independently restored baseline changed; never reset business data")
    backup = source.dump(private / ("money-source-" + secrets.token_hex(4) + ".sql"))
    db = migration.Database(source.container, "mt705_probe_stage2_money_" + secrets.token_hex(4))
    db.create_empty()
    db.sql(Path(backup["path"]).read_text(encoding="utf-8"))
    db.sql("INSERT INTO tenant(id,code,name,status,created_at) VALUES"
           "(2,'stage2_money_a','Stage2 money A','MAINTENANCE',UTC_TIMESTAMP(6)),"
           "(3,'stage2_money_b','Stage2 money B','MAINTENANCE',UTC_TIMESTAMP(6))")
    # Required extra synthetic initial funds, not an edit to the inherited USDT/legacy deposit facts.
    db.sql("INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version) "
           "VALUES(1,7000001,'FUND',25,0,0)")
    before = migration.fingerprint(db)
    backup = db.dump(private / (db.database + "-before.sql"))
    restore = migration.Database(spec["containers"]["restore_mysql"], db.database + "_restore")
    restore.create_empty()
    restore.sql(Path(backup["path"]).read_text(encoding="utf-8"))
    if migration.fingerprint(restore, before["columns"]) != before:
        raise RuntimeError("Stage2 independently restored money fixture mismatch")
    fields = "SELECT @@server_uuid,@@port,@@datadir"
    uuid, port, directory = db.query(fields)[0].split("\t")
    restore_uuid = restore.query(fields)[0].split("\t")[0]
    proof = {"result": "PASS", "source": {
        "test_instance": True, "database": db.database, "server_uuid": uuid, "port": int(port),
        "datadir": directory, "container": db.container, "container_id": db.identity["container_id"],
        "run_id": spec["run"]}, "backup": backup, "original_records_unchanged": True,
        "initialTestFunds": [{"tenant": 1, "syntheticUser": 7000001, "coin": "FUND", "available": "25", "frozen": "0"}],
        "independent_restore_before_ddl": {"result": "PASS", "fingerprint_equal": True, "target": {
            "server_uuid": restore_uuid, "database": restore.database, "container": restore.container,
            "container_id": restore.identity["container_id"], "run_id": spec["run"]}}}
    proof_path = run / (db.database + "-fixture-proof.json")
    fixtures.save(proof_path, proof)
    username, password = "mt705_" + secrets.token_hex(4), secrets.token_hex(24)
    db.sql("CREATE USER '" + username + "'@'%' IDENTIFIED BY '" + password
           + "'; GRANT ALL PRIVILEGES ON " + migration.ident(db.database) + ".* TO '" + username + "'@'%';")
    fixtures.save(connection_file, {
        "url": "jdbc:mysql://127.0.0.1:" + str(spec["mysqlPort"]) + "/" + db.database
               + "?useSSL=false&useUnicode=true&characterEncoding=utf8&serverTimezone=UTC",
        "username": username, "password": password, "database": db.database, "container": db.container,
        "fixtureTransport": "docker", "containerName": db.container, "containerId": db.identity["container_id"],
        "serverUuid": uuid, "runId": spec["run"], "fixtureProof": str(proof_path),
        "fixtureProofSha256": migration.file_hash(proof_path)})
    fixtures.save(run / "money-fixture-current.json", {"connectionFile": str(connection_file),
        "proof": str(proof_path), "stage": 2, "ownershipCompatibility": "new stage1-script namespace inside this stage2 run"})
    print("Stage2 new money fixture independently restored; inherited historical rows unchanged")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["money-fixture"])
    parser.add_argument("--run-dir", type=Path, required=True)
    args = parser.parse_args()
    run = args.run_dir.resolve()
    if ROOT / "reports" not in run.parents or not run.name.startswith("stage2-"):
        raise SystemExit("Explicit stage2 workspace report directory required")
    money_fixture(run)
