# -*- coding: utf-8 -*-
import os, paramiko
def load_deploy_env():
    cfg = {}
    path = os.environ.get("DATANOTE_DEPLOY_ENV", "deploy.env")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as fp:
            for line in fp:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                cfg[k.strip()] = v.strip().strip('"').strip("'")
    return cfg
def deploy_creds():
    cfg = load_deploy_env()
    host = os.environ.get("DEPLOY_HOST") or cfg.get("DEPLOY_HOST")
    user = os.environ.get("DEPLOY_USER") or cfg.get("DEPLOY_USER")
    pwd = os.environ.get("DEPLOY_PASSWORD") or cfg.get("DEPLOY_PASSWORD")
    port = int(os.environ.get("DEPLOY_PORT") or cfg.get("DEPLOY_PORT") or "22")
    if not host or not user or not pwd:
        raise SystemExit("Missing DEPLOY_HOST/DEPLOY_USER/DEPLOY_PASSWORD; set env vars or local deploy.env")
    return host, user, pwd, port
HOST, USER, PWD, PORT = deploy_creds()
def run(c, cmd, t=120):
    _, so, se = c.exec_command(cmd, timeout=t)
    return so.read().decode("utf-8","replace").strip(), se.read().decode("utf-8","replace").strip()
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, port=PORT, username=USER, password=PWD, timeout=30)

print("=== BEFORE ===")
o,_ = run(c, "systemctl is-active datanote"); print("datanote:", o)
o,_ = run(c, "docker ps -a --format '{{.Names}}|{{.State}}|{{.Status}}' 2>/dev/null"); print("docker:\n"+(o or "(no docker / none)"))

print("=== START datanote ===")
o,e = run(c, "systemctl start datanote 2>&1; sleep 2; systemctl is-active datanote"); print(o, e)

print("=== START stopped docker containers ===")
# start any container not running
o,_ = run(c, "for n in $(docker ps -a --filter status=exited --format '{{.Names}}') $(docker ps -a --filter status=created --format '{{.Names}}'); do echo starting $n; docker start $n; done 2>&1")
print(o or "(none stopped)")

print("=== AFTER ===")
import time
act=""
for _ in range(20):
    time.sleep(3); act,_ = run(c,"systemctl is-active datanote")
    if act=="active": break
http="000"
for _ in range(20):
    http,_ = run(c,"curl -s -o /dev/null -w '%{http_code}' localhost:8099")
    if http!="000": break
    time.sleep(3)
print("datanote active=%s http=%s" % (act, http))
o,_ = run(c, "docker ps --format '{{.Names}}|{{.Status}}' 2>/dev/null"); print("running docker:\n"+(o or "(none)"))
# port checks
o,_ = run(c, "ss -lntp 2>/dev/null | grep -E ':(8099|3307|9030|5432|1433|1521)' | awk '{print $4}' | sort -u"); print("listening ports:\n"+(o or "(none of interest)"))
c.close(); print(">> DONE")
