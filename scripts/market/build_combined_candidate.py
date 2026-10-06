"""Build verified, immutable LOCAL backend/admin/control candidates. Never deploy or grant approval."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid
import zipfile
from build_control_interruption_candidate import digest, docker


def files(folder):
    return {p.relative_to(folder).as_posix(): digest(p) for p in sorted(folder.rglob("*")) if p.is_file()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verification", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--admin-nginx", type=Path, required=True)
    parser.add_argument("--control-nginx", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root).decode().strip()
    assert not subprocess.check_output(["git", "status", "--porcelain"], cwd=root), "Commit merged source before building a candidate"
    verified = json.loads(args.verification.read_text(encoding="utf-8-sig"))
    assert verified["result"] == "PASS", "Local tests must pass; skipped scopes stay explicit"
    actual = {p.relative_to(root).as_posix(): digest(p) for folder in verified["sourceFolders"] for p in sorted((root / folder).rglob("*")) if p.is_file()}
    actual.update({p: digest(root / p) for p in verified["sourceFiles"]})
    assert actual == verified["sources"], "Source set changed after verification"
    for relative, expected in verified["sources"].items():
        assert digest(root / relative) == expected, "Source changed after verification: " + relative
    for role, record in verified["frontendArtifacts"].items():
        assert files(Path(record["path"])) == record["files"], "Frontend build drift: " + role
    old_folder = root / "rollback/control-recovery-20261007/release-candidate-verified"
    old = json.loads((old_folder / "manifest.json").read_text())
    rollback = old["images"]["rollback-backend"]
    assert json.loads(docker("image", "inspect", rollback["imageId"]))[0]["Id"] == rollback["imageId"]
    assert json.loads(docker("image", "inspect", rollback["tag"]))[0]["Id"] == rollback["imageId"], "Rollback tag drift"
    assert digest(old_folder / "images.tar") == old["imageArchiveSha256"]
    out = args.output.absolute()
    out.mkdir(parents=True, exist_ok=False)
    backend = out / "backend"
    backend.mkdir()
    shutil.copy2(root / "exchange-backend/target/exchange-backend-0.0.1-SNAPSHOT.jar", backend / "app.jar")
    jar_sha = digest(backend / "app.jar")
    inventory = json.loads((root / "scripts/multitenant/table_manifest.json").read_text())
    with zipfile.ZipFile(backend / "app.jar") as jar:
        for relative, expected in verified["sources"].items():
            prefix = "exchange-backend/src/main/resources/"
            if not relative.startswith(prefix):
                continue
            resource = relative[len(prefix):]
            if resource == "db/migration/.gitignore":
                continue  # Maven standard resource excludes; not an application artifact.
            entry = "BOOT-INF/classes/" + resource
            if entry not in jar.namelist() and resource.startswith("META-INF/"):
                entry = resource
            assert hashlib.sha256(jar.read(entry)).hexdigest() == expected, "Packaged resource differs: " + resource
        classes = {n[len("BOOT-INF/classes/"):]: hashlib.sha256(jar.read(n)).hexdigest()
                   for n in jar.namelist() if n.startswith("BOOT-INF/classes/") and n.endswith(".class")}
        assert classes == verified["compiledArtifacts"], "Packaged classes differ from tested build"
        assert all(int.from_bytes(jar.read("BOOT-INF/classes/" + n)[6:8], "big") <= 52 for n in classes)
        epoch = jar.read("META-INF/mt705-schema-epoch").decode().strip()
        assert epoch == str(inventory["schema_epoch"]) == "2026100702"
        migrations = {n: hashlib.sha256(jar.read("BOOT-INF/classes/db/migration/" + n)).hexdigest()
                      for n in inventory["migration_files"]}
        assert all(h == digest(root / "exchange-backend/src/main/resources/db/migration" / n) for n, h in migrations.items())
    base = old["images"]["backend"]["base"]
    (backend / "Dockerfile").write_bytes(("FROM " + base + "\nWORKDIR /app\nCOPY app.jar /app/app.jar\nLABEL org.opencontainers.image.revision=\"" + revision + "\"\nLABEL org.opencontainers.image.description=\"UNRELEASED verified three-chat candidate\"\nEXPOSE 8080\nENTRYPOINT [\"java\",\"-Xmx512m\",\"-jar\",\"/app/app.jar\"]\n").encode())
    contexts = {"backend": backend}
    tags = {"backend": "exchange-705-backend:combined-20261007-" + jar_sha[:16]}
    artifact_hashes = {"backend": jar_sha}
    for role, config in (("admin", args.admin_nginx), ("control", args.control_nginx)):
        context = out / role
        context.mkdir()
        shutil.copytree(Path(verified["frontendArtifacts"][role]["path"]), context / "dist")
        shutil.copy2(config, context / "nginx.conf")
        bundle_sha = hashlib.sha256(json.dumps(files(context / "dist"), sort_keys=True).encode()).hexdigest()
        (context / "Dockerfile").write_bytes(("FROM " + old["images"]["admin"]["base"] + "\nCOPY nginx.conf /etc/nginx/conf.d/default.conf\nCOPY dist /usr/share/nginx/html\nLABEL org.opencontainers.image.revision=\"" + revision + "\"\nLABEL org.opencontainers.image.description=\"UNRELEASED verified three-chat candidate\"\nEXPOSE 80\n").encode())
        contexts[role] = context
        tags[role] = "exchange-705-" + role + ":combined-20261007-" + bundle_sha[:16] + "-" + digest(context / "nginx.conf")[:8]
        artifact_hashes[role] = bundle_sha
    images = {"rollback-backend": dict(rollback, reusedFrom=str(old_folder))}
    checks = []
    owner = str(uuid.uuid4())
    for role, context in contexts.items():
        tag = tags[role]
        assert not docker("image", "ls", "--no-trunc", "--quiet", tag).strip(), "Never replace candidate tags"
        (out / (role + "-build.log")).write_bytes(docker("build", "--pull=false", "--network=none", "-t", tag, str(context)))
        image = json.loads(docker("image", "inspect", tag))[0]["Id"]
        images[role] = {"tag": tag, "imageId": image, "artifactSha256": artifact_hashes[role]}
        for number in (1, 2):
            command = "sha256sum /app/app.jar && java -version" if role == "backend" else "nginx -t && sha256sum /usr/share/nginx/html/index.html"
            cid = docker("create", "--network", "none", "--add-host", "backend:127.0.0.1", "--add-host", "control-api:127.0.0.1", "--label", "codex.combined.verifier=" + owner, "--entrypoint", "sh", image, "-c", command).decode().strip()
            try:
                log = docker("start", "-a", cid)
                state = json.loads(docker("inspect", cid))[0]
                assert state["State"]["ExitCode"] == 0
                (out / (role + "-recreate-" + str(number) + ".log")).write_bytes(log)
                if role == "backend":
                    assert log.decode().split()[0] == jar_sha
                else:
                    copy = out / (role + "-recreated-" + str(number))
                    copy.mkdir()
                    docker("cp", cid + ":/usr/share/nginx/html/.", str(copy))
                    expected = verified["frontendArtifacts"][role]["files"]
                    actual = files(copy)
                    assert all(actual.get(p) == h for p, h in expected.items()), "Recreated bundle differs"
                    assert set(actual) - set(expected) <= {"50x.html"}, "Unexpected base artifact"
                    copied_config = out / (role + "-nginx-recreated-" + str(number) + ".conf")
                    docker("cp", cid + ":/etc/nginx/conf.d/default.conf", str(copied_config))
                    assert digest(copied_config) == digest(context / "nginx.conf")
                checks.append({"role": role, "recreation": number, "result": "PASS", "scope": "artifact persistence/runtime/configuration syntax, NOT full application boot or production acceptance"})
            finally:
                state = json.loads(docker("inspect", cid))[0]
                assert state["Id"] == cid and state["Config"]["Labels"]["codex.combined.verifier"] == owner
                docker("rm", "-f", "-v", cid)
    archive = out / "images.tar.next"
    docker("image", "save", "-o", str(archive), *tags.values(), rollback["imageId"])
    os.replace(archive, out / "images.tar")
    manifest = {"state": "UNRELEASED", "deploymentReady": False, "productionMutated": False,
                "gitRevision": revision, "verificationSha256": digest(args.verification), "images": images, "recreationChecks": checks,
                "packagedSchemaEpoch": epoch, "migrations": migrations, "imageArchiveSha256": digest(out / "images.tar"), "allowedNginxBaseExtraFiles": ["50x.html"],
                "gates": ["Running images and Compose candidates drift", "Independent production approval and backup/restore ledger absent", "Source release gate remains blocked"],
                "reusedRollbackArchiveSha256": old["imageArchiveSha256"]}
    temporary = out / "manifest.json.tmp"
    temporary.write_bytes((json.dumps(manifest, indent=2) + "\n").encode())
    os.replace(temporary, out / "manifest.json")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
