#!/usr/bin/env bash
#
# One-command local launcher for the GridFS + Atlas Search demo.
#
#   ./run.sh
#
# Idempotent: re-running it reuses the existing Mongo container rather than
# erroring, and never destroys data. Use `./run.sh --reset` if you actually
# want a clean database.
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONGO_CONTAINER="gridfs-mongo"
MONGO_IMAGE="mongodb/mongodb-atlas-local:latest"
# Host port for the demo's MongoDB. Deliberately NOT 27017: a locally installed
# mongod (brew mongodb-community) commonly owns that port, and because Docker
# and a native mongod can both appear to "be" localhost:27017, the app silently
# talks to the wrong server — which presents as a confusing auth failure rather
# than a connection error. 27018 keeps the demo entirely self-contained.
MONGO_PORT="${MONGO_PORT:-27018}"

# The atlas-local image runs a keyfile-secured replica set so mongot can tail
# the oplog, which means auth is enforced. Connections from the host are not
# covered by the localhost exception, so a root user is required.
MONGO_USER="${MONGO_USER:-gridfs}"
MONGO_PASS="${MONGO_PASS:-gridfs}"
JAVA_HOME_CANDIDATE="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"

bold()  { printf '\033[1m%s\033[0m\n' "$*"; }
info()  { printf '  \033[36m•\033[0m %s\n' "$*"; }
ok()    { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn()  { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()   { printf '  \033[31m✗\033[0m %s\n' "$*" >&2; exit 1; }

RESET=false
for arg in "$@"; do
  case "$arg" in
    --reset) RESET=true ;;
    -h|--help)
      sed -n '2,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) die "Unknown argument: $arg (try --help)" ;;
  esac
done

bold "GridFS demo launcher"

# ---------------------------------------------------------------------------
# 1. Java
# ---------------------------------------------------------------------------
if [[ -x "${JAVA_HOME_CANDIDATE}/bin/java" ]]; then
  export JAVA_HOME="${JAVA_HOME_CANDIDATE}"
  ok "JAVA_HOME=${JAVA_HOME}"
elif [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  ok "Using inherited JAVA_HOME=${JAVA_HOME}"
elif command -v /usr/libexec/java_home >/dev/null 2>&1 \
     && JH="$(/usr/libexec/java_home -v 21 2>/dev/null)"; then
  export JAVA_HOME="$JH"
  ok "JAVA_HOME=${JAVA_HOME} (via java_home)"
else
  die "No JDK 21 found. Install one with:  brew install openjdk@21"
fi
export PATH="${JAVA_HOME}/bin:${PATH}"

JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
[[ "${JAVA_MAJOR}" == "21" ]] || warn "Expected Java 21, found ${JAVA_MAJOR:-unknown}"

command -v mvn >/dev/null 2>&1 || die "mvn not on PATH. Install with:  brew install maven"
ok "maven $(mvn -v 2>/dev/null | sed -n '1s/Apache Maven \([0-9.]*\).*/\1/p')"

# ---------------------------------------------------------------------------
# 2. Docker
# ---------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "docker not found. Install Docker Desktop or OrbStack."
docker info >/dev/null 2>&1 \
  || die "Docker is installed but not running. Start Docker Desktop / OrbStack and retry."
ok "Docker daemon is up"

# ---------------------------------------------------------------------------
# 3. MongoDB (atlas-local — gives us a real \$search stage on the laptop)
# ---------------------------------------------------------------------------
container_state() {
  docker inspect -f '{{.State.Status}}' "${MONGO_CONTAINER}" 2>/dev/null || echo "absent"
}

if [[ "${RESET}" == true ]]; then
  warn "--reset: removing container ${MONGO_CONTAINER} and its data"
  docker rm -f "${MONGO_CONTAINER}" >/dev/null 2>&1 || true
fi

case "$(container_state)" in
  running)
    ok "Container ${MONGO_CONTAINER} already running — reusing it"
    ;;
  exited|created|paused)
    info "Container ${MONGO_CONTAINER} exists but is stopped — starting it"
    docker start "${MONGO_CONTAINER}" >/dev/null
    ok "Started ${MONGO_CONTAINER}"
    ;;
  absent)
    if lsof -nP -iTCP:"${MONGO_PORT}" -sTCP:LISTEN >/dev/null 2>&1; then
      warn "Port ${MONGO_PORT} is already in use by something that is not ${MONGO_CONTAINER}."
      warn "Assuming you have your own MongoDB there and skipping container creation."
    else
      info "Creating ${MONGO_CONTAINER} from ${MONGO_IMAGE} (first run pulls ~1 GB)"
      docker run -d \
        --name "${MONGO_CONTAINER}" \
        -p "${MONGO_PORT}:27017" \
        -e MONGODB_INITDB_ROOT_USERNAME="${MONGO_USER}" \
        -e MONGODB_INITDB_ROOT_PASSWORD="${MONGO_PASS}" \
        -v gridfs-mongo-data:/data/db \
        "${MONGO_IMAGE}" >/dev/null
      ok "Created ${MONGO_CONTAINER}"
    fi
    ;;
esac

# ---------------------------------------------------------------------------
# 4. Wait for health — the atlas-local image reports healthy only once mongod
#    AND mongot (the search node) are both answering.
# ---------------------------------------------------------------------------
if docker inspect "${MONGO_CONTAINER}" >/dev/null 2>&1; then
  info "Waiting for MongoDB + search node to become healthy (up to ${HEALTH_TIMEOUT}s)"
  deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
  while :; do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
              "${MONGO_CONTAINER}" 2>/dev/null || echo none)"
    case "${status}" in
      healthy) ok "MongoDB is healthy (mongod + mongot ready)"; break ;;
      none)    warn "Container has no healthcheck; continuing without waiting"; break ;;
      unhealthy)
        warn "Healthcheck reports unhealthy. Last output:"
        docker inspect -f '{{range .State.Health.Log}}{{.Output}}{{end}}' "${MONGO_CONTAINER}" \
          2>/dev/null | tail -n 5 || true
        ;;
    esac
    if (( $(date +%s) >= deadline )); then
      die "Timed out after ${HEALTH_TIMEOUT}s waiting for ${MONGO_CONTAINER}. Check: docker logs ${MONGO_CONTAINER}"
    fi
    sleep 3
  done
fi

# ---------------------------------------------------------------------------
# 5. Run the app
# ---------------------------------------------------------------------------
export MONGODB_URI="${MONGODB_URI:-mongodb://${MONGO_USER}:${MONGO_PASS}@localhost:${MONGO_PORT}/gridfs_demo?authSource=admin&authMechanism=SCRAM-SHA-256&directConnection=true}"
info "MONGODB_URI=${MONGODB_URI}"
bold "Starting Spring Boot — UI at http://localhost:${PORT:-8081}"
echo

cd "${PROJECT_DIR}"
exec mvn spring-boot:run
