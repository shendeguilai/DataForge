#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/.." && pwd)
COMPOSE_FILE="$REPO_ROOT/compose.prod.yml"
ENV_FILE=${DATAFORGE_ENV_FILE:-/etc/dataforge/dataforge.env}

die() {
  echo "错误：$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "缺少命令：$1"
}

load_environment() {
  [[ -f "$ENV_FILE" ]] || die "生产配置不存在：$ENV_FILE"
  # 配置文件由管理员维护，格式与 deploy/prod.env.example 一致。
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a

  : "${POSTGRES_DB:?POSTGRES_DB 未配置}"
  : "${POSTGRES_USER:?POSTGRES_USER 未配置}"
  : "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD 未配置}"
  : "${DATAFORGE_POSTGRES_DIR:?DATAFORGE_POSTGRES_DIR 未配置}"
  : "${DATAFORGE_RUNTIME_DIR_HOST:?DATAFORGE_RUNTIME_DIR_HOST 未配置}"
  : "${DATAFORGE_BACKUP_DIR:?DATAFORGE_BACKUP_DIR 未配置}"
  : "${DATAFORGE_MIGRATION_DIR:?DATAFORGE_MIGRATION_DIR 未配置}"
  : "${DATAFORGE_SECRET:?DATAFORGE_SECRET 未配置}"
  : "${ADMIN_USERNAME:?ADMIN_USERNAME 未配置}"
  : "${ADMIN_PASSWORD:?ADMIN_PASSWORD 未配置}"
  : "${INVITE_CODE:?INVITE_CODE 未配置}"

  PUBLIC_PORT=${PUBLIC_PORT:-8080}
  DATAFORGE_IMAGE_TAG=${DATAFORGE_IMAGE_TAG:-local}
  STATE_FILE=${DATAFORGE_STATE_FILE:-/srv/dataforge/deployed_commit}
  validate_path "$DATAFORGE_POSTGRES_DIR"
  validate_path "$DATAFORGE_RUNTIME_DIR_HOST"
  validate_path "$DATAFORGE_BACKUP_DIR"
  validate_path "$DATAFORGE_MIGRATION_DIR"
  validate_path "$(dirname "$STATE_FILE")"
}

