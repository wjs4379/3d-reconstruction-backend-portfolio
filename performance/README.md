# AI 분석 요청 Outbox 성능·안정성 실험

이 디렉터리는 기존 `afterCommit + subscribe()` 방식과 MySQL Transactional Outbox 방식을 **동일한 조건**에서 비교하기 위한 로컬 실험 도구입니다.

## 실험이 확인하는 것

- 백엔드의 `POST /api/rooms/{roomId}/models/analyze` 응답 시간과 성공률
- 순간 요청이 Fake AI의 허용 동시 처리량을 초과할 때 거절되는 요청 수
- Outbox 적용 후 AI 요청 동시 처리량이 설정값 이하로 제한되는지
- 전송 실패가 재시도되어 최종 완료되는지
- 재시도 시 같은 멱등성 키가 사용되어 AI 작업이 중복 실행되지 않는지
- 프로세스를 재시작해도 DB에 남은 작업을 다시 처리하는지

이 실험은 로컬 단일 인스턴스 결과이며 실제 운영 규모의 처리량을 보장하지 않습니다.

## 기본 조건

- Fake AI 주소: `http://127.0.0.1:8000`
- Fake AI 처리 지연: 1초
- Fake AI 최대 동시 처리량: 4
- k6 요청률: 초당 20건
- k6 요청 시간: 5초(총 약 100건)

## 1. Fake AI 실행

```powershell
$env:FAKE_AI_MAX_IN_FLIGHT="4"
$env:FAKE_AI_ACCEPT_DELAY_MS="1000"
$env:FAKE_AI_ANALYSIS_DELAY_MS="300"
node performance/fake-ai/server.mjs
```

## 2. 백엔드와 MySQL 준비

실제 MySQL 8.0을 사용합니다. 백엔드를 먼저 한 번 실행해 Hibernate가 테이블을 준비하게 한 뒤 `performance/sql/seed.sql`을 실행합니다. SQL 마지막에 출력된 `performance_room_id`를 기록합니다.

백엔드는 다음 값을 환경변수로 전달해 실행합니다.

- `DB_URL`
- `DB_USERNAME`
- `DB_PASSWORD`
- `AI_SERVER_BASE_URL=http://127.0.0.1:8000`
- `APP_WEBHOOK_URL=http://127.0.0.1:8080/api/webhook`
- `JWT_SECRET`(32바이트 이상인 로컬 테스트 값)

비밀번호나 JWT 비밀값을 저장소에 커밋하지 않습니다.

`performance/.env.perf.example`을 `performance/.env.perf.local`로 복사하고 로컬 MySQL 값을 입력한 다음 실행할 수도 있습니다. `.env.perf.local`은 Git에서 제외됩니다.

```powershell
./performance/run-backend.ps1
```

## 3. 동일 조건 반복 비교

먼저 실행 JAR을 생성합니다.

```powershell
./gradlew.bat test bootJar
```

`run-comparison.ps1`은 각 회차마다 다음 절차를 자동으로 수행합니다.

1. 성능 실험용 3D 작업·Outbox 행 정리
2. 기존 방식 또는 Outbox 환경변수 설정
3. Fake AI와 백엔드 프로세스 새로 실행
4. 동일한 k6 파일로 부하 발생
5. 동일한 40초 관찰 시간 대기
6. k6·Fake AI·DB 결과와 실행 설정 manifest 저장
7. 두 프로세스 종료

실행 순서는 `기존 → Outbox → Outbox → 기존`을 반복해 한 방식이 항상 나중에 실행되는 편향을 줄입니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File ./performance/run-comparison.ps1 `
  -TrialsPerMode 5 `
  -RoomId 9 `
  -RequestRate 20 `
  -Duration 5s `
  -ObservationSeconds 40

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File ./performance/summarize-comparison.ps1
```

회차별 원시 k6·Fake AI·DB 출력은 [`results/repeated`](results/repeated)에 로컬 생성되며 Git에서는 제외합니다. 재현에 필요한 실행 설정과 회차별 compact 지표는 [`manifest.json`](results/repeated/manifest.json)과 [`comparison-summary.json`](results/repeated/comparison-summary.json)에 저장합니다.

## 4. 반복 실험 결과

실행일은 2026-08-10이며, 로컬 단일 백엔드 인스턴스와 MySQL 8.0.30을 사용했습니다. k6의 constant-arrival-rate 경계 때문에 회차별 요청이 100건 또는 101건이 되어 두 방식의 합계가 1건 차이 납니다. 모든 회차에서 예상하지 않은 백엔드 응답과 dropped iteration은 0건이었습니다.

