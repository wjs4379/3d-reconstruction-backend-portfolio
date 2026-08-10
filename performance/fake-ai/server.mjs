import http from "node:http";
import { randomUUID } from "node:crypto";

const port = Number.parseInt(process.env.FAKE_AI_PORT ?? "8000", 10);
const acceptDelayMs = Number.parseInt(process.env.FAKE_AI_ACCEPT_DELAY_MS ?? "1000", 10);
const analysisDelayMs = Number.parseInt(process.env.FAKE_AI_ANALYSIS_DELAY_MS ?? "300", 10);
const maxInFlight = Number.parseInt(process.env.FAKE_AI_MAX_IN_FLIGHT ?? "4", 10);

let stats = newStats();
const processedKeys = new Map();

function newStats() {
  return {
    startedAt: new Date().toISOString(),
    totalRequests: 0,
    acceptedRequests: 0,
    rejectedRequests: 0,
    duplicateRequests: 0,
    uniqueExecutions: 0,
    completedExecutions: 0,
    webhookSucceeded: 0,
    webhookFailed: 0,
    inFlight: 0,
    maxObservedInFlight: 0,
  };
}

function writeJson(response, statusCode, body) {
  const json = JSON.stringify(body);
  response.writeHead(statusCode, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(json),
  });
  response.end(json);
}

async function readJson(request) {
  const chunks = [];
  let size = 0;

  for await (const chunk of request) {
    size += chunk.length;
    if (size > 1024 * 1024) {
      throw new Error("request body is larger than 1 MiB");
    }
    chunks.push(chunk);
  }

  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function reset() {
  stats = newStats();
  processedKeys.clear();
}

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);

  if (request.method === "GET" && url.pathname === "/__control/health") {
    writeJson(response, 200, { status: "UP" });
    return;
  }

  if (request.method === "GET" && url.pathname === "/__control/stats") {
    writeJson(response, 200, {
      ...stats,
      configuredMaxInFlight: maxInFlight,
      acceptDelayMs,
      analysisDelayMs,
      rememberedIdempotencyKeys: processedKeys.size,
    });
    return;
  }

  if (request.method === "POST" && url.pathname === "/__control/reset") {
    reset();
    writeJson(response, 200, { reset: true });
    return;
  }

  if (request.method !== "POST" || url.pathname !== "/api/v1/rooms/analyze") {
    writeJson(response, 404, { message: "not found" });
    return;
  }

  let body;
  try {
    body = await readJson(request);
  } catch (error) {
    writeJson(response, 400, { message: error.message });
    return;
  }

  stats.totalRequests += 1;

  const suppliedKey = request.headers["idempotency-key"];
  const idempotencyKey = typeof suppliedKey === "string" && suppliedKey.trim() !== ""
    ? suppliedKey
    : `without-key-${randomUUID()}`;

  if (processedKeys.has(idempotencyKey)) {
    stats.duplicateRequests += 1;
    writeJson(response, 202, {
      accepted: true,
      duplicate: true,
      roomId: body.roomId ?? null,
    });
    return;
  }

  if (stats.inFlight >= maxInFlight) {
    stats.rejectedRequests += 1;
    writeJson(response, 503, {
      accepted: false,
      reason: "fake AI capacity exceeded",
      configuredMaxInFlight: maxInFlight,
    });
    return;
  }

  processedKeys.set(idempotencyKey, "PROCESSING");
  stats.acceptedRequests += 1;
  stats.uniqueExecutions += 1;
  stats.inFlight += 1;
  stats.maxObservedInFlight = Math.max(stats.maxObservedInFlight, stats.inFlight);

  setTimeout(() => {
    processedKeys.set(idempotencyKey, "ACCEPTED");
    stats.inFlight -= 1;
    writeJson(response, 202, {
      accepted: true,
      duplicate: false,
      roomId: body.roomId ?? null,
    });

    setTimeout(async () => {
      stats.completedExecutions += 1;
      if (typeof body.webhookUrl !== "string" || body.webhookUrl.trim() === "") {
        stats.webhookFailed += 1;
        return;
      }

      try {
        const webhookResponse = await fetch(body.webhookUrl, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            aiRoomId: body.roomId,
            status: "WAITING_FOR_CORRECTION",
            roomDataJson: JSON.stringify({ aiVertices: [], aiEdges: [] }),
          }),
        });

        if (webhookResponse.ok) {
          stats.webhookSucceeded += 1;
        } else {
          stats.webhookFailed += 1;
        }
      } catch {
        stats.webhookFailed += 1;
      }
    }, analysisDelayMs);
  }, acceptDelayMs);
});

server.listen(port, "127.0.0.1", () => {
  console.log(
    `Fake AI listening on http://127.0.0.1:${port} ` +
      `(maxInFlight=${maxInFlight}, acceptDelayMs=${acceptDelayMs})`,
  );
});

function shutdown(signal) {
  console.log(`Received ${signal}; shutting down Fake AI.`);
  server.close(() => process.exit(0));
}

process.on("SIGINT", () => shutdown("SIGINT"));
process.on("SIGTERM", () => shutdown("SIGTERM"));
