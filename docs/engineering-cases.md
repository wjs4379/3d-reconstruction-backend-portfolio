# MetaRound: 부동산·3D 백엔드 설계 사례

같은 방을 촬영한 사진 **4장 → AI 분석 → 사용자 보정 → 재구성 → 가구 수동 배치 → GLB 저장 → 매물 조회**가 제품의 핵심 흐름이다. 백엔드는 계산 알고리즘보다 입력의 소유권, 작업 상태, 파일 검증과 복구 경계를 책임진다.

건물 `Property`, 거래 대상 `Unit`, 호실 안의 공간 `Room/Space`, 생성 버전 `Room3DModel`, 거래 조건·공개 상태 `Listing`을 구분한다. 새 분석이 실패해도 매물에서 선택한 기존 완료 모델은 유지한다.

아래는 검토·로컬 재현에서 발견한 문제와 설계 선택이다. 운영 사용자에게 발생한 장애나 개인이 직접 클릭한 시험으로 확대하지 않는다. 실행 날짜와 검증 범위는 [검증 기록](validation.md)을 따른다.

## 1. 사진 네 장을 “ID 네 개”보다 강한 입력 계약으로 만들기

**문제.** 제품에는 같은 방의 네 방향 사진이 필요했지만 최소 한 장 검사는 이를 표현하지 못했다. 같은 파일을 여러 번 올리거나 사진 순서가 바뀌면 분석과 사용자 보정의 대응도 틀어질 수 있었다.

**선택.** 새 분석은 동일 호실의 소유자에게 속한 `VERIFIED IMAGE` 네 개를 요구한다. ID와 SHA256 중복을 거부하고 원본 순서를 보존한다. 보정에는 `imageIndex`와 원본 점 식별자를 유지하며, 사진별 좌표 범위와 다른 사진을 연결하는 간선을 검사한다.

EXIF 회전 사진은 파일을 다시 인코딩하지 않고 실제 표시 폭·높이를 저장한다. V5 이행에서 기존 사진의 크기는 `null`로 보존한다. 복구 API는 원본 key/version/size/SHA를 재확인한 뒤 metadata만 채우므로 자산·모델·작업 ID가 바뀌지 않는다.

**확인.** 사진 1·2·3·5장, 반복 ID, 서로 다른 ID의 동일 bytes, 타인 자산을 모델/Outbox 생성 전에 거부한다. EXIF 표시 크기·metadata 재호출·변조 파일 거부도 회귀로 확인한다.

**한계.** SHA는 동일 bytes를 검출한다. 재인코딩된 같은 사진, 동일한 실제 방인지, 충분한 시점 겹침을 보증하는 검사는 아니다. 매물 사진 갤러리나 수동 GLB 업로드에도 네 장을 강제하지 않는다.

코드: [분석 접수](../src/main/kotlin/com/hanyang/backend/domain/model3d/ManagedModelService.kt), [미디어 검증](../src/main/kotlin/com/hanyang/backend/domain/media/MediaValidation.kt), [V5](../src/main/resources/db/migration/V5__verified_image_dimensions.sql).

검사: [네 장 DTO](../src/test/kotlin/com/hanyang/backend/domain/model3d/ManagedAnalyzeRequestValidationTest.kt), [미디어 흐름](../src/test/kotlin/com/hanyang/backend/domain/media/MediaWorkflowIntegrationTest.kt), [사진 metadata](../src/test/kotlin/com/hanyang/backend/domain/media/MediaImageMetadataIntegrationTest.kt).

## 2. 분석뿐 아니라 보정·재구성도 재전송할 수 있게 만들기

**문제.** DB commit 뒤 메모리에서 HTTP 요청을 시작하면 프로세스 종료 때 전송 기록이 사라진다. 보정 저장의 응답만 유실되거나, 늦은 분석 callback이 이미 재구성 중인 모델을 덮는 문제도 고려해야 했다.

**선택.** 모델 상태와 `ANALYZE` 또는 `AI_CORRECTION_RECONSTRUCT` 명령을 같은 트랜잭션에 저장한다. 후자는 원본 보정 payload를 보존하고 보정 전달 → 재구성 요청을 순서대로 반복한다.

