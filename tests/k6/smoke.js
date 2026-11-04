import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    smoke: {
      executor: 'constant-vus',
      vus: 3,
      duration: '1m',
      gracefulStop: '5s',
    },
  },
  discardResponseBodies: true,
  maxRedirects: 0,
  thresholds: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

export default function () {
  // 모니터링 전용 Docker 내부 앱의 조회 API만 호출합니다.
  const response = http.get('http://app:8080/v1/app-config', {
    timeout: '3s',
    responseCallback: http.expectedStatuses(200),
    tags: {
      name: 'GET /v1/app-config',
    },
  });

  check(response, {
    'HTTP 200': (r) => r.status === 200,
  });

  // 각 가상 사용자가 요청 후 1초씩 쉽니다.
  sleep(1);
}
