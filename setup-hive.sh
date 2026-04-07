#!/bin/bash
# ============================================================
# 第一步：安装 Hadoop + Hive 环境（Docker）
# 启动顺序：MySQL → HDFS(NameNode + DataNode) → Hive(Metastore + HiveServer2)
#
# 使用：chmod +x setup-hive.sh && ./setup-hive.sh
# 停止：./setup-hive.sh stop
# 清理：./setup-hive.sh clean
# 验证：./setup-hive.sh test
# ============================================================

set -e

# ---------- 配置区（可按需修改）----------
NETWORK="datanote-net"
MYSQL_PASSWORD="root"
MYSQL_PORT=3306
HIVE_PORT=10000
HDFS_WEB_PORT=9870

# ---------- 颜色输出 ----------
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

# ---------- 停止 ----------
if [ "$1" = "stop" ]; then
  info "停止 Hive 环境..."
  for c in datanote-hiveserver2 datanote-metastore datanote-datanode datanote-namenode datanote-mysql; do
    docker stop $c 2>/dev/null && docker rm $c 2>/dev/null && info "已停止 $c" || true
  done
  info "全部停止"
  exit 0
fi

# ---------- 清理 ----------
if [ "$1" = "clean" ]; then
  info "停止并清理所有容器和数据..."
  for c in datanote-hiveserver2 datanote-metastore datanote-datanode datanote-namenode datanote-mysql; do
    docker stop $c 2>/dev/null && docker rm $c 2>/dev/null || true
  done
  docker volume rm datanote-mysql-data datanote-namenode-data datanote-datanode-data 2>/dev/null || true
  docker network rm $NETWORK 2>/dev/null || true
  info "清理完成"
  exit 0
fi

# ---------- 测试连接 ----------
if [ "$1" = "test" ]; then
  info "测试 HiveServer2 连接..."
  docker exec datanote-hiveserver2 beeline -u 'jdbc:hive2://localhost:10000/default;auth=noSasl' -e 'SHOW DATABASES;' 2>/dev/null
  if [ $? -eq 0 ]; then
    info "HiveServer2 连接正常！"
    echo ""
    echo "  连接信息（配置 DataNote 时使用）："
    echo "  ─────────────────────────────────"
    echo "  HiveServer2:  localhost:${HIVE_PORT}"
    echo "  认证方式:      NOSASL"
    echo "  HDFS NameNode: hdfs://localhost:8020"
    echo "  MySQL:         localhost:${MYSQL_PORT} (root / ${MYSQL_PASSWORD})"
    echo ""
    info "Hive 环境就绪，可以运行 ./setup-datanote.sh 安装 DataNote"
  else
    error "HiveServer2 连接失败，请检查容器日志：docker logs datanote-hiveserver2"
  fi
  exit 0
fi

# ---------- 前置检查 ----------
if ! command -v docker &>/dev/null; then
  error "请先安装 Docker：https://docs.docker.com/get-docker/"
  exit 1
fi

if ! docker info &>/dev/null; then
  error "Docker 未启动，请先启动 Docker Desktop"
  exit 1
fi

# ---------- MySQL JDBC 驱动 ----------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JDBC_JAR="$SCRIPT_DIR/docker/mysql-connector-j-8.0.33.jar"
if [ ! -f "$JDBC_JAR" ]; then
  info "下载 MySQL JDBC 驱动..."
  mkdir -p "$SCRIPT_DIR/docker"
  curl -sSL -o "$JDBC_JAR" \
    "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar"
  info "驱动已下载"
fi

# ---------- 创建网络 ----------
docker network inspect $NETWORK &>/dev/null || {
  info "创建 Docker 网络: $NETWORK"
  docker network create $NETWORK
}

# ---------- 等待工具 ----------
wait_for() {
  local name=$1 cmd=$2 max=$3
  local i=0
  while [ $i -lt $max ]; do
    if eval "$cmd" &>/dev/null; then
      info "$name 就绪"
      return 0
    fi
    sleep 3
    i=$((i+1))
    echo -n "."
  done
  echo ""
  warn "$name 等待超时，继续执行..."
  return 1
}

# ==================== 1. MySQL ====================
# 检查端口占用
if lsof -i:${MYSQL_PORT} &>/dev/null; then
  warn "端口 ${MYSQL_PORT} 已被占用！"
  warn "可能你本地已经安装了 MySQL。请先停止本地 MySQL 或修改脚本顶部的 MYSQL_PORT 变量。"
  echo ""
  echo "  查看占用：lsof -i:${MYSQL_PORT}"
  echo "  Mac 停止 MySQL：brew services stop mysql"
  echo "  或修改本脚本第 16 行：MYSQL_PORT=3307"
  echo ""
  error "退出部署"
  exit 1
fi

if docker ps -a --format '{{.Names}}' | grep -q datanote-mysql; then
  info "MySQL 已存在，跳过"
else
  info "启动 MySQL..."
  docker run -d \
    --name datanote-mysql \
    --network $NETWORK \
    --hostname mysql \
    -p ${MYSQL_PORT}:3306 \
    -e MYSQL_ROOT_PASSWORD=$MYSQL_PASSWORD \
    -e MYSQL_DATABASE=datanote \
    -v datanote-mysql-data:/var/lib/mysql \
    -v "$SCRIPT_DIR/sql/init-all.sql":/docker-entrypoint-initdb.d/01_init.sql \
    --restart unless-stopped \
    mysql:8.0 \
    --character-set-server=utf8mb4 \
    --collation-server=utf8mb4_unicode_ci \
    --default-authentication-plugin=mysql_native_password

  echo -n "等待 MySQL 启动"
  wait_for "MySQL" "docker exec datanote-mysql mysqladmin ping -h localhost -p$MYSQL_PASSWORD" 20