Worker는 짧은 트랜잭션으로 선점한 뒤 DB 락 밖에서 HTTP를 수행한다. lease·token으로 오래된 Worker의 반영을 차단하고, 일시 오류는 backoff로 재시도하며 한도를 넘으면 `DEAD`로 남긴다. 현재 RDB로 전달 기록을 관리하기로 선택했으며 새 메시지 브로커는 도입하지 않았다.

```text
ANALYZING → WAITING_FOR_CORRECTION → RECONSTRUCTING
          → WAITING_FOR_MODEL_UPLOAD → SUCCESS
```

AI의 좌표 생성 완료와 검증된 GLB 저장 완료는 다르다. callback은 `ANALYZE/RECONSTRUCT` 단계·현재 상태·유효한 좌표를 확인한다. 지연 알림으로 최종 좌표나 완료 파일을 바꾸지 않고, callback 유실은 상태 조회로 보완한다.

**확인.** Outbox insert 실패의 모델 rollback, 보정 상태와 명령의 원자 commit, ACK 유실 뒤 lease 만료 재전송, stale token 거부, callback 단계 불일치·순서 변경·좌표 키 누락·잘못된 좌표 구조 거부를 검사한다.

**한계.** at-least-once 전달이다. 수신 AI도 멱등성을 지켜야 하며 GPU 연산이 물리적으로 한 번만 실행된다고 보장하지 않는다. AI 복구는 저장 입력부터 단계 재계산하는 방식이며 중단된 CUDA 연산의 중간 지점 재개가 아니다.

코드: [Outbox 전달](../src/main/kotlin/com/hanyang/backend/domain/model3d/outbox/OutboxDispatchService.kt), [선점·조건부 반영](../src/main/kotlin/com/hanyang/backend/domain/model3d/outbox/OutboxEventRepository.kt), [상태 전이](../src/main/kotlin/com/hanyang/backend/domain/model3d/Room3DModelService.kt).

검사: [트랜잭션](../src/test/kotlin/com/hanyang/backend/domain/model3d/outbox/OutboxTransactionIntegrationTest.kt), [보정 재전송](../src/test/kotlin/com/hanyang/backend/domain/model3d/outbox/OutboxCorrectionRecoveryIntegrationTest.kt), [모델 흐름](../src/test/kotlin/com/hanyang/backend/domain/model3d/Room3DModelWorkflowTest.kt).

## 3. 업로드 URL이 아닌 검증한 bytes를 완료 자산으로 고정하기

**문제.** staging 파일을 최종 결과로 참조하면 검증 후 재업로드로 내용이 바뀔 수 있다. 확장자·MIME·URL만 믿으면 잘못된 이미지나 렌더링 비용이 큰 GLB를 공개할 수도 있다.

**선택.** 업로드 세션에서 사용자·호실·용도·만료·선언 크기를 확인하고 실제 bytes를 검증한다. 캡처한 bytes는 새 final key로 저장한다. 짧은 session row-lock 트랜잭션에서 asset/model/session을 확정하고 중복 complete에는 같은 완료 결과를 반환한다.

사진은 decode 결과와 픽셀 한도를 검사한다. GLB는 버전·JSON/buffer 구조·accessor/index 범위·외부 리소스 참조·디코딩 및 draw 한도를 검사한다. 저장된 파일도 선택 모델·호실·인증 사용자에게 귀속되어야 매물에 연결할 수 있다.

**확인.** staging 변경 후 final bytes 불변, 동시 complete의 단일 commit, 타인·만료·길이 불일치·잘못된 GLB 거부를 시험한다. 다운로드·검증 동시 처리도 제한한다.

**한계.** 객체 저장소 쓰기와 DB commit은 하나의 원자적 트랜잭션이 아니다. 실패·경쟁 패자의 파일은 best-effort로 정리하며 장애 시 orphan object가 남을 수 있다. GLB 형식 검사는 실제 방 치수나 AI 품질 검증을 대신하지 않는다.