| 지표 | 기존 `afterCommit + subscribe` | MySQL Transactional Outbox |
| --- | ---: | ---: |
| 반복 횟수 | 5회 | 5회 |
| 백엔드 요청 | 504건 | 503건 |
| 백엔드 `202` | 504건 | 503건 |
| AI 접수 | 106건(21.03%) | 503건(100%) |
| AI `503` 거절 | 398건 | 0건 |
| Webhook 성공 | 106건 | 503건 |
| DB `WAITING_FOR_CORRECTION` | 106건 | 503건 |
| DB `FAILED` | 398건 | 0건 |
| DB Outbox `SENT` | 해당 없음 | 503건 |
| 백엔드 응답 평균의 회차 중앙값 | 472.46ms | 59.22ms |
| 백엔드 응답 p95의 회차 중앙값 | 933.47ms | 202.35ms |
| p95 회차 범위 | 661.99~1,371.19ms | 178.72~465.57ms |
| Fake AI 최대 동시 처리 | 4 | 4 |

이 조건에서는 p95 중앙값이 약 78.3% 낮아졌습니다. 다만 Outbox 자체가 모든 환경에서 단건 요청을 빠르게 만드는 것은 아닙니다. 기존 방식에서는 다수의 즉시 전송과 실패 콜백·DB 갱신이 요청 폭주 구간에 겹쳤고, Outbox 방식은 이 작업을 Worker로 분리한 결과입니다.

## 5. 실제 재시도·백오프 검증

Worker 동시성을 3, Fake AI 허용량을 2로 낮춰 의도적으로 `503`을 발생시켰습니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File ./performance/run-comparison.ps1 `
  -TrialsPerMode 1 `
  -RoomId 9 `
  -RequestRate 10 `
  -Duration 2s `
  -ObservationSeconds 30 `
  -ResultSet retry-backoff `
  -LabelPrefix retry `
  -WorkerBatchSize 3 `
  -WorkerMaxConcurrency 3 `
  -FakeAiMaxInFlight 2
```

Outbox 방식에서 백엔드 작업은 21건이었고 AI HTTP 호출은 재시도를 포함해 31회였습니다. 10회가 `503`으로 거절됐지만 해당 10개 이벤트가 두 번째 시도에서 처리되어 최종 결과는 다음과 같았습니다.

- AI 고유 작업 21건 실행·완료
- Webhook 성공 21건
- Outbox `SENT` 21건
- 모델 `FAILED` 0건
- Fake AI 최대 동시 처리 2건

집계 결과와 실행 설정은 [`results/retry-backoff`](results/retry-backoff)에 있습니다. 회차별 원시 출력은 같은 디렉터리에 로컬 생성되며 Git에서는 제외합니다.

## 6. 재시작과 멱등성 장애 주입

- Worker를 끈 상태에서 `PENDING` 11건을 저장하고 JVM을 완전히 종료한 뒤 Worker를 켜 재시작했습니다. 새로운 API 요청 없이 11건이 모두 `SENT`가 되고 Webhook까지 완료됐습니다.
- AI가 요청을 접수했지만 백엔드의 `SENT` 반영이 사라진 상황을 수동으로 주입했습니다. 같은 이벤트를 동일한 멱등성 키로 재전송했을 때 Fake AI의 HTTP 요청은 102회, 중복 감지는 1회, 고유 실행은 101회로 유지됐습니다.

이 두 항목은 로컬 수동 장애 주입 결과이며, 각각 [`outbox-restart-recovery.json`](results/outbox-restart-recovery.json)과 [`outbox-idempotency.json`](results/outbox-idempotency.json)에 요약했습니다.

## 해석 시 주의

- 이 결과는 로컬 단일 인스턴스·제어된 Fake AI에서의 관찰값이며 실제 운영 규모의 처리량을 보장하지 않습니다.
- `398건`은 조용히 유실된 요청이 아니라 Fake AI가 `503`으로 거절하고 기존 로직이 모델을 `FAILED`로 기록한 요청입니다.
- Outbox의 핵심 효과는 DB 커밋과 작업 등록의 원자성, 재시작 후 복구 가능성, 하류 AI 용량에 맞춘 전송입니다.
- Outbox는 at-least-once 전달입니다. 실제 AI 서버가 멱등성 키를 영속적으로 저장·검사해야 중복 실행을 방어할 수 있습니다.
- 현재 동시성 제한은 단일 백엔드 프로세스 안에서만 적용됩니다. 여러 인스턴스의 전역 제한을 보장하지 않습니다.
- 현재 Outbox 적용 범위는 최초 이미지 분석 요청이며, 사용자 보정·재구성 경로는 기존 `afterCommit` 방식입니다.
- H2 기반 자동화 테스트는 MySQL의 실제 잠금·데드락·`SKIP LOCKED` 동작을 보장하지 않습니다.
- 현재 Hibernate `ddl-auto=update`를 사용하며 Outbox 정리·보존 정책과 명시적 마이그레이션은 후속 과제입니다.
