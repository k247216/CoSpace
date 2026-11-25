#!/usr/bin/env python3
"""Exercise fresh startup and a prior-JAR upgrade against disposable local databases."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
from contextlib import contextmanager
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "backend/r-pan-api-services"
SCHEMA = "BOOT-INF/classes/sql/db.sql"
MIGRATION = SERVICE / "src/main/resources/sql/migrations/20261123_file_storage_type.sql"
LOCAL = "com.rubin.rpan.storage.local.LocalStorageProcessor"
TEST_ACCOUNT = {"username": "baseline", "password": "BaselineTest371"}


@contextmanager
def process(command, log, env=None):
    with log.open("wb") as output:
        child = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, env=env)
        try:
            yield child
        finally:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()


def wait_ready(child, ready, log):
    for _ in range(60):
        if child.poll() is not None:
            break
        try:
            if ready():
                return
        except (URLError, TimeoutError):
            pass
        time.sleep(0.5)
    raise RuntimeError(f"Service failed to become ready. See {log}")


class Api:
    def __init__(self, port):
        self.base = f"http://127.0.0.1:{port}"
        self.token = None

    def request(self, path, method="GET", data=None, headers=None, binary=False):
        request_headers = {"Content-Type": "application/json"}
        if self.token:
            request_headers["Authorization"] = self.token
        request_headers.update(headers or {})
        body = data if isinstance(data, bytes) or data is None else json.dumps(data).encode()
        request = Request(self.base + path, body, request_headers, method=method)
        with urlopen(request, timeout=15) as response:
            content = response.read()
        if binary:
            return content
        result = json.loads(content)
        assert result["status"] == 0, (path, result.get("message"))
        return result.get("data")

    def post(self, path, data=None):
        return self.request(path, "POST", data)

    def register(self):
        self.post("/user/register", {**TEST_ACCOUNT, "question": "test?", "answer": "yes"})
        self.login()
        return self.request("/user")["rootFileId"]

    def login(self):
        self.token = self.post("/user/login", TEST_ACCOUNT)

    def task(self, root_id, name, content):
        return self.post("/file/upload/tasks", {
            "parentId": root_id, "name": name, "size": len(content),
            "md5": hashlib.md5(content).hexdigest(),
        })

    def send_parts(self, task, content, indices):
        size = task["chunkSize"]
        for index in indices:
            boundary = secrets.token_hex(12)
            body = (
                f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="part"\r\n'
                'Content-Type: application/octet-stream\r\n\r\n'
            ).encode() + content[index * size:(index + 1) * size] + f"\r\n--{boundary}--\r\n".encode()
            self.request(f"{task_path(task)}/chunks/{index}", "POST", body,
                         {"Content-Type": f"multipart/form-data; boundary={boundary}"})

    def download(self, file_id):
        return self.request("/file/download?" + urlencode({"fileId": file_id}), binary=True)

    def quota(self, used, reserved):
        account = self.request("/file/space")
        assert (account["usedBytes"], account["reservedBytes"]) == (used, reserved), account


def task_path(task):
    return "/file/upload/tasks/" + task["uploadId"]


@contextmanager
def application(args, api, work, jar, database, label, storage, jwt):
    env = dict(os.environ,
               SPRING_DATASOURCE_URL=f"jdbc:mysql://127.0.0.1:{args.mysql_port}/{database}"
                                     "?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false",
               SPRING_DATASOURCE_USERNAME=os.environ["RPAN_TEST_MYSQL_USER"],
               SPRING_DATASOURCE_PASSWORD=os.environ["RPAN_TEST_MYSQL_PASSWORD"],
               RPAN_CACHE_REDIS_HOST="127.0.0.1", RPAN_CACHE_REDIS_PORT=str(args.redis_port),
               RPAN_CACHE_REDIS_DATABASE="0", RPAN_CACHE_REDIS_PASSWORD="",
               RPAN_JWT_SECRET=jwt)
    log = work / f"{label}.log"
    command = [
        "java", f"-Duser.home={work}", "-jar", str(jar), "--server.address=127.0.0.1",
        f"--server.port={args.api_port}", "--rpan.cleanup.enabled=false",
        "--rpan.storage.s3.enabled=false", f"--rpan.storage.processor.type={LOCAL}",
        f"--rpan.storage.legacy-type={LOCAL}", f"--rpan.upload.storage-type={LOCAL}",
        f"--rpan.storage.local.root-file-path={storage}",
        f"--rpan.upload.staging-path={storage / '.upload-tasks'}",
        f"--rpan.share.prefix={api.base}/share/",
        f"--logging.file.name={work / (label + '-application.log')}",
    ]
    with process(command, log, env) as app:
        def healthy():
            with urlopen(api.base + "/actuator/health", timeout=1) as response:
                return json.load(response)["status"] == "UP"

        wait_ready(app, healthy, log)
        yield


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prior-jar", required=True, type=Path,
                        help="Release before 20261123_file_storage_type.sql")
    parser.add_argument("--current-jar", type=Path, default=SERVICE / "target/r-pan-api-services.jar")
    parser.add_argument("--mysql-port", type=int, default=3306)
    parser.add_argument("--redis-port", type=int, default=16379)
    parser.add_argument("--api-port", type=int, default=18081)
    args = parser.parse_args()
    schema = {}
    for jar in (args.prior_jar, args.current_jar):
        with ZipFile(jar) as bundle:
            schema[jar] = bundle.read(SCHEMA)
    if b"`storage_type`" in schema[args.prior_jar] or b"`storage_type`" not in schema[args.current_jar]:
        parser.error("Expected a pre-storage-type JAR and a current JAR")
    if not {"RPAN_TEST_MYSQL_USER", "RPAN_TEST_MYSQL_PASSWORD"} <= os.environ.keys():
        parser.error("Set RPAN_TEST_MYSQL_USER and RPAN_TEST_MYSQL_PASSWORD for local scratch databases")
    if args.api_port == args.redis_port:
        parser.error("Use different API and Redis ports")
    for port in (args.api_port, args.redis_port):
        with socket.socket() as server:
            server.settimeout(0.5)
            if server.connect_ex(("127.0.0.1", port)) == 0:
                parser.error(f"Local port {port} is unavailable")

    work = Path(tempfile.mkdtemp(prefix="cospace-release-"))
    print(f"Run logs and synthetic backups: {work}", flush=True)
    mysql_args = ["--no-defaults", "--protocol=TCP", "-h", "127.0.0.1",
                  "-P", str(args.mysql_port), "-u", os.environ["RPAN_TEST_MYSQL_USER"]]
    mysql_env = dict(os.environ, MYSQL_PWD=os.environ["RPAN_TEST_MYSQL_PASSWORD"])
    databases = []

    def sql(statement, database=None):
        args = ["mysql", *mysql_args, "--batch", "--skip-column-names"]
        if database:
            args.append(database)
        return subprocess.check_output(args, input=statement, env=mysql_env).strip()

    def create_db():
        database = "r_pan_cleanup_test_release_" + secrets.token_hex(6)
        sql(f"CREATE DATABASE `{database}` CHARACTER SET utf8mb4".encode())
        databases.append(database)
        return database

    api = Api(args.api_port)
    jwt = base64.b64encode(secrets.token_bytes(32)).decode()
    redis = ["redis-server", "--bind", "127.0.0.1", "--port", str(args.redis_port),
             "--save", "", "--appendonly", "no", "--dir", str(work)]
    storage = work / "storage"
    content = b"Legacy file with a protected share\n"
    recycled = b"Legacy file in the recycle bin\n"
    pending = bytes(range(256)) * 20480
    try:
        fresh_db = create_db()
        sql(schema[args.current_jar], fresh_db)
        with process(redis, work / "redis-fresh.log"):
            with application(args, api, work, args.current_jar, fresh_db, "fresh", work / "fresh-files", jwt):
                api.register()
                api.quota(0, 0)
                print("PASS current JAR starts on a fresh database and initializes quota", flush=True)

        old_db = create_db()
        sql(schema[args.prior_jar], old_db)
        with process(redis, work / "redis-old.log"):
            with application(args, api, work, args.prior_jar, old_db, "prior", storage, jwt):
                root = api.register()
                for name, sample in (("shared.txt", content), ("recycled.txt", recycled)):
                    task = api.task(root, name, sample)
                    api.send_parts(task, sample, [0])
                    done = api.post(task_path(task) + "/complete")
                    assert done["state"] == "COMPLETED"
                    if name == "shared.txt":
                        file_id = str(done["userFileId"])
                    else:
                        recycled_id = str(done["userFileId"])
                api.post("/share", {"shareName": "Baseline share", "shareType": 0,
                                    "shareDayType": 0, "shareFileIds": file_id})
                share = api.request("/shares")[0]
                api.request("/file", "DELETE", {"parentId": root, "fileIds": recycled_id})
                task = api.task(root, "pending.bin", pending)
                assert task["totalChunks"] > 1
                api.send_parts(task, pending, [0])
                api.quota(len(content) + len(recycled), len(pending))

        backup = work / "backup.sql"
        with backup.open("wb") as dump:
            subprocess.run(["mysqldump", *mysql_args, "--single-transaction",
                            "--no-tablespaces", "--set-gtid-purged=OFF", old_db],
                           stdout=dump, env=mysql_env, check=True)
        shutil.copytree(storage, work / "files-backup")
        storage.rename(work / "prior-files")
        shutil.copytree(work / "files-backup", storage)
        restored_db = create_db()
        with backup.open("rb") as dump:
            subprocess.run(["mysql", *mysql_args, restored_db], stdin=dump, env=mysql_env, check=True)
        sql(MIGRATION.read_bytes(), restored_db)
        assert sql(b"SELECT COUNT(*) FROM r_pan_file WHERE storage_type IS NULL", restored_db) == b"2"

        with process(redis, work / "redis-upgrade.log"):
            with application(args, api, work, args.current_jar, restored_db, "upgraded", storage, jwt):
                api.login()
                assert api.download(file_id) == content
                share_token = api.post("/share/code/check", {
                    "shareId": share["shareId"], "shareCode": share["shareCode"],
                })
                assert api.request("/share/file/download?" + urlencode({"fileId": file_id}),
                                   headers={"Share-Token": share_token}, binary=True) == content
                recycle_entries = api.request("/recycles")
                assert recycled_id in {str(row["fileId"]) for row in recycle_entries}, recycle_entries
                api.quota(len(content) + len(recycled), len(pending))
                assert api.request(task_path(task))["uploadedChunks"] == [0]
                api.send_parts(task, pending, range(1, task["totalChunks"]))
                done = api.post(task_path(task) + "/complete")
                assert done["state"] == "COMPLETED"
                assert api.post(task_path(task) + "/complete") == done
                assert api.download(done["userFileId"]) == pending
                api.quota(len(content) + len(recycled) + len(pending), 0)
                api.request("/recycle/restore", "PUT", {"fileIds": recycled_id})
                assert api.download(recycled_id) == recycled
                print("PASS restore and upgrade preserve share, recycle, upload resume and quota", flush=True)

        with process(redis, work / "redis-rollback.log"):
            with application(args, api, work, args.prior_jar, restored_db, "rollback", storage, jwt):
                api.login()
                assert api.download(file_id) == content
                assert api.download(done["userFileId"]) == pending
                api.quota(len(content) + len(recycled) + len(pending), 0)
                print("PASS previous JAR reads the migrated database and new local file", flush=True)
    finally:
        for database in reversed(databases):
            sql(f"DROP DATABASE `{database}`".encode())


if __name__ == "__main__":
    main()
