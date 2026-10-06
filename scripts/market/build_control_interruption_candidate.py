"""Build a fresh UNRELEASED local backend only; reuse, never overwrite, earlier admin/rollback images."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import uuid
import zipfile


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def docker(*args):
    run = subprocess.run(["docker", *args], capture_output=True, timeout=300)
    if run.returncode:
        raise RuntimeError(run.stderr.decode(errors="replace")[-4000:])
    return run.stdout + (run.stderr if args[0] in ("start", "build") else b"")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    evidence, out = Path(args.evidence).absolute(), Path(args.output).absolute()
    stages = json.loads((evidence / "result.json").read_text(encoding="utf-8-sig"))
    assert {stage["stage"] for stage in stages} >= {"backend", "client", "browser", "mysql-application"}
    assert all(stage.get("exitCode") == 0 and not stage.get("skipped") for stage in stages)
    mysql = json.loads((evidence / "mysql-application/result.json").read_text())
    assert mysql["result"] == "PASS" and mysql["tests"]["tests"] >= 42
    assert not any(mysql["tests"][key] for key in ("errors", "failures", "skipped"))
    old_folder = root / "rollback/control-recovery-20261007/release-candidate-verified"
    old = json.loads((old_folder / "manifest.json").read_text())
    assert digest(old_folder / "images.tar") == old["imageArchiveSha256"]
    old_sources = json.loads((old_folder / "sources.json").read_text())["sha256"]
    sources = {p.relative_to(root).as_posix(): digest(p) for folder in [root / "exchange-backend/src/main", root / "exchange-admin/src"] for p in sorted(folder.rglob("*")) if p.is_file()}
    admin_sources = {p: h for p, h in sources.items() if p.startswith("exchange-admin/src/")}
    assert admin_sources == {p: h for p, h in old_sources.items() if p.startswith("exchange-admin/src/")}, "Do not reuse an admin image for changed source"
    tested = json.loads((evidence / "tested-build.json").read_text())
    assert sources == tested["sources"], "Application source changed after the tested build snapshot"
    out.mkdir(parents=True, exist_ok=False)
    backend = out / "backend"
    backend.mkdir()
    source = root / "exchange-backend/target/exchange-backend-0.0.1-SNAPSHOT.jar"
    assert source.stat().st_mtime >= (evidence / "result.json").stat().st_mtime, "Package after final test evidence"
    shutil.copy2(source, backend / "app.jar")
    sha = digest(backend / "app.jar")
    with zipfile.ZipFile(backend / "app.jar") as jar:
        major = max(int.from_bytes(jar.read(n)[6:8], "big") for n in jar.namelist() if n.startswith("BOOT-INF/classes/") and n.endswith(".class"))
        assert major <= 52
        assert "BOOT-INF/classes/com/gtcfesk/exchange/market/MarketEngineFailure.class" in jar.namelist()
        assert {name[len("BOOT-INF/classes/"):] for name in jar.namelist() if name.startswith("BOOT-INF/classes/") and name.endswith(".class")} == set(tested["compiledArtifacts"]), "Packaged class set differs from tested build"
        for name, expected in tested["compiledArtifacts"].items():
            entry = "BOOT-INF/classes/" + name
            assert hashlib.sha256(jar.read(entry)).hexdigest() == expected, "Packaged artifact differs from tested classes: " + name
        epoch = jar.read("META-INF/mt705-schema-epoch").decode().strip()
        assert epoch == old["packagedSchemaEpoch"] == "2026100603", "Keep unapproved epoch changes blocked"
        migrations = {name: hashlib.sha256(jar.read("BOOT-INF/classes/db/migration/" + name)).hexdigest() for name in old["migrations"]}
        assert migrations == old["migrations"], "Unreviewed migration drift"
    base = old["images"]["backend"]["base"]
    tag = "exchange-705-backend:control-interruptions-20261007-" + sha[:16]
    assert subprocess.run(["docker", "image", "inspect", tag], capture_output=True).returncode != 0, "Never replace an existing candidate"
    (backend / "Dockerfile").write_bytes(("FROM " + base + "\nWORKDIR /app\nCOPY app.jar /app/app.jar\nLABEL org.opencontainers.image.description=\"UNRELEASED interruption-tested local candidate\"\nEXPOSE 8080\nENTRYPOINT [\"java\",\"-Xmx512m\",\"-jar\",\"/app/app.jar\"]\n").encode())
    (out / "backend-build.log").write_bytes(docker("build", "--pull=false", "--network=none", "-t", tag, str(backend)))
    image = json.loads(docker("image", "inspect", tag))[0]
    checks, owner = [], str(uuid.uuid4())
    for number in range(2):
        cid = docker("create", "--network", "none", "--label", "codex.control-recovery-verifier=" + owner, "--entrypoint", "sh", image["Id"], "-c", "sha256sum /app/app.jar && java -version").decode().strip()
        try:
            result = docker("start", "-a", cid)
            state = json.loads(docker("inspect", cid))[0]
            assert state["State"]["ExitCode"] == 0 and result.decode().split()[0] == sha
            (out / ("backend-recreate-" + str(number + 1) + ".log")).write_bytes(result)
            checks.append({"recreation": number + 1, "imageId": image["Id"], "artifactSha256": sha, "result": "PASS", "scope": "artifact persistence and Java runtime, NOT complete Spring Boot startup"})
        finally:
            state = json.loads(docker("inspect", cid))[0]
            assert state["Id"] == cid and state["Config"]["Labels"]["codex.control-recovery-verifier"] == owner
            docker("rm", "-f", "-v", cid)
    images = {"backend": {"imageId": image["Id"], "tag": tag, "artifactSha256": sha, "base": base}}
    for role in ["admin", "rollback-backend"]:
        info = old["images"][role]
        assert json.loads(docker("image", "inspect", info["imageId"]))[0]["Id"] == info["imageId"]
        images[role] = dict(info, reusedFrom="rollback/control-recovery-20261007/release-candidate-verified")
    manifest = {"state": "UNRELEASED", "deploymentReady": False, "productionMutated": False, "publicationAttempted": False,
                "gates": old["gates"], "images": images, "tests": mysql["tests"], "evidence": str(evidence), "classMaximumMajor": major,
                "supersedesBackendImage": old["images"]["backend"]["imageId"], "recreationChecks": checks, "sources": sources,
                "testedBuildSha256": digest(evidence / "tested-build.json"), "packagedClassesMatchTestedBuild": True,
                "packagedSchemaEpoch": epoch, "migrations": migrations,
                "inheritedGatesFromManifestSha256": digest(old_folder / "manifest.json"),
                "reusedAdminSourceFingerprintSha256": hashlib.sha256(json.dumps(admin_sources, sort_keys=True).encode()).hexdigest(),
                "reusedAdminAndRollbackArchive": {"path": str(old_folder / "images.tar"), "sha256": old["imageArchiveSha256"], "notContainedInNewBackendArchive": True}}
    archive = out / "backend-image.tar.next"
    docker("image", "save", "-o", str(archive), tag)
    os.replace(archive, out / "backend-image.tar")
    manifest["backendArchiveSha256"] = digest(out / "backend-image.tar")
    temporary = out / "manifest.json.tmp"
    temporary.write_bytes((json.dumps(manifest, indent=2) + "\n").encode())
    os.replace(temporary, out / "manifest.json")
    print(json.dumps({"state": "UNRELEASED", "deploymentReady": False, "backend": images["backend"], "tests": mysql["tests"], "recreationChecks": len(checks)}))


if __name__ == "__main__":
    main()