validate_path() {
  local path=$1
  [[ "$path" = /* ]] || die "生产目录必须是绝对路径：$path"
  [[ "$path" != "/" ]] || die "生产目录不能是根目录"
  [[ "$path" != "$REPO_ROOT" && "$path" != "$REPO_ROOT/"* ]] ||
    die "生产数据目录不能放在 Git 仓库中：$path"
}

prepare_directories() {
  mkdir -p -- "$DATAFORGE_POSTGRES_DIR" "$DATAFORGE_RUNTIME_DIR_HOST" \
    "$DATAFORGE_BACKUP_DIR" "$DATAFORGE_MIGRATION_DIR" "$(dirname "$STATE_FILE")"
  if [[ ${EUID:-$(id -u)} -eq 0 ]]; then
    chown -R 10001:10001 "$DATAFORGE_RUNTIME_DIR_HOST"
  fi
}

compose() {
  docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

compose_tagged() {
  local tag=$1
  shift
  DATAFORGE_IMAGE_TAG="$tag" docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" "$@"
}

wait_for_database() {
  local attempts=60
  while (( attempts > 0 )); do
    if compose exec -T db pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
    attempts=$((attempts - 1))
  done
  die "PostgreSQL 未在规定时间内就绪"
}

wait_for_application() {
  local attempts=60
  while (( attempts > 0 )); do
    if curl --fail --silent "http://127.0.0.1:${PUBLIC_PORT}/actuator/health" | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
    attempts=$((attempts - 1))
  done
  return 1
}

database_has_schema() {
  local answer
  answer=$(compose exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" db \
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc "SELECT to_regclass('public.user_accounts') IS NOT NULL")
  [[ "$answer" == "t" ]]
}

current_deployed_commit() {
  if [[ -f "$STATE_FILE" ]]; then
    tr -d '[:space:]' < "$STATE_FILE"
  else
    git -C "$REPO_ROOT" rev-parse HEAD
  fi
}

backup_database() {
  local backup_id=${1:-$(date -u +%Y%m%dT%H%M%SZ)}
  [[ "$backup_id" =~ ^[A-Za-z0-9._-]+$ ]] || die "备份编号包含非法字符"
  local destination="$DATAFORGE_BACKUP_DIR/$backup_id"
  [[ ! -e "$destination" ]] || die "备份已存在：$destination"
  mkdir -p -- "$destination"

  compose exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" db \
    pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom \
    > "$destination/database.dump"
  tar --exclude='*.partial' -czf "$destination/runtime.tar.gz" -C "$DATAFORGE_RUNTIME_DIR_HOST" .

  local deployed_commit flyway_version
  deployed_commit=$(current_deployed_commit)
  flyway_version=$(compose exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" db \
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atqc \
    "SELECT COALESCE(MAX(version), 'none') FROM flyway_schema_history WHERE success" 2>/dev/null || echo none)
  {
    echo "backup_id=$backup_id"
    echo "created_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "git_commit=$deployed_commit"
    echo "image_tag=$deployed_commit"
    echo "flyway_version=$flyway_version"
  } > "$destination/manifest.env"
  (cd "$destination" && sha256sum database.dump runtime.tar.gz manifest.env > SHA256SUMS)
  echo "备份完成：$destination"
}

cleanup_old_backups() {
  local candidate
  while IFS= read -r -d '' candidate; do
    [[ "$candidate" == "$DATAFORGE_BACKUP_DIR/"* ]] || die "拒绝清理意外路径：$candidate"
    rm -rf -- "$candidate"
  done < <(find "$DATAFORGE_BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -name '20*' -mtime +14 -print0)
}

deploy() {
  require_command git
  require_command curl
  [[ -z $(git -C "$REPO_ROOT" status --porcelain) ]] || die "Git 工作区有未提交改动，拒绝部署"
  local previous_commit next_commit
  previous_commit=$(current_deployed_commit)

  git -C "$REPO_ROOT" pull --ff-only
  next_commit=$(git -C "$REPO_ROOT" rev-parse HEAD)
  echo "构建提交：$next_commit"
  compose_tagged "$next_commit" build app
  compose up -d db
  wait_for_database

  compose stop app >/dev/null 2>&1 || true
  if database_has_schema; then
    backup_database "$(date -u +%Y%m%dT%H%M%SZ)-predeploy"
  fi

  if compose_tagged "$next_commit" up -d app && wait_for_application; then
    printf '%s\n' "$next_commit" > "$STATE_FILE"
    cleanup_old_backups
    echo "部署成功：$next_commit"
    return 0
  fi

  echo "新版本启动失败，尝试恢复旧镜像：$previous_commit" >&2
  compose_tagged "$previous_commit" up -d app || true
  wait_for_application || true
  die "部署失败。数据库若已执行不兼容迁移，请使用部署前备份执行 restore"
}

restore_backup() {
  local backup_id=${1:-}
  local confirmation=${2:-}
  [[ -n "$backup_id" && "$confirmation" == "--confirm" ]] ||
    die "用法：prod.sh restore <backup-id> --confirm"
  [[ "$backup_id" =~ ^[A-Za-z0-9._-]+$ ]] || die "备份编号包含非法字符"
  local source="$DATAFORGE_BACKUP_DIR/$backup_id"
  [[ -d "$source" ]] || die "备份不存在：$source"
  (cd "$source" && sha256sum -c SHA256SUMS)
  # shellcheck disable=SC1090
  source "$source/manifest.env"
  [[ -n ${git_commit:-} ]] || die "备份清单缺少 git_commit"
  [[ -z $(git -C "$REPO_ROOT" status --porcelain) ]] || die "Git 工作区有未提交改动，拒绝恢复"

  compose up -d db
  wait_for_database
  compose stop app >/dev/null 2>&1 || true
  compose exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" db \
    pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --no-privileges \
    < "$source/database.dump"

  local safety_path="${DATAFORGE_RUNTIME_DIR_HOST}.before-restore-$(date -u +%Y%m%dT%H%M%SZ)"
  mv -- "$DATAFORGE_RUNTIME_DIR_HOST" "$safety_path"
  mkdir -p -- "$DATAFORGE_RUNTIME_DIR_HOST"
  tar -xzf "$source/runtime.tar.gz" -C "$DATAFORGE_RUNTIME_DIR_HOST"
  if [[ ${EUID:-$(id -u)} -eq 0 ]]; then
    chown -R 10001:10001 "$DATAFORGE_RUNTIME_DIR_HOST"
  fi

  git -C "$REPO_ROOT" switch --detach "$git_commit"
  compose_tagged "$git_commit" build app
  compose_tagged "$git_commit" up -d app
  wait_for_application || die "数据已恢复，但对应应用版本未通过健康检查"
  printf '%s\n' "$git_commit" > "$STATE_FILE"
  echo "恢复完成：$backup_id；恢复前 runtime 保留在 $safety_path"
}

import_h2() {
  [[ -f "$DATAFORGE_MIGRATION_DIR/dataforge.mv.db" ]] ||
    die "请把旧 dataforge.mv.db 放到 $DATAFORGE_MIGRATION_DIR"
  [[ -d "$DATAFORGE_MIGRATION_DIR/runtime" ]] ||
    die "请把旧 runtime 目录放到 $DATAFORGE_MIGRATION_DIR/runtime"
  local tag
  tag=$(git -C "$REPO_ROOT" rev-parse HEAD)
  compose_tagged "$tag" build app
  compose up -d db
  wait_for_database
  compose stop app >/dev/null 2>&1 || true
  DATAFORGE_IMAGE_TAG="$tag" compose --profile tools run --rm importer
  compose_tagged "$tag" up -d app
  wait_for_application || die "迁移完成，但应用未通过健康检查"
  printf '%s\n' "$tag" > "$STATE_FILE"
  echo "H2 数据迁移及应用启动完成"
}

reset_admin_password() {
  local username=${1:-$ADMIN_USERNAME}
  local password confirmation tag
  read -r -s -p "请输入管理员新密码（12～72 位）：" password
  echo
  read -r -s -p "请再次输入：" confirmation
  echo
  [[ "$password" == "$confirmation" ]] || die "两次密码不一致"
  (( ${#password} >= 12 && ${#password} <= 72 )) || die "密码长度必须是 12～72 位"
  tag=$(current_deployed_commit)
  DATAFORGE_ADMIN_RESET_USERNAME="$username" DATAFORGE_ADMIN_RESET_PASSWORD="$password" \
    DATAFORGE_IMAGE_TAG="$tag" compose --profile tools run --rm admin-reset
  unset password confirmation DATAFORGE_ADMIN_RESET_PASSWORD
}

show_status() {
  compose ps
  echo "部署提交：$(current_deployed_commit)"
  curl --fail --silent "http://127.0.0.1:${PUBLIC_PORT}/actuator/health" || true
  echo
}

usage() {
  cat <<'USAGE'
用法：./scripts/prod.sh <command>

  deploy                         拉取、备份、构建并部署最新版
  backup [backup-id]             备份 PostgreSQL、runtime 和版本清单
  restore <backup-id> --confirm  恢复数据库、runtime 和对应代码版本
  import-h2                      从迁移目录导入旧 H2 与历史 ZIP
  reset-admin-password [user]    离线重置管理员密码
  status                         查看容器与健康状态
  logs                           持续查看应用日志
USAGE
}

main() {
  require_command docker
  require_command sha256sum
  require_command tar
  docker compose version >/dev/null 2>&1 || die "需要 Docker Compose v2"
  load_environment
  prepare_directories
  cd "$REPO_ROOT"

  case ${1:-} in
    deploy) deploy ;;
    backup)
      compose up -d db
      wait_for_database
      database_has_schema || die "数据库尚未初始化"
      backup_database "${2:-}"
      cleanup_old_backups
      ;;
    restore) restore_backup "${2:-}" "${3:-}" ;;
    import-h2) import_h2 ;;
    reset-admin-password) reset_admin_password "${2:-}" ;;
    status) show_status ;;
    logs) compose logs -f --tail=200 app ;;
    *) usage; exit 2 ;;
  esac
}

main "$@"
