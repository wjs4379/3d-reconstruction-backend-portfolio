# V2 API 계약: 사진 4장부터 매물의 3D 결과까지

공개 코드의 현재 계약이다. 오래된 /api/rooms/**·/api/webhook·임의 S3 URL 결과 등록은 이전 계약이며 현재 일반 업무 경로로 사용하지 않는다. v2는 사용자 API 계약 버전이고 별도 AI의 /api/v1과 번호를 맞추는 의미가 아니다. 예시 ID는 설명용이다.

## 인증과 공간

보호 API에는 Authorization: Bearer 토큰이 필요하다. 실제 Kakao 인가 코드는 POST /api/auth/kakao/code의 {"code":"..."}로 교환하고 GET /api/auth/me로 사용자와 권한을 확인한다. 앱 ID·REST 식별자·서버 Client Secret·등록 callback은 같은 앱 설정이어야 한다. 미설정 제공자는 503이며 production에 테스트 로그인은 없다.

| Method | 경로 | 역할 |
|---|---|---|
| POST / GET | /api/v2/properties | 건물 생성·내 목록 |
| POST | /api/v2/units | 호실 생성 |
| GET | /api/v2/me/units | 내 호실 |
| POST / GET | /api/v2/units/{unitId}/spaces | 호실 안의 공간 생성·목록 |
| POST | /api/v2/locations/resolve | 서버 주소·좌표 확인 |

사용자 ID를 요청에서 임의 지정해 권한을 바꾸지 않는다. 소유자·호실·공간·자산 귀속은 서버에서 확인한다.

## 사진 업로드

POST /api/v2/media/upload-sessions

```json
{"unitId":10,"kind":"IMAGE","contentType":"image/jpeg","sizeBytes":120000}
```

1. 응답의 id/uploadUrl/method/headers/expiresAt을 사용한다. S3 URL을 직접 조립하지 않는다.
2. 지정 URL에 파일을 전송한다. local 모드는 PUT /api/v2/media/upload-sessions/{id}/content, S3 모드는 응답의 임시 URL을 사용한다.
3. POST /api/v2/media/upload-sessions/{id}/complete로 내용 검증 후 MediaAssetView.id를 받는다.
4. 서로 다른 사진 네 장에 대해 반복한다. session ID와 완료 asset ID는 다르다.

서버는 checksum·이미지 표시 크기·EXIF 방향·소유권을 확인한다. 같은 사진을 반복한 분석, 형식·크기 불일치와 다른 호실 자산은 거절한다. 세부 필드는 [MediaDto](../src/main/kotlin/com/hanyang/backend/domain/media/MediaDto.kt)를 본다.

## 분석·상태·보정·재구성

| Method | 경로 | 요청/반환 |
|---|---|---|
| POST | /api/v2/spaces/{spaceId}/models/analyze | imageAssetIds 정확히 네 개 → ManagedModelView |
| GET | /api/v2/spaces/{spaceId}/models | 작업 목록 |
| GET | /api/v2/spaces/{spaceId}/models/{modelId} | 지정 작업 상태·좌표·자산 |
| POST | /api/v2/spaces/{spaceId}/models/{modelId}/reconstruct | 보정 vertices/edges → 재구성 접수 |
| POST | /api/v2/spaces/{spaceId}/models/{modelId}/refresh | AI 상태 재조회로 callback 유실 복구 |

```json
{"imageAssetIds":[41,42,43,44]}
```

결과에는 modelId/spaceId/unitId/status/sourceType/sourceImageAssetIds/assetId/modelUrl/roomData/createdAt/legacyImportRequired가 있다. roomData는 객체이며 추가 JSON.parse가 필요 없다. 반환된 modelId를 유지하고 최신 작업만 조회해 진행 작업을 섞지 않는다.

| 상태 | 표시·후속 작업 |
|---|---|
| ANALYZING | 해당 작업 Polling |
| WAITING_FOR_CORRECTION | aiVertices/aiEdges로 사진별 보정 |
| RECONSTRUCTING | 최종 좌표 대기 |
| WAITING_FOR_MODEL_UPLOAD | finalVertices/finalEdges/viewerUp으로 3D 표시·수동 가구 배치·GLB 생성 |
| SUCCESS | 검증·보존된 결과 자산 조회 |
| FAILED | 원인 확인·재시도 판단 |

사진 보정은 반환된 전체 vertices/edges에서 시작한다. UUID·imageIndex/sourceVertexId를 보존하고 사진 X/Y 범위·Z=0·같은 사진 내 간선 조건을 지킨다. 화면에서 축소된 좌표를 원본 pixel 좌표로 보내지 않는다. 재구성의 3D 좌표는 별도 좌표계다.

SUCCESS만 확인하지 말고 실제 점·선·방의 표시도 확인한다. 빈 배열을 의미 있는 방으로 판단하지 않는다. 가구 자동 추천은 현재 API에 포함되지 않는다.

## 최종 GLB 저장

업로드 대기 상태에서 방과 가구를 합친 GLB를 만들고 같은 upload-session 절차로 검증한다.

```json
{
  "unitId":10,"kind":"MODEL","contentType":"model/gltf-binary",
  "sizeBytes":250000,"spaceId":20,"modelId":30,
  "sourceImageAssetIds":[41,42,43,44]
}
```

완료 응답의 asset ID와 다시 조회한 모델의 assetId/status=SUCCESS를 확인한다. 서버는 GLB 내용·용량·공간/작업/원본 자산 일치를 확인한다. 임의 외부 modelUrl을 등록하는 구 PUT 경로를 사용하지 않는다. 같은 세션 완료는 확정 자산을 재사용하며 완료 파일 덮어쓰기를 허용하지 않는다.

## 매물·조회

| Method | 경로 | 역할 |
|---|---|---|
| POST | /api/v2/units/{unitId}/listings | 매물 초안 |
| PUT / PATCH | /api/v2/listings/{listingId} | expectedVersion으로 충돌 확인 후 수정 |
| POST | /api/v2/listings/{listingId}/publish | 버전·게시 조건 확인 후 공개 |
| POST | /api/v2/listings/{listingId}/hide | 숨김 |
| POST | /api/v2/listings/{listingId}/complete | 거래 완료 |
| POST | /api/v2/listings/{listingId}/reopen | 거래 재개 |
| GET | /api/v2/listings | 공개 검색 |
| GET | /api/v2/listings/map | 지도 범위 조회 |
| GET | /api/v2/listings/{listingId} | 공개 상세 |
| GET | /api/v2/listings/{listingId}/assets/{assetId} | 해당 매물의 공개 사진·모델 |
| PUT / DELETE | /api/v2/listings/{listingId}/favorite | 관심 매물 |
| POST | /api/v2/listings/{listingId}/inquiries | 문의 |

공간별 modelSelections는 spaceId/selectedModelId/modelAssetId를 함께 지정한다. 서로 다른 작업과 자산을 임의 연결하지 않는다. 버전 충돌 때는 최신 값을 확인하고 사용자 의도에 맞게 다시 처리한다. [ListingDto](../src/main/kotlin/com/hanyang/backend/domain/listing/ListingDto.kt)를 기준으로 필수 필드를 확인한다.

## AI 내부 통신

GET /api/internal/ai/assets/{assetId}/content와 POST /api/internal/ai/webhook는 AI 전용이며 X-AI-Service-Key를 사용한다. 브라우저에 키를 넣거나 공개 프록시로 내부 경로를 열지 않는다. 원본 GET과 callback 양방향 접근이 필요하다.

백엔드의 AI 요청은 /api/v1/rooms/analyze → /api/v1/rooms/{aiRoomId}/correction → /api/v1/rooms/{aiRoomId}/reconstruct다. AI의 room UUID와 사용자 API의 정수 modelId/spaceId를 구분한다. AI 소스·가중치는 별도 팀원 범위로 공개하지 않는다.
