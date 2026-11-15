# 면접 질문 근거의 공시일·대상 기간

`GET /v1/interview-questions/{questionId}`의 `data.evidence[]`에 세 필드를 추가한다. 기존 필드의 이름·값과 근거 순서는 유지한다.

| 응답 필드 | 타입 | 기존 DB 컬럼 |
| --- | --- | --- |
| `publishedAt` | ISO 날짜 문자열 `YYYY-MM-DD` | `disclosures.disclosed_on` |
| `periodStart` | ISO 날짜 문자열 또는 null | `disclosures.period_start` |
| `periodEnd` | ISO 날짜 문자열 또는 null | `disclosures.period_end` |

응답 형태 예시이며 제목·날짜·기간은 실제 조회한 공시 값으로 채운다.

```json
{
  "evidence": [
    {
      "disclosureId": 1,
      "disclosureVersionId": 2,
      "reportTitle": "사업보고서",
      "sourceUrl": "https://dart.fss.or.kr/",
      "sectionTitle": "사업의 내용",
      "quote": "해당 절에서 검증한 원문 인용",
      "isSuperseded": false,
      "publishedAt": "2026-03-12",
      "periodStart": "2025-01-01",
      "periodEnd": "2025-12-31"
    }
  ]
}
```

날짜는 시각·타임존이 없는 DB DATE를 그대로 반환한다. 앱의 기존 `_evidence()`가 `publishedAt`, `_evPeriod()`가 `periodStart`·`periodEnd`를 읽고 `공시일`·`대상 기간`을 표시한다. 앱의 날짜 키 목록에 없는 `disclosedOn`으로 바꾸지 않는다.

기간이 없거나 한쪽만 있는 경우 DB의 null을 그대로 유지한다. 보고서 제목·접수번호·현재 연도에서 기간을 추정하거나 채워 넣지 않는다. 서로 다른 근거는 각각 해당 공시의 날짜를 반환한다. 기존 정정·대체 공시 표시와 제출 가능 여부 판정도 유지한다.

이 변경은 기존 컬럼을 조회하므로 Flyway·DB 스키마·기존 데이터 수정이 필요 없다. 새 데이터 수집·보정도 수행하지 않는다. 퀴즈 API, AI 생성·평가 프롬프트, 로그인·권한, 답변 접수·횟수 처리에는 변경이 없다.

범위: 이미 있는 근거 카드에 날짜 메타데이터를 제공한다. 근거 배열이 비었거나 인용문이 빈 경우 카드를 새로 만드는 변경은 아니다. 앱은 여러 근거를 한 카드와 한 세트의 제목·날짜로 표시하므로 서로 다른 보고서의 근거를 출처별로 묶는 화면 변경은 별도 검토가 필요하다.