fi

# ==================== 2. HDFS NameNode ====================
if docker ps -a --format '{{.Names}}' | grep -q datanote-namenode; then
  info "NameNode 已存在，跳过"
else
  info "启动 HDFS NameNode..."
  docker run -d \
    --name datanote-namenode \
    --network $NETWORK \
    --hostname namenode \
    -p ${HDFS_WEB_PORT}:9870 \
    -e ENSURE_NAMENODE_DIR="/tmp/hadoop-hadoop/dfs/name/current" \
    -e CORE-SITE.XML_fs.defaultFS="hdfs://namenode:8020" \
    -e HDFS-SITE.XML_dfs.replication=1 \
    -e HDFS-SITE.XML_dfs.permissions.enabled=false \
    -v datanote-namenode-data:/tmp/hadoop-hadoop/dfs/name \
    --restart unless-stopped \
    apache/hadoop:3 \
    hdfs namenode

  sleep 5
fi

# ==================== 3. HDFS DataNode ====================
if docker ps -a --format '{{.Names}}' | grep -q datanote-datanode; then
  info "DataNode 已存在，跳过"
else
  info "启动 HDFS DataNode..."
  docker run -d \
    --name datanote-datanode \
    --network $NETWORK \
    --hostname datanode \
    -e CORE-SITE.XML_fs.defaultFS="hdfs://namenode:8020" \
    -e HDFS-SITE.XML_dfs.replication=1 \
    -v datanote-datanode-data:/tmp/hadoop-hadoop/dfs/data \
    --restart unless-stopped \
    apache/hadoop:3 \
    hdfs datanode

  echo -n "等待 HDFS 就绪"
  wait_for "HDFS" "docker exec datanote-namenode hdfs dfs -ls /" 20
fi

# 初始化 HDFS 目录
info "初始化 HDFS 目录..."
docker exec datanote-namenode hdfs dfs -mkdir -p /user/hive/warehouse 2>/dev/null || true
docker exec datanote-namenode hdfs dfs -mkdir -p /tmp 2>/dev/null || true
docker exec datanote-namenode hdfs dfs -chmod -R 777 /user/hive/warehouse 2>/dev/null || true
docker exec datanote-namenode hdfs dfs -chmod -R 777 /tmp 2>/dev/null || true

# ==================== 4. Hive Metastore ====================
if docker ps -a --format '{{.Names}}' | grep -q datanote-metastore; then
  info "Hive Metastore 已存在，跳过"
else
  info "启动 Hive Metastore..."
  docker run -d \
    --name datanote-metastore \
    --network $NETWORK \
    --hostname metastore \
    -e SERVICE_NAME=metastore \
    -e DB_DRIVER=mysql \
    -e SERVICE_OPTS="\
-Djavax.jdo.option.ConnectionURL=jdbc:mysql://mysql:3306/hive_metastore?createDatabaseIfNotExist=true&useSSL=false \
-Djavax.jdo.option.ConnectionDriverName=com.mysql.cj.jdbc.Driver \
-Djavax.jdo.option.ConnectionUserName=root \
-Djavax.jdo.option.ConnectionPassword=$MYSQL_PASSWORD" \
    --mount type=bind,source="$JDBC_JAR",target=/opt/hive/lib/mysql-connector-j-8.0.33.jar \
    --restart unless-stopped \
    apache/hive:3.1.3

  echo -n "等待 Hive Metastore 启动"
  sleep 15
  info "Hive Metastore 已启动"
fi

# ==================== 5. HiveServer2 ====================
if docker ps -a --format '{{.Names}}' | grep -q datanote-hiveserver2; then
  info "HiveServer2 已存在，跳过"
else
  info "启动 HiveServer2..."
  docker run -d \
    --name datanote-hiveserver2 \
    --network $NETWORK \
    --hostname hiveserver2 \
    -p ${HIVE_PORT}:10000 \
    -e SERVICE_NAME=hiveserver2 \
    -e IS_RESUME=true \
    -e SERVICE_OPTS="\
-Xms512m -Xmx2g \
-Dhive.metastore.uris=thrift://metastore:9083 \
-Dhive.server2.authentication=NOSASL \
-Dhive.server2.thrift.bind.host=0.0.0.0 \
-Dfs.defaultFS=hdfs://namenode:8020 \
-Dhive.execution.engine=tez \
-Dtez.lib.uris=/opt/tez \
-Dtez.use.cluster.hadoop-libs=true" \
    --restart unless-stopped \
    apache/hive:3.1.3

  echo -n "等待 HiveServer2 就绪"
  wait_for "HiveServer2" "docker exec datanote-hiveserver2 beeline -u 'jdbc:hive2://localhost:10000/default;auth=noSasl' -e 'SELECT 1;'" 30
fi

# 创建数仓分层库
info "创建数仓分层库（ods/dwd/dws/ads/dim）..."
for db in ods dwd dws ads dim; do
  docker exec datanote-hiveserver2 beeline -u 'jdbc:hive2://localhost:10000/default;auth=noSasl' \
    -e "CREATE DATABASE IF NOT EXISTS $db;" 2>/dev/null || true
done

# ==================== 完成 ====================
echo ""
echo "============================================"
info "Hadoop + Hive 环境部署完成！"
echo ""
echo "  HDFS Web UI:  http://localhost:${HDFS_WEB_PORT}"
echo "  HiveServer2:  localhost:${HIVE_PORT}"
echo "  MySQL:        localhost:${MYSQL_PORT} (root / ${MYSQL_PASSWORD})"
echo ""
echo "  验证：./setup-hive.sh test"
echo "  下一步：./setup-datanote.sh"
echo "============================================"
