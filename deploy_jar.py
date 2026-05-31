# -*- coding: utf-8 -*-
import paramiko, hashlib, os, time
HOST, USER, PWD = "38.76.183.50", "root", "Cwk@19901121"
LOCAL_JAR = r"target\datanote-1.0.0.jar"
REMOTE_JAR = "/opt/datanote/target/datanote-1.0.0.jar"
CHUNK = 128 * 1024
def run(c, cmd, t=120):
    _, so, se = c.exec_command(cmd, timeout=t)
    return so.read().decode("utf-8","replace").strip(), se.read().decode("utf-8","replace").strip()
size = os.path.getsize(LOCAL_JAR); md5l = hashlib.md5(open(LOCAL_JAR,"rb").read()).hexdigest()
c = paramiko.SSHClient(); c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect(HOST, username=USER, password=PWD, timeout=30)
print(">> local %d md5=%s" % (size, md5l))
run(c, "rm -rf /tmp/dnup && mkdir -p /tmp/dnup")
n = (size + CHUNK - 1)//CHUNK
with open(LOCAL_JAR,"rb") as fp:
    for i in range(n):
        d = fp.read(CHUNK); ch = c.get_transport().open_session()
        ch.exec_command("cat > /tmp/dnup/p%06d" % i); ch.sendall(d); ch.shutdown_write(); ch.recv_exit_status(); ch.close()
        if (i+1)%200==0 or i+1==n: print("   %d/%d"%(i+1,n))
run(c, "cat /tmp/dnup/p* > /tmp/datanote-new.jar")
rmd5,_ = run(c, "md5sum /tmp/datanote-new.jar | cut -d' ' -f1")
if rmd5 != md5l: print("!! MISMATCH "+rmd5); c.close(); raise SystemExit(1)
print(">> upload OK")
ts = time.strftime("%Y%m%d_%H%M%S")
run(c, "mkdir -p /opt/datanote/backups/%s && cp %s /opt/datanote/backups/%s/" % (ts,REMOTE_JAR,ts))
run(c, "mv /tmp/datanote-new.jar %s && rm -rf /tmp/dnup" % REMOTE_JAR)
run(c, "systemctl restart datanote")
print(">> restarted")
act=""
for _ in range(25):
    time.sleep(3); act,_ = run(c,"systemctl is-active datanote")
    if act=="active": break
http="000"
for _ in range(20):
    http,_ = run(c,"curl -s -o /dev/null -w '%{http_code}' localhost:8099")
    if http!="000": break
    time.sleep(3)
pid,_ = run(c,"systemctl show datanote -p MainPID --value")
print(">> active=%s http=%s pid=%s" % (act,http,pid))
c.close(); print(">> DONE")
