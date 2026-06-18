#!/bin/bash
# DataX helper for DataNote -> Doris sync jobs.

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CONF_FILE="$SCRIPT_DIR/datanote.conf"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

if [ ! -f "$CONF_FILE" ]; then
  error "Config file not found: $CONF_FILE"
  exit 1
fi

source "$CONF_FILE"

CONTAINER_NAME="datanote-datax"
NETWORK=${NETWORK:-datanote-net}
DATAX_IMAGE=${DATAX_IMAGE:-beginor/datax:latest}
MYSQL_DRIVER_VERSION=${MYSQL_DRIVER_VERSION:-8.0.33}
MYSQL_DRIVER_URL=${MYSQL_DRIVER_URL:-https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/${MYSQL_DRIVER_VERSION}/mysql-connector-j-${MYSQL_DRIVER_VERSION}.jar}

if [ "$1" = "status" ]; then
  if docker ps --format '{{.Names}}' | grep -q $CONTAINER_NAME; then
    info "DataX container is running"
    docker ps --filter "name=$CONTAINER_NAME" --format "table {{.Names}}\t{{.Status}}"
  elif docker ps -a --format '{{.Names}}' | grep -q $CONTAINER_NAME; then
    warn "DataX container exists but is stopped"
  else
    info "DataX container is not installed"
  fi
  exit 0
fi

if [ "$1" = "stop" ]; then
  if docker ps --format '{{.Names}}' | grep -q $CONTAINER_NAME; then
    docker stop $CONTAINER_NAME >/dev/null
    docker rm $CONTAINER_NAME >/dev/null
    info "DataX stopped"
  else
    warn "DataX is not running"
  fi
  sed -i '' "s/^DATAX_MODE=.*/DATAX_MODE=local/" "$CONF_FILE" 2>/dev/null || \
  sed -i "s/^DATAX_MODE=.*/DATAX_MODE=local/" "$CONF_FILE"
  exit 0
fi

if [ "$1" != "start" ]; then
  echo "Usage:"
  echo "  ./setup-datax.sh start"
  echo "  ./setup-datax.sh stop"
  echo "  ./setup-datax.sh status"
  exit 1
fi

if ! docker info &>/dev/null; then
  error "Docker is not running"
  exit 1
fi

docker network inspect $NETWORK &>/dev/null || docker network create $NETWORK >/dev/null

if docker ps -a --format '{{.Names}}' | grep -q $CONTAINER_NAME; then
  docker stop $CONTAINER_NAME >/dev/null 2>&1 || true
  docker rm $CONTAINER_NAME >/dev/null 2>&1 || true
fi

info "Starting DataX container..."
docker image inspect $DATAX_IMAGE >/dev/null 2>&1 || docker pull $DATAX_IMAGE
docker run -d \
  --name $CONTAINER_NAME \
  --network $NETWORK \
  --add-host=host.docker.internal:host-gateway \
  --hostname datax \
  -v /tmp/datax_jobs:/tmp/datax_jobs \
  --entrypoint tail \
  --restart unless-stopped \
  $DATAX_IMAGE \
  -f /dev/null

sleep 2
if docker ps --format '{{.Names}}' | grep -q $CONTAINER_NAME; then
  info "DataX container started"
else
  error "Failed to start DataX container"
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
info "Installing MySQL Connector/J ${MYSQL_DRIVER_VERSION} for DataX..."
if command -v curl >/dev/null 2>&1; then
  curl -fsSL "$MYSQL_DRIVER_URL" -o "$TMP_DIR/mysql-connector-j.jar"
elif command -v wget >/dev/null 2>&1; then
  wget -q "$MYSQL_DRIVER_URL" -O "$TMP_DIR/mysql-connector-j.jar"
else
  warn "curl/wget not found; keeping bundled DataX MySQL driver"
fi

if [ -f "$TMP_DIR/mysql-connector-j.jar" ]; then
  docker exec $CONTAINER_NAME sh -c \
    'rm -f /opt/datax/plugin/reader/mysqlreader/libs/mysql-connector*.jar /opt/datax/plugin/writer/mysqlwriter/libs/mysql-connector*.jar'
  docker cp "$TMP_DIR/mysql-connector-j.jar" \
    "$CONTAINER_NAME:/opt/datax/plugin/reader/mysqlreader/libs/mysql-connector-j-${MYSQL_DRIVER_VERSION}.jar"
  docker cp "$TMP_DIR/mysql-connector-j.jar" \
    "$CONTAINER_NAME:/opt/datax/plugin/writer/mysqlwriter/libs/mysql-connector-j-${MYSQL_DRIVER_VERSION}.jar"
fi

docker exec $CONTAINER_NAME python /opt/datax/bin/datax.py -h >/dev/null 2>&1 || \
  warn "DataX verification failed; check the container image if sync jobs fail"

sed -i '' "s/^DATAX_MODE=.*/DATAX_MODE=docker/" "$CONF_FILE" 2>/dev/null || \
sed -i "s/^DATAX_MODE=.*/DATAX_MODE=docker/" "$CONF_FILE"

info "DataX is ready. Doris target: ${DORIS_HOST:-<not configured>}:${DORIS_QUERY_PORT:-9030}"
