#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

die() { printf '%s\n' "$*" >&2; exit 1; }
[[ "$EUID" -eq 0 ]] || die "root 권한으로 실행하세요."

RELEASE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_ROOT=/home/ubuntu/dadot-deploy
STATE_DIR=/var/lib/dadot/deploy
IMAGE="${1:-}"

[[ "$IMAGE" =~ ^ghcr\.io/team-dadot/dadot-server@sha256:[a-f0-9]{64}$ ]] \
    || die "다닷 GHCR 이미지의 digest 주소가 필요합니다."
[[ "$RELEASE_DIR" =~ ^/home/ubuntu/dadot-deploy/releases/[0-9]+-[0-9]+$ ]] \
    || die "배포 폴더 경로를 확인하세요."

install -d -o root -g root -m 0700 "$STATE_DIR"
exec 9>"$STATE_DIR/deploy.lock"
flock -w 900 9 || die "다른 배포가 진행 중입니다."

cd -- "$RELEASE_DIR"
bash -n deploy/configure.sh deploy/start.sh
bash deploy/configure.sh check
[[ -f /etc/dadot/rds-ca.pem ]] || die "RDS 인증서가 없습니다."
[[ -d /var/lib/dadot/originals ]] || die "공시 원문 보관 폴더가 없습니다."
DADOT_IMAGE="$IMAGE" docker compose -f compose.bootstrap.yaml config --quiet

previous_image=''
previous_dir=''
container_ids="$(docker ps -aq \
    --filter label=com.docker.compose.project=dadot-bootstrap \
    --filter label=com.docker.compose.service=app)"
containers=()
if [[ -n "$container_ids" ]]; then
    mapfile -t containers <<< "$container_ids"
fi
(( ${#containers[@]} <= 1 )) || die "기존 앱 컨테이너가 여러 개입니다. 수동 확인이 필요합니다."
if (( ${#containers[@]} == 1 )); then
    previous_image="$(docker inspect --format '{{.Image}}' "${containers[0]}")"
    previous_dir="$(docker inspect --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "${containers[0]}")"
    [[ "$previous_image" =~ ^sha256:[a-f0-9]{64}$ ]] || die "이전 이미지 ID를 확인할 수 없습니다."
    if [[ "$previous_dir" != "$DEPLOY_ROOT" \
        && ! "$previous_dir" =~ ^/home/ubuntu/dadot-deploy/releases/[0-9]+-[0-9]+$ ]]; then
        die "이전 배포 폴더 경로를 확인하세요."
    fi
    [[ -f "$previous_dir/compose.bootstrap.yaml" \
        && -f "$previous_dir/deploy/start.sh" ]] || die "이전 배포 설정이 없습니다."
fi

# 다운로드와 환경 검사는 실행 중인 컨테이너를 교체하기 전에 완료합니다.
timeout 300 docker pull "$IMAGE"
docker image inspect "$IMAGE" > /dev/null

if DADOT_IMAGE="$IMAGE" bash deploy/start.sh; then
    record="$(mktemp "$STATE_DIR/.current.XXXXXX")"
    printf '%s\n%s\n' "$RELEASE_DIR" "$IMAGE" > "$record"
    mv -- "$record" "$STATE_DIR/current"
    printf '%s\n' "배포 성공: $IMAGE"
else
    printf '%s\n' "새 버전 기동 실패. DB 마이그레이션은 자동으로 되돌리지 않습니다." >&2
    if [[ -n "$previous_image" ]]; then
        if DADOT_IMAGE="$previous_image" bash "$previous_dir/deploy/start.sh"; then
            printf '%s\n' "이전 이미지와 배포 설정으로 복구했습니다." >&2
        else
            printf '%s\n' "이전 버전 복구도 실패했습니다. EC2에서 상태를 확인하세요." >&2
        fi
    else
        printf '%s\n' "복구할 이전 컨테이너가 없습니다." >&2
    fi
    exit 1
fi