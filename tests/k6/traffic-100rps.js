import http from 'k6/http';
import { check } from 'k6';

export const options = {
  scenarios: {
    traffic_100rps: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 50,
      maxVUs: 100,
      gracefulStop: '5s',
    },
  },

  discardResponseBodies: true,
  maxRedirects: 0,

  thresholds: {
    http_req_failed: ['rate==0'],
    checks: ['rate==1'],
    dropped_iterations: ['count==0'],
  },

  summaryTrendStats: [
    'avg',
    'min',
    'med',
    'max',
    'p(90)',
    'p(95)',
    'p(99)',
  ],
};

export default function () {
  // 로컬 모니터링 전용 Docker 앱의 조회 API만 호출합니다.
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

  // 요청 간격은 constant-arrival-rate가 조절하므로 sleep을 넣지 않습니다.
}
