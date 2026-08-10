import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

const baseUrl = __ENV.BASE_URL || "http://127.0.0.1:8080";
const roomId = __ENV.ROOM_ID || "1";
const requestRate = Number(__ENV.REQUEST_RATE || 20);
const duration = __ENV.DURATION || "5s";

const accepted202 = new Counter("backend_accepted_202");
const unexpectedResponses = new Counter("backend_unexpected_response");

export const options = {
  summaryTrendStats: ["avg", "p(95)", "p(99)", "max"],
  scenarios: {
    analyzeBurst: {
      executor: "constant-arrival-rate",
      rate: requestRate,
      timeUnit: "1s",
      duration,
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 200),
    },
  },
  thresholds: {
    checks: ["rate==1"],
    http_req_failed: ["rate<0.05"],
    // 비교 실험에서는 느린 기준선도 중단하지 않고 결과를 수집합니다.
    http_req_duration: ["p(95)<5000"],
  },
};

export default function () {
  const unique = `${__VU}-${__ITER}-${Date.now()}`;
  const payload = JSON.stringify({
    imageUrls: [`https://example.com/performance/${unique}.jpg`],
  });

  const response = http.post(
    `${baseUrl}/api/rooms/${roomId}/models/analyze`,
    payload,
    {
      headers: { "Content-Type": "application/json" },
      tags: { endpoint: "analyze" },
      timeout: __ENV.REQUEST_TIMEOUT || "5s",
    },
  );

  const accepted = check(response, {
    "backend returned 202": (result) => result.status === 202,
  });

  if (accepted) {
    accepted202.add(1);
  } else {
    unexpectedResponses.add(1);
  }
}

function metric(data, name, value) {
  return data.metrics[name]?.values?.[value] ?? null;
}

export function handleSummary(data) {
  const compact = {
    generatedAt: new Date().toISOString(),
    checksRate: metric(data, "checks", "rate"),
    totalRequests: metric(data, "http_reqs", "count"),
    failedRequestRate: metric(data, "http_req_failed", "rate"),
    responseTimeMs: {
      average: metric(data, "http_req_duration", "avg"),
      p95: metric(data, "http_req_duration", "p(95)"),
      p99: metric(data, "http_req_duration", "p(99)"),
      max: metric(data, "http_req_duration", "max"),
    },
    backendAccepted202: metric(data, "backend_accepted_202", "count") ?? 0,
    backendUnexpectedResponse: metric(data, "backend_unexpected_response", "count") ?? 0,
    droppedIterations: metric(data, "dropped_iterations", "count") ?? 0,
  };

  const resultFile = __ENV.RESULT_FILE || "performance/results/k6-summary.json";
  return {
    stdout: `${JSON.stringify(compact, null, 2)}\n`,
    [resultFile]: JSON.stringify({ compact, raw: data }, null, 2),
  };
}
