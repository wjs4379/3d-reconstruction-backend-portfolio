# AI 기반 3D 공간 재구성 플랫폼

[![Backend CI](https://github.com/wjs4379/3d-reconstruction-backend-portfolio/actions/workflows/Backend%20CI.yml/badge.svg?branch=main)](https://github.com/wjs4379/3d-reconstruction-backend-portfolio/actions/workflows/Backend%20CI.yml)

여러 시점에서 촬영한 실내 사진을 분석해 **사용자가 보정할 수 있는 공간 좌표를 생성하고, 최종 3D 모델로 재구성하는 애플리케이션**입니다.

프론트엔드, AI, 백엔드 담당으로 구성된 3인 졸업프로젝트이며, 이 저장소는 Kotlin/Spring Boot 기반 백엔드입니다. 백엔드는 프론트엔드와 FastAPI AI 서버 사이에서 요청 흐름, 상태, 데이터 정합성을 관리합니다.

> 이 저장소는 제가 담당한 백엔드의 포트폴리오 검토용 공개 스냅샷입니다. 원본 팀 저장소는 비공개이며, 민감정보와 내부 작업 기록을 제외하고 공개 가능한 백엔드 코드와 문서를 정리했습니다.

## 프로젝트 요약

| 구분 | 내용 |
| --- | --- |
| 핵심 기능 | 다중 이미지 분석, 공간 좌표 보정, 3D 모델 재구성 |
| 백엔드 역할 | API 제공, AI 서버 연동, 비동기 작업 상태 관리, 결과 영속화 |
| 팀 구성 | Frontend 1명 · AI 1명 · Backend 1명 |
| 주요 기술 | Kotlin, Spring Boot, JPA, MySQL, WebClient, JUnit 5, GitHub Actions |
| 외부 연동 | 프론트가 업로드한 AWS S3 이미지 URL 전달·최종 모델 URL 저장, FastAPI AI 서버, Webhook |

## 왜 비동기 파이프라인이 필요했는가

AI의 이미지 분석과 좌표 재구성은 처리 시간이 길고, 분석 결과를 사용자가 직접 보정한 뒤 다시 재구성해야 하는 **2단계 AI 작업**입니다. AI가 최종 좌표를 생성한 뒤에는 프론트가 모델 파일을 만들어 영속 저장소에 업로드하는 단계가 이어집니다. 하나의 HTTP 요청에서 AI 결과를 끝까지 기다리면 다음 문제가 생깁니다.

- 처리 시간만큼 프론트 요청이 유지되어 타임아웃과 자원 점유 가능성이 커집니다.
- 1차 분석 완료, AI 최종 좌표 생성, 프론트 최종 모델 업로드를 하나의 `SUCCESS`로 표현하기 어렵습니다.
- 프론트 보정 전후의 작업 상태와 늦게 도착한 Webhook을 일관되게 처리하기 어렵습니다.

이를 해결하기 위해 AI 호출 결과를 기다리는 동기 흐름을 **WebClient 요청 + Webhook 콜백 + 프론트 Polling** 구조로 분리했습니다. 백엔드는 요청 접수 시 작업 상태를 먼저 저장하고, AI 처리 결과는 Webhook으로 받아 갱신합니다. AI의 최종 `SUCCESS`는 좌표 생성 완료를 뜻하며, 프론트가 영속 모델 파일의 URL을 등록해야 백엔드 작업이 최종 `SUCCESS`가 됩니다.

## 시스템 아키텍처

```mermaid
flowchart LR
    FE["Frontend App"]
    S3["AWS S3"]
    BE["Spring Boot Backend"]
    DB[("MySQL")]
    AI["FastAPI AI Server"]

    FE -->|"실내 사진·최종 모델 업로드"| S3
    S3 -->|"이미지·최종 모델 URL"| FE
    FE -->|"분석·보정·상태 조회·최종 결과 API"| BE
    BE -->|"JPA"| DB
    BE -->|"WebClient: analyze / correction / reconstruct"| AI
    AI -->|"Webhook: 처리 상태와 좌표 데이터"| BE
    BE -->|"Polling 응답"| FE
```

### 전체 처리 흐름

```mermaid
sequenceDiagram
    autonumber
    participant FE as Frontend
    participant S3 as AWS S3
    participant BE as Spring Boot
    participant DB as MySQL
    participant AI as FastAPI AI

    FE->>S3: 여러 시점의 실내 사진 업로드
    S3-->>FE: 이미지 URL 반환
    FE->>BE: 방 메타데이터 생성
    BE->>DB: Room 저장
    BE-->>FE: roomId 반환
    FE->>BE: 분석 요청(imageUrls)
    BE->>DB: Room3DModel 저장(ANALYZING)
    DB-->>BE: 트랜잭션 커밋
    BE->>AI: 분석 요청(WebClient, 커밋 이후 실행)
    BE-->>FE: AI 완료를 기다리지 않고 현재 상태 반환

    AI->>BE: 초기 좌표 완료 Webhook(aiVertices/aiEdges)
    BE->>DB: 좌표 데이터와 상태 갱신
    FE->>BE: 상태 Polling
    BE-->>FE: 보정 가능한 좌표 반환

    FE->>BE: 사용자 보정 좌표 전송
    BE->>DB: 상태 갱신(RECONSTRUCTING)
    BE->>AI: correction 완료 후 reconstruct 순차 요청
    BE-->>FE: 재구성 상태 반환

    AI->>BE: Webhook(SUCCESS, finalVertices/finalEdges)
    BE->>DB: 최종 좌표 저장, WAITING_FOR_MODEL_UPLOAD로 전이
    FE->>BE: 상태 Polling
    BE-->>FE: modelId와 최종 좌표 반환
    FE->>FE: 최종 좌표로 모델 파일 생성
    FE->>S3: 최종 모델 파일 업로드
    S3-->>FE: 영속 modelUrl 반환
    FE->>BE: PUT /api/rooms/{roomId}/models/{modelId}/result
    BE->>DB: modelUrl·파일 메타데이터 저장, SUCCESS로 전이
    BE-->>FE: SUCCESS와 modelUrl 반환
    FE->>BE: GET /api/rooms/{roomId}/models/status
    BE-->>FE: 저장된 SUCCESS와 modelUrl 반환
```

## 주요 상태 흐름

AI 서버가 전달하는 `RenderStatus`는 다섯 가지이지만, 이 값만으로는 1차 분석 완료, 최종 좌표 생성, 프론트 모델 업로드 단계를 구분할 수 없었습니다. 백엔드는 `WAITING_FOR_MODEL_UPLOAD`을 추가한 여섯 가지 `ModelStatus`로 서비스 흐름을 관리합니다.

```mermaid
stateDiagram-v2
    [*] --> ANALYZING: 분석 요청 접수
    ANALYZING --> WAITING_FOR_CORRECTION: 유효한 초기 좌표 Webhook
    WAITING_FOR_CORRECTION --> RECONSTRUCTING: 사용자 보정·재구성 요청
    RECONSTRUCTING --> WAITING_FOR_MODEL_UPLOAD: AI 최종 좌표 SUCCESS Webhook
    WAITING_FOR_MODEL_UPLOAD --> SUCCESS: 프론트 최종 모델 결과 등록
    ANALYZING --> FAILED: 요청 또는 분석 실패
    RECONSTRUCTING --> FAILED: 보정 전달 또는 재구성 실패
```

| 상태 | 의미 | 프론트 동작 |
| --- | --- | --- |
| `ANALYZING` | AI가 업로드 이미지를 분석하는 중 | 상태 Polling |
| `WAITING_FOR_CORRECTION` | 1차 좌표 생성 완료 | 좌표 확인 및 보정 |
| `RECONSTRUCTING` | 보정 데이터를 기반으로 AI가 최종 좌표를 생성하는 중 | 상태 Polling |
| `WAITING_FOR_MODEL_UPLOAD` | AI 최종 좌표 생성 완료 | 최종 좌표로 모델 생성·업로드 후 결과 등록 |
| `SUCCESS` | 프론트가 제공한 영속 모델 URL까지 저장된 완료 상태 | 최종 모델 표시 |
| `FAILED` | 분석 또는 재구성 단계의 AI 요청·처리 실패 | 오류 안내 및 재시도 판단 |

AI의 최종 `SUCCESS` Webhook은 `finalVertices`와 `finalEdges`가 준비됐다는 뜻입니다. 백엔드는 이를 `WAITING_FOR_MODEL_UPLOAD`로 변환하고, 프론트가 좌표로 모델 파일을 생성·업로드한 뒤 `PUT /api/rooms/{roomId}/models/{modelId}/result`로 `modelUrl`을 등록해야 `SUCCESS`로 전이합니다. 백엔드 Webhook 계약은 `modelUrl`을 처리하지 않으며, 최종 URL의 유일한 등록 주체는 프론트입니다.

## 핵심 설계와 트러블슈팅

### 1. 장시간 AI 작업과 HTTP 요청 분리

**문제**

동기 호출에서는 AI 처리가 끝날 때까지 요청이 반환되지 않아 클라이언트 대기 시간과 서버 자원 점유가 길어졌습니다.

**해결**

- `WebClient`가 반환하는 `Mono<Void>`로 AI 요청을 구성했습니다.
- DB 트랜잭션이 커밋된 뒤 `subscribe`하여 외부 요청을 시작합니다.
- 프론트에는 `ANALYZING` 또는 `RECONSTRUCTING` 상태를 반환하고, AI 좌표 결과는 Webhook으로 수신합니다.
- 최종 좌표가 준비되면 `WAITING_FOR_MODEL_UPLOAD`를 반환하고, 프론트의 모델 업로드 결과는 별도 `PUT` 요청으로 받습니다.
- 비동기 요청 실패 신호는 `boundedElastic` 스케줄러에서 처리해 작업을 `FAILED`로 갱신합니다. 기존 `roomData`가 비어 있을 때는 오류 메타데이터도 함께 저장합니다.

### 2. 트랜잭션 커밋 이후 AI 요청

**문제**

방과 3D 작업 레코드가 DB에 확정되기 전에 AI 서버가 처리와 Webhook 전송을 시작하면, 아직 조회할 수 없는 식별자를 기준으로 콜백이 도착할 수 있습니다.

**해결**

`TransactionSynchronizationManager`의 `afterCommit()`에서 AI 요청을 실행하도록 분리했습니다. 트랜잭션이 없는 호출에서는 즉시 실행해 동일한 서비스 메서드를 재사용합니다.

### 3. 비동기 전환 과정의 DB 제약조건 충돌

**문제**

비동기 구조에서는 최초 요청 시점에 `roomData`와 `modelUrl`이 아직 존재하지 않습니다. 기존 스키마의 `NOT NULL` 전제를 유지하면 초기 작업 저장 시 `DataIntegrityViolationException`이 발생했습니다.

**해결**

- 결과 데이터 컬럼을 처리 완료 전까지 `null`을 허용하도록 변경했습니다.
- 먼저 `ANALYZING` 작업을 저장하고, Webhook 도착 시점에는 좌표를, 프론트 최종 결과 등록 시점에는 모델 URL과 파일 메타데이터를 각각 갱신하도록 저장 시점을 분리했습니다.

### 4. 지연 Webhook에 의한 상태 역행 방지

**문제**

네트워크 지연으로 과거 단계의 Webhook이 늦게 도착하면 `RECONSTRUCTING`, `WAITING_FOR_MODEL_UPLOAD` 또는 `SUCCESS`가 다시 이전 상태로 돌아갈 수 있습니다.

**해결**

- 현재 상태와 Webhook 상태를 함께 확인하는 상태 전이 규칙을 적용했습니다.
- `SUCCESS`와 `FAILED`에서는 후속 Webhook이 상태나 결과 필드를 바꾸지 못하게 합니다.
- 현재 단계보다 늦게 도착한 과거 단계의 Webhook은 상태와 결과 필드 모두 반영하지 않습니다.
- Webhook과 프론트 최종 결과 등록은 같은 작업 행에 쓰기 잠금을 사용하고, 비동기 전송 실패는 예상 상태일 때만 조건부로 반영합니다.
- 상태 역행과 존재하지 않는 `aiRoomId` 예외를 단위 테스트로 검증했습니다.

### 5. 보정과 재구성의 실행 순서 보장

correction HTTP 요청을 나타내는 `Mono`가 정상 완료된 뒤 reconstruct HTTP 요청을 전송하도록 Reactor 체인으로 연결했습니다.

```kotlin
aiServerClient.sendCorrection(model.aiRoomId, request)
    .then(aiServerClient.reconstruct(model.aiRoomId))
```

### 6. AI 좌표 생성과 영속 모델 파일 생성의 책임 분리

AI 서버는 재구성된 최종 좌표를 Webhook으로 전달하고, 최종 모델 파일은 프론트가 생성해 S3 등의 영속 저장소에 업로드합니다. 백엔드는 AI `SUCCESS`를 `WAITING_FOR_MODEL_UPLOAD`로 변환해 두 완료 시점을 구분합니다.

프론트는 상태 조회 응답의 `modelId`를 사용해 `PUT /api/rooms/{roomId}/models/{modelId}/result`로 `modelUrl`, `fileSizeMb`, `format`을 전달합니다. 백엔드는 `roomId`와 `modelId` 조합 및 상태를 검증한 뒤 결과를 저장하고 `SUCCESS`로 전이합니다. 같은 URL의 재요청은 멱등하게 처리하고, 완료된 모델의 URL을 다른 값으로 덮어쓰는 요청은 거부합니다.

`PUT` 응답 자체에 최종 `SUCCESS`와 `modelUrl`이 포함됩니다. 프론트가 저장 상태를 다시 확인하려면 `GET /api/rooms/{roomId}/models/status`를 명시적으로 호출하며, 백엔드가 `PUT` 이후 `GET`을 자동 호출하지는 않습니다.

## 담당 범위

백엔드 담당자로서 다음 영역을 설계·구현했습니다.

- Kotlin/Spring Boot 기반 Room·3D Model API와 JPA 영속화
- 프론트의 여러 S3 URL을 AI Swagger의 `imagePaths` 계약으로 변환
- 백엔드 `Long` 사용자 ID를 AI 서버용 결정적 UUID로 변환
- WebClient 기반 분석·보정·재구성 요청과 좌표 결과 Webhook 수신
- `ANALYZING`부터 `SUCCESS`까지 AI 2단계와 프론트 모델 업로드 상태 모델링
- `modelId`로 대상을 고정하는 프론트 최종 모델 결과 등록 API
- 트랜잭션 커밋 이후 외부 요청 실행과 비동기 실패 상태 처리
- 지연 Webhook 상태 역행 방지 및 JUnit/Mockito 단위 테스트
- DB/JWT/외부 서버 주소 환경변수화와 OAuth 토큰 로그 제거
- Pull Request 및 `dev/main` push에서 실행되는 GitHub Actions CI 구축

### 핵심 구현 코드

- [Room3DModelService.kt](src/main/kotlin/com/hanyang/backend/domain/model3d/Room3DModelService.kt): 상태 전이, Webhook 반영, after-commit 비동기 dispatch
- [AiServerClient.kt](src/main/kotlin/com/hanyang/backend/domain/model3d/AiServerClient.kt): FastAPI 분석·보정·재구성 API 연동
- [Room3DModel.kt](src/main/kotlin/com/hanyang/backend/domain/model3d/Room3DModel.kt): 3D 작업 상태와 결과 데이터 모델
- [RoomController.kt](src/main/kotlin/com/hanyang/backend/domain/room/RoomController.kt): 방 메타데이터 생성 API
- [Room3DModelController.kt](src/main/kotlin/com/hanyang/backend/domain/model3d/Room3DModelController.kt): 분석·상태 조회·재구성·최종 결과 등록 API
- [WebhookController.kt](src/main/kotlin/com/hanyang/backend/domain/room/WebhookController.kt): AI 결과 Webhook 수신
- [Room3DModelServiceTest.kt](src/test/kotlin/com/hanyang/backend/domain/model3d/Room3DModelServiceTest.kt): 상태 경계 단위 테스트
- [Room3DModelRepositoryTest.kt](src/test/kotlin/com/hanyang/backend/domain/model3d/Room3DModelRepositoryTest.kt): 조건부 상태 갱신 JPA 통합 테스트
- [Room3DModelWorkflowTest.kt](src/test/kotlin/com/hanyang/backend/domain/model3d/Room3DModelWorkflowTest.kt): 다중 이미지 전달 및 보정·재구성 순서 테스트
- [Backend CI.yml](.github/workflows/Backend%20CI.yml): Gradle 테스트 자동화

## 주요 API

프론트엔드 요청·응답 예시는 [Frontend–Backend 3D API Contract](docs/frontend-api-contract.md)에 정리했습니다.

| Method | Endpoint | 설명 |
| --- | --- | --- |
| `POST` | `/api/rooms` | 방 메타데이터 생성 및 `roomId` 반환 |
| `POST` | `/api/rooms/{roomId}/models/analyze` | 여러 이미지 URL로 분석 시작 |
| `GET` | `/api/rooms/{roomId}/models/status` | 최신 상태와 중간·최종 결과 Polling |
| `POST` | `/api/rooms/{roomId}/models/reconstruct` | 보정 좌표 전달 후 재구성 순차 실행 |
| `PUT` | `/api/rooms/{roomId}/models/{modelId}/result` | 프론트가 업로드한 최종 모델 URL과 파일 메타데이터 등록 |
| `POST` | `/api/rooms/{roomId}/models/refresh` | Webhook 유실 시 AI 상태와 1차·최종 좌표를 다시 조회해 DB에 반영 |
| `POST` | `/api/webhook` | AI 처리 상태와 좌표 결과 수신 |

상태 조회 응답의 `roomData`는 DB에 저장된 JSON 문자열을 객체로 변환해 전달합니다. 프론트는 별도의 `JSON.parse()` 없이 좌표 필드에 접근할 수 있습니다.

## 테스트와 CI

서비스 테스트는 다음 경계를 검증합니다.

- `ANALYZING → WAITING_FOR_CORRECTION`
- AI 최종 좌표 Webhook에서 `RECONSTRUCTING → WAITING_FOR_MODEL_UPLOAD`
- 프론트 최종 모델 결과 등록에서 `WAITING_FOR_MODEL_UPLOAD → SUCCESS`
- AI 최종 좌표 도착 전 모델 URL 등록 거부
- 완료 Webhook의 1차·최종 좌표 JSON 구조 검증
- 유효하지 않은 모델 URL과 일치하지 않는 `roomId`·`modelId` 조합 거부
- 같은 모델 URL 재등록의 멱등성과 완료된 URL 덮어쓰기 방지
- Webhook 유실 시 AI 상태 재조회로 1차·최종 좌표와 다음 대기 상태 복구
- 지연 AI 상태 조회와 Webhook으로 `WAITING_FOR_MODEL_UPLOAD`가 역행하거나 좌표가 덮어써지지 않음
- `SUCCESS` 이후 늦은 `WAITING_FOR_CORRECTION` Webhook에도 상태와 결과 유지
- 존재하지 않는 `aiRoomId` Webhook 예외 처리
- 여러 이미지 URL 전체를 AI `imagePaths`로 전달
- correction 완료 후 reconstruct 요청 실행
- 상태 조회 시 `roomData` JSON 객체 변환
- 중복 경로가 다시 추가되지 않도록 공개 API 매핑 검증
- 실제 JPA에서 예상 상태에만 비동기 전송 실패를 반영하고 기존 좌표를 보존하는 조건부 UPDATE 검증

별도의 `BackendApplicationTests`에서 Spring Application Context 로딩을 검증합니다.

```powershell
.\gradlew.bat test --no-daemon
```

GitHub Actions의 `Backend CI`는 다음 이벤트에서 Java 21 환경으로 전체 테스트를 실행합니다.

- `dev`, `main` 대상 Pull Request
- `dev`, `main` push
- 수동 실행(`workflow_dispatch`)

동일 PR의 이전 실행은 자동 취소하며, 실패 시 테스트 리포트를 Artifact로 보관합니다.

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Language / Runtime | Kotlin 2.2.0, Java 21 |
| Framework | Spring Boot 4.0.3, Spring MVC, Spring WebFlux WebClient |
| Data | Spring Data JPA, MySQL, H2(Test) |
| Async Integration | Reactor `Mono`, Webhook, Frontend Polling, 최종 결과 `PUT` |
| Test | JUnit 5, Mockito, Spring Boot Test |
| API Docs | Springdoc OpenAPI / Swagger UI |
| Collaboration | Git, Issue 기반 Branch Workflow, GitHub Actions |

## 프로젝트 구조

```text
src/main/kotlin/com/hanyang/backend
└── domain
    ├── model3d   # AI Client, 상태 모델, 3D 작업 API
    ├── room      # 방 생성과 AI Webhook 수신
    ├── property  # 공간이 속한 건물 정보
    ├── usage     # 공간 사용 이력
    └── user      # OAuth 로그인과 JWT 발급
```

## 로컬 실행

### 요구사항

- JDK 21
- MySQL 8
- 3D 변환 흐름 검증 시 실행 중인 AI 서버 또는 접근 가능한 AI 서버 URL

### 환경변수

실제 비밀값은 저장소에 커밋하지 않습니다. `.env.example`을 참고해 IntelliJ Run Configuration 또는 실행 셸에 설정합니다.

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/spatial_asset_db?...` | MySQL 연결 URL |
| `DB_USERNAME` | `root` | DB 사용자 |
| `DB_PASSWORD` | 없음 | DB 비밀번호 |
| `AI_SERVER_BASE_URL` | `http://localhost:8000` | FastAPI AI 서버 주소 |
| `APP_WEBHOOK_URL` | `http://localhost:8080/api/webhook` | AI 서버가 호출할 Webhook URL. 원격 AI 연동 시 외부 접근 가능한 주소 필요 |
| `JWT_SECRET` | 없음 | 32바이트 이상의 JWT 서명 키 |

### 실행 및 확인

기본 연결 설정을 사용한다면 MySQL에 데이터베이스를 먼저 생성합니다.

```sql
CREATE DATABASE IF NOT EXISTS spatial_asset_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
```

기존 `room_3d_models.status`가 MySQL native `ENUM`으로 생성돼 있다면 새 상태를 사용하기 전에 [상태 컬럼 마이그레이션](docs/sql/20260722-room-3d-model-status-varchar.sql)을 한 번 실행합니다. 새로 생성되는 스키마는 애플리케이션 상태 추가와 충돌하지 않도록 `VARCHAR(40)`을 사용합니다.

```powershell
.\gradlew.bat bootRun
```

Swagger UI:

```text
http://localhost:8080/swagger-ui/index.html
```

## 협업 방식

```text
Issue 생성 → dev 기반 자동 브랜치 생성 → 구현·테스트 → PR → dev 병합
```

| 작업 유형 | Issue prefix | Branch prefix |
| --- | --- | --- |
| 기능 | `[feature]` | `feature/` |
| 버그 | `[fix]` | `bugfix/` |
| 리팩터링 | `[refactor]` | `refactor/` |
| 문서 | `[docs]` | `docs/` |
| 테스트 | `[test]` | `test/` |
| 설정 | `[chore]` | `chore/` |

`main`에는 `dev`만 병합하고, 기능·문서 브랜치는 `dev`를 대상으로 Pull Request를 생성합니다.

## 다음 개선 계획

- Webhook HMAC 또는 공유 시크릿 검증
- WebClient 타임아웃·재시도와 요청 멱등성 적용
- Polling 응답 경량화와 상태 조회용 DB 인덱스 추가
- 여러 작업이 동시에 생성될 때 상태 조회·보정 요청도 `modelId` 기준으로 대상을 명확히 구분
- Frontend–Backend–AI 전체 E2E 자동화 및 Docker Compose 실행 환경 구성