코드: [미디어 서비스](../src/main/kotlin/com/hanyang/backend/domain/media/MediaService.kt), [GLB 검증](../src/main/kotlin/com/hanyang/backend/domain/media/GlbValidation.kt), [소유권·선택 모델](../src/main/kotlin/com/hanyang/backend/domain/listing/ListingService.kt).

검사: [미디어 통합](../src/test/kotlin/com/hanyang/backend/domain/media/MediaWorkflowIntegrationTest.kt), [GLB 경계값](../src/test/kotlin/com/hanyang/backend/domain/media/GlbValidationTest.kt), [동시 다운로드](../src/test/kotlin/com/hanyang/backend/domain/media/MediaDownloadConcurrencyTest.kt).

## 4. “DB가 남는다”에서 “별도 환경에 복원된다”까지 확인하기

**문제.** H2 메모리 시연은 재시작하면 사용자·매물·문의·선택 모델이 사라졌다. 파일만 남겨도 소유권과 도메인 연결은 복구되지 않는다. 백업 성공 메시지와 복원 가능 여부도 서로 다른 문제였다.

**선택.** MySQL과 Flyway V1~V5, `ddl-auto=validate`, 영속 media를 사용한다. 기존 데이터를 자동 baseline하거나 `update`로 우회하지 않고 별도 환경에서 이행을 검사한다.

백업은 writer를 멈춘 구간의 DB·파일·AI 상태를 보존한다. 복원 도구는 입력 소유권·권한·archive 경로·SHA를 먼저 검사한 뒤 새 schema와 제한 계정에 import한다. 전체 테이블의 행·DDL fingerprint, 외래키 orphan과 실제 media를 대조한다. schema 이름의 `_`가 권한 wildcard로 해석되지 않게 처리한다.

실제 OAuth 준비도 기존 시연과 분리했다. 테스트 인증 클래스 없는 production JAR, 새 DB·계정·파일 저장소를 사용하고 키 누락은 `503`으로 명시한다. AI PC 연결 도구는 전용 공개키·호스트 지문과 양방향 SSH 경로를 준비하며, 접속 검사와 실제 추론 완료를 따로 보고한다.

**확인.** 제한 계정의 활성 DB·유사 schema 접근 거부, 별도 복원 후 테이블/FK/media 일치, 원본 환경 불변을 검사했다. 실제 연동 준비에서는 production JAR와 새 DB를 실행하고 기존 데이터 보존을 대조했다.

**한계.** 로컬 디스크 보존은 디스크 장애 대비 백업과 다르다. 이행 검사·복원 검사가 RDS 장애 조치나 상시 운영을 입증하지 않는다. 현재 실제 Kakao와 팀원 PC의 새 사진 E2E는 별도 확인이 남아 있다.

코드: [이행 파일](../src/main/resources/db/migration), [백업 fingerprint](../scripts/backup_database_fingerprint.py), [격리 복원](../scripts/verify_local_backup.py), [실제 연동 실행기](../scripts/real_integration.py), [AI PC 연결](../scripts/local_ai_connection.py).

검사: [MySQL 이행](../src/test/kotlin/com/hanyang/backend/migration/MySqlMigrationIntegrationTest.kt), [복원 보호](../scripts/tests/test_verify_local_backup.py), [실행 환경 보호](../scripts/tests/test_real_integration.py).

## 담당 범위와 AI 도구 사용

백엔드 담당자로서 기존 부동산 목적과 사진 네 장 조건을 명확히 하고, 범위를 시연 가능한 흐름으로 좁히며 데이터 보존·배포·팀 간 계약 점검을 요구했다. 구현·코드 검토·회귀 시험에는 Codex를 활용했다. 자동 브라우저·CLI 검증은 사용자 본인의 수동 시연 기록과 구분한다.

기존 신경망·추론 전략·가중치는 AI 팀원의 영역이고, React/Three.js 화면과 가구 수동 배치는 프론트 팀원의 영역이다. 이 문서는 백엔드의 도메인·권한·전달·입출력·복구 설계와 통합 검증을 설명한다. 가구 자동 추천 알고리즘을 개발했다는 의미는 아니다.
