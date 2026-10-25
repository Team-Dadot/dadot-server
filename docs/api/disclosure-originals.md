# 공시 원문 수집

## 처리 흐름

관리자 수집 요청 → 작업 등록 → OpenDART 목록 조회 →
공시별 원본 ZIP 다운로드 → 본문 텍스트 추출 →
원본 파일 보관 및 DB 저장 → 작업 완료

- 기업별로 수집을 요청하며 워커가 백그라운드에서 처리한다.
- 추출한 본문은 검수 전인 DRAFT 상태로 저장한다.
- 퀴즈 생성과 자동 게시는 이번 기능에 포함하지 않는다.

## 설정

로컬 환경의 .env에 설정한다.
DART_API_KEY는 기존 값을 사용하며 문서에 실제 키를 기록하지 않는다.

```properties
APP_DART_ENABLED=true
APP_DART_WORKER_ENABLED=true
APP_DART_ORIGINALS_ENABLED=true
APP_DART_ORIGINALS_ROOT=/Users/jinyoung/Documents/dadot-data/disclosures
```

원문 수집의 기본값은 비활성이다.
활성화하려면 저장 폴더 설정이 필요하다.

## 관리자 API

ADMIN 권한과 유효한 로그인 세션이 필요하다.

### 수집 요청

POST /v1/admin/companies/{companyId}/disclosure-sync-tasks

- Idempotency-Key 헤더에 새로운 UUID를 전달한다.
- 요청 본문에 fromDate, toDate, reportTypes를 전달한다.
- 202 응답의 taskId로 진행 상태를 확인한다.

### 목록 및 작업 상태 조회

GET /v1/admin/disclosures

- companyId, syncTaskId, page, size로 조회한다.
- 목록에는 본문 대신 sectionCount를 반환한다.

### 본문 조회

GET /v1/admin/disclosures/{disclosureId}/versions/{disclosureVersionId}/sections

- page 기본값: 0
- size 기본값: 50, 최대 100
- 선택한 버전의 본문 절을 순서대로 반환한다.

## 저장 방식

- 원본 ZIP: 설정한 원본 저장 폴더에 보관한다.
- 본문 버전: disclosure_versions에 저장한다.
- 추출 본문과 근거 위치: disclosure_sections에 저장한다.
- 공시 한 건의 본문은 여러 절로 나누어 저장한다.
- 이미지 안의 글자는 OCR로 추출하지 않는다.

## 중복 및 실패 처리

- 동일한 원문과 추출기 버전은 기존 본문 버전을 재사용한다.
- 원문 또는 추출기 버전이 변경되면 새 DRAFT 버전을 생성한다.
- 기존 본문과 근거 절 ID는 보존한다.
- 일시적인 오류는 최초 시도를 포함해 최대 3회 시도한다.
- 실패한 항목이 있어도 성공한 공시는 보존한다.
- 최종 실패 항목이 있으면 작업 상태는 FAILED가 된다.

## 적용 시 참고

- 기존 Flyway V1~V9는 유지하고 V10을 추가한다.
- 테스트 실행은 테스트 DB에만 마이그레이션을 적용한다.
- 과거 목록 수집 작업은 자동 재실행하지 않는다.
- 기존 공시의 원문을 수집하려면 새 Idempotency-Key로 요청한다.
- 로컬 원본 폴더는 영속적으로 보관해야 한다.
- 다중 서버에서는 공유 영속 저장소 또는 별도 저장소 어댑터가 필요하다.