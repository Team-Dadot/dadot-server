#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${DADOT_ENV_FILE:-/etc/dadot/app.env}"
REQUIRED_FILE="${DADOT_REQUIRED_FILE:-$SCRIPT_DIR/env.required}"

die() {
    printf '%s\n' "$*" >&2
    exit 1
}

require_root() {
    [[ "$EUID" -eq 0 ]] || die "sudo bash deploy/configure.sh 로 실행하세요."
}

read_config() {
    [[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || die "환경 설정 파일이 없거나 심볼릭 링크입니다."
    [[ "$(stat -c '%u' "$ENV_FILE")" == 0 ]] || die "환경 설정 파일의 소유자는 root여야 합니다."
    local permissions line key value
    permissions="$(stat -c '%a' "$ENV_FILE")"
    (( (8#$permissions & 077) == 0 )) || die "환경 설정 파일은 root 전용 권한(600 또는 400)이어야 합니다."
    declare -gA CONFIG=()
    while IFS= read -r line || [[ -n "$line" ]]; do
        [[ -z "$line" || "$line" == \#* ]] && continue
        [[ "$line" != *$'\r'* ]] || die "환경 설정 파일을 LF 줄바꿈으로 저장하세요."
        [[ "$line" =~ ^([A-Z][A-Z0-9_]*)=(.*)$ ]] || die "환경 설정 파일의 KEY=value 형식을 확인하세요."
        key="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        [[ -z "${CONFIG[$key]+present}" ]] || die "중복 환경변수: $key"
        CONFIG["$key"]="$value"
    done < "$ENV_FILE"
    [[ -f "$REQUIRED_FILE" ]] || die "필수 변수 목록이 없습니다."
    while IFS= read -r key || [[ -n "$key" ]]; do
        [[ -z "$key" || "$key" == \#* ]] && continue
        [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || die "필수 변수 목록 형식을 확인하세요."
        [[ -n "${CONFIG[$key]:-}" ]] || die "필수 환경변수 누락: $key"
    done < "$REQUIRED_FILE"
}

check_key() {
    local key="$1" encoded bytes canonical
    encoded="${CONFIG[$key]}"
    [[ "$encoded" =~ ^[A-Za-z0-9+/]+={0,2}$ ]] || die "Base64 형식 오류: $key"
    (( ${#encoded} % 4 == 0 )) || die "Base64 길이 오류: $key"
    bytes="$(printf '%s' "$encoded" | openssl base64 -d -A | wc -c)"
    canonical="$(printf '%s' "$encoded" | openssl base64 -d -A | openssl base64 -A)"
    [[ "$canonical" == "$encoded" ]] || die "Base64 형식 오류: $key"
    if [[ "$key" == AUTH_PROOF_ENCRYPTION_KEY ]]; then
        (( bytes == 32 )) || die "32바이트 키가 필요합니다: $key"
    else
        (( bytes >= 32 )) || die "32바이트 이상 키가 필요합니다: $key"
    fi
}

check_config() {
    local mode="$1" key value
    read_config
    [[ "${CONFIG[DADOT_CONFIG_VERSION]}" == 1 ]] || die "지원하지 않는 설정 버전입니다. 코드의 env.required와 변경 사항을 확인하세요."
    [[ "${CONFIG[DB_URL]}" == jdbc:mariadb://* ]] || die "DB_URL 형식을 확인하세요."
    [[ "${CONFIG[DB_URL]}" == *"sslMode=verify-full"* ]] || die "DB_URL에 sslMode=verify-full이 필요합니다."
    [[ "${CONFIG[DB_URL]}" == *"serverSslCert=/run/certs/rds-ca.pem"* ]] || die "DB 인증서 경로를 확인하세요."
    [[ "${CONFIG[MAIL_PORT]}" =~ ^[0-9]{1,5}$ ]] || die "MAIL_PORT 형식을 확인하세요."
    (( 10#${CONFIG[MAIL_PORT]} >= 1 && 10#${CONFIG[MAIL_PORT]} <= 65535 )) || die "MAIL_PORT 범위를 확인하세요."
    for key in JWT_SECRET_BASE64 AUTH_PROOF_ENCRYPTION_KEY AUTH_VERIFICATION_HMAC_SECRET; do
        check_key "$key"
    done
    for key in PRIVACY_POLICY_URL INQUIRY_FORM_URL; do
        value="${CONFIG[$key]}"
        [[ "$value" =~ ^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]+)?([/?#][^[:space:]]*)?$ ]] || die "절대 HTTPS URL이 필요합니다: $key"
        if [[ "$mode" == release && "$value" == https://example.invalid/* ]]; then
            die "공개 전 실제 URL로 변경해야 합니다: $key"
        fi
    done
    printf '%s\n' "환경 설정 형식 확인 완료. 값은 출력하지 않았습니다."
    if [[ "$mode" == bootstrap ]]; then
        printf '%s\n' "현재는 내부 기동 확인 단계입니다. 실제 URL과 외부 기능 검증은 공개 전에 완료해야 합니다."
    fi
}

initialize() {
    require_root
    [[ "$ENV_FILE" == /etc/dadot/app.env ]] || die "init은 /etc/dadot/app.env에만 생성합니다."
    [[ ! -e "$ENV_FILE" && ! -L "$ENV_FILE" ]] || die "기존 app.env를 보존했습니다. 수정은 sudoedit, 확인은 check 명령을 사용하세요."
    [[ -f /home/ubuntu/ap-northeast-2-bundle.pem ]] || die "앞서 다운로드한 RDS 인증서가 없습니다."
    local db_password mail_user mail_password mail_from tmp
    read -r -s -p "dadot_app DB 비밀번호: " db_password
    printf '\n'
    [[ -n "$db_password" ]] || die "DB 비밀번호가 비어 있습니다."
    read -r -p "Gmail SMTP 사용자 이메일: " mail_user
    [[ "$mail_user" == *@*.* && "$mail_user" != *" "* ]] || die "Gmail 이메일 형식을 확인하세요."
    read -r -s -p "Gmail 앱 비밀번호(일반 로그인 비밀번호 아님): " mail_password
    printf '\n'
    mail_password="${mail_password// /}"
    [[ -n "$mail_password" ]] || die "SMTP 비밀번호가 비어 있습니다."
    read -r -p "메일 발신 주소(Enter: 위 이메일 사용): " mail_from
    mail_from="${mail_from:-$mail_user}"
    [[ "$mail_from" == *@*.* && "$mail_from" != *" "* ]] || die "발신 이메일 형식을 확인하세요."

    install -d -o root -g root -m 0755 /etc/dadot
    install -d -o 10001 -g 10001 -m 0750 /var/lib/dadot/originals
    install -o root -g root -m 0644 /home/ubuntu/ap-northeast-2-bundle.pem /etc/dadot/rds-ca.pem
    tmp="$(mktemp /etc/dadot/.app.env.XXXXXX)"
    trap 'rm -f -- "$tmp"' EXIT

    {
        printf '%s\n' 'DADOT_CONFIG_VERSION=1'
        printf '%s\n' 'DB_URL=jdbc:mariadb://dadot-db-prod.cl22kouek0ek.ap-northeast-2.rds.amazonaws.com:3306/dadot_db?sslMode=verify-full&serverSslCert=/run/certs/rds-ca.pem'
        printf '%s\n' 'DB_USERNAME=dadot_app'
        printf 'DB_PASSWORD=%s\n' "$db_password"
        printf 'JWT_SECRET_BASE64=%s\n' "$(openssl rand -base64 32)"
        printf 'AUTH_PROOF_ENCRYPTION_KEY=%s\n' "$(openssl rand -base64 32)"
        printf 'AUTH_VERIFICATION_HMAC_SECRET=%s\n' "$(openssl rand -base64 32)"
        printf '%s\n' 'MAIL_HOST=smtp.gmail.com' 'MAIL_PORT=587'
        printf 'MAIL_USERNAME=%s\n' "$mail_user"
        printf 'MAIL_PASSWORD=%s\n' "$mail_password"
        printf 'MAIL_FROM=%s\n' "$mail_from"
        printf '%s\n' 'PRIVACY_POLICY_URL=https://example.invalid/privacy'
        printf '%s\n' 'INQUIRY_FORM_URL=https://example.invalid/inquiry'
    } > "$tmp"
    chmod 0600 "$tmp"
    ENV_FILE="$tmp" check_config bootstrap
    mv -- "$tmp" /etc/dadot/app.env
    trap - EXIT
    unset db_password mail_password
    printf '%s\n' "/etc/dadot/app.env 생성 완료. 키는 재배포 때 다시 생성하지 않습니다."
}

case "${1:-}" in
    init) initialize ;;
    check) require_root; check_config bootstrap ;;
    check-release) require_root; check_config release ;;
    *) die "사용법: sudo bash deploy/configure.sh {init|check|check-release}" ;;
esac