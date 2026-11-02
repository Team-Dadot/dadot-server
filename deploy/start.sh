#!/usr/bin/env bash
set +x
set -euo pipefail

[[ "$EUID" -eq 0 ]] || { printf '%s\n' "sudo bash deploy/start.sh 로 실행하세요." >&2; exit 1; }
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd -- "$SCRIPT_DIR/.."

# All checks below finish before Compose can replace a running container.
bash "$SCRIPT_DIR/configure.sh" check
[[ -f /etc/dadot/rds-ca.pem ]] || { printf '%s\n' "RDS CA 파일이 없습니다." >&2; exit 1; }
[[ -d /var/lib/dadot/originals ]] || { printf '%s\n' "공시 원문 보관 폴더가 없습니다." >&2; exit 1; }
docker compose -f compose.bootstrap.yaml config --quiet
docker image inspect "${DADOT_IMAGE:-dadot-server:bootstrap}" --format '{{.Id}}' > /dev/null

docker compose -f compose.bootstrap.yaml up -d --force-recreate --no-build --wait --wait-timeout 180
docker compose -f compose.bootstrap.yaml ps
curl --fail --silent --show-error --max-time 10 http://127.0.0.1:8080/actuator/health/readiness
printf '\n'