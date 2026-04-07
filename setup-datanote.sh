#!/bin/bash
# ============================================================
# 第二步：安装 DataNote
# 前提：已运行 setup-hive.sh 或已有 Hadoop + Hive 环境
#
# 使用：chmod +x setup-datanote.sh && ./setup-datanote.sh
# 停止：./setup-datanote.sh stop
# ============================================================

set -e

# ---------- 配置区 ----------
DATANOTE_PORT=8099

# MySQL 配置（与 setup-hive.sh 保持一致）
DB_HOST=127.0.0.1
DB_PORT=3306
DB_USER=root
DB_PASS=datanote123

# ---------- 颜色输出 ----------
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR_FILE="$SCRIPT_DIR/target/datanote-1.0.0.jar"
PID_FILE="/tmp/datanote.pid"

# ---------- 停止 ----------
if [ "$1" = "stop" ]; then
  if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 $PID 2>/dev/null; then
      kill $PID
      info "DataNote 已停止 (PID: $PID)"
    else
      warn "进程 $PID 不存在"
    fi
    rm -f "$PID_FILE"
  else
    # 按端口查找
    PID=$(lsof -ti:$DATANOTE_PORT 2>/dev/null | head -1)
    if [ -n "$PID" ]; then
      kill $PID
      info "DataNote 已停止 (PID: $PID)"
    else
      warn "DataNote 未在运行"
    fi
  fi
  exit 0
fi

# ---------- 前置检查 ----------
# 检查 Java
if ! command -v java &>/dev/null; then
  error "请先安装 Java 8+：https://adoptium.net/"
  exit 1
fi

JAVA_VER=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d. -f1)
if [ "$JAVA_VER" = "1" ]; then
  JAVA_VER=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | cut -d. -f2)
fi
info "Java 版本: $JAVA_VER"

# 检查 MySQL 连通性
info "检查 MySQL 连接..."
if command -v mysql &>/dev/null; then
  mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "SELECT 1;" &>/dev/null
  if [ $? -eq 0 ]; then
    info "MySQL 连接正常"
  else
    error "MySQL 连接失败（$DB_HOST:$DB_PORT），请检查配置"
    exit 1
  fi
else
  warn "mysql 客户端未安装，跳过连接检查"
fi

# ---------- 检查端口占用 ----------
EXISTING_PID=$(lsof -ti:$DATANOTE_PORT 2>/dev/null | head -1)
if [ -n "$EXISTING_PID" ]; then
  warn "端口 $DATANOTE_PORT 已被占用 (PID: $EXISTING_PID)"
  read -p "是否停止旧进程？[Y/n] " answer
  if [ "$answer" != "n" ] && [ "$answer" != "N" ]; then
    kill $EXISTING_PID
    sleep 2
    info "旧进程已停止"
  else
    error "端口被占用，退出"
    exit 1
  fi
fi

# ---------- 编译（如果 JAR 不存在）----------
if [ ! -f "$JAR_FILE" ]; then
  info "JAR 包不存在，开始编译..."
  if ! command -v mvn &>/dev/null; then
    error "JAR 包不存在且 Maven 未安装，请先编译或下载 JAR 包"
    echo "  编译：mvn package -DskipTests"
    echo "  或从 GitHub Release 下载 JAR 放到 $SCRIPT_DIR/target/ 目录"
    exit 1
  fi
  cd "$SCRIPT_DIR"
  mvn package -DskipTests -q
  info "编译完成"
fi

# ---------- 初始化数据库（首次运行）----------
if command -v mysql &>/dev/null; then
  DB_EXISTS=$(mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -N -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='datanote';" 2>/dev/null)
  if [ "$DB_EXISTS" = "0" ] || [ -z "$DB_EXISTS" ]; then
    info "首次运行，初始化数据库..."
    mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "CREATE DATABASE IF NOT EXISTS datanote DEFAULT CHARACTER SET utf8mb4;" 2>/dev/null
    mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS datanote < "$SCRIPT_DIR/sql/init-all.sql" 2>/dev/null
    info "数据库初始化完成"
  else
    info "数据库已存在（${DB_EXISTS} 张表），跳过初始化"
  fi
fi

# ---------- 启动 DataNote ----------
info "启动 DataNote..."
cd "$SCRIPT_DIR"
nohup java \
  -Xms512m -Xmx1g \
  -jar "$JAR_FILE" \
  --spring.datasource.url="jdbc:mysql://${DB_HOST}:${DB_PORT}/datanote?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true" \
  --spring.datasource.username=$DB_USER \
  --spring.datasource.password=$DB_PASS \
  > /tmp/datanote.log 2>&1 &

echo $! > "$PID_FILE"
info "DataNote 启动中... (PID: $(cat $PID_FILE))"

# 等待启动
echo -n "等待服务就绪"
for i in $(seq 1 20); do
  if curl -sf http://localhost:${DATANOTE_PORT}/ &>/dev/null; then
    echo ""
    break
  fi
  sleep 2
  echo -n "."
done

# ==================== 完成 ====================
echo ""
echo "============================================"
info "DataNote 启动成功！"
echo ""
echo "  访问地址：http://localhost:${DATANOTE_PORT}"
echo "  日志文件：/tmp/datanote.log"
echo ""
echo "  下一步：打开浏览器 → 系统管理 → 数据源管理"
echo "  配置 Hive 连接信息，测试通过后保存即可。"
echo ""
echo "  停止：./setup-datanote.sh stop"
echo "============================================"
