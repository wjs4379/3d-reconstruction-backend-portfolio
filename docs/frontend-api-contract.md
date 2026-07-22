# Frontend–Backend 3D API Contract

이 문서는 방 생성부터 AI 분석, 사용자 보정, 최종 좌표 기반 모델 파일 등록까지 프론트엔드가 사용하는 단일 API 계약입니다.

> 프론트엔드는 `POST /api/webhook`을 호출하지 않습니다. 이 경로는 AI 서버의 좌표 결과 수신 전용입니다. 최종 `modelUrl`은 반드시 `PUT /api/rooms/{roomId}/models/{modelId}/result`로 등록합니다.

## 처리 순서

```text
방 생성
→ 여러 이미지 분석 요청
→ 상태 Polling
→ WAITING_FOR_CORRECTION에서 좌표 편집
→ 보정 좌표와 함께 재구성 요청
→ 상태 Polling
→ WAITING_FOR_MODEL_UPLOAD에서 최종 좌표로 모델 생성
→ 최종 모델 파일을 영속 저장소에 업로드
→ modelUrl과 파일 메타데이터 등록
→ SUCCESS에서 modelUrl 표시
```

## 1. 방 생성

```http
POST /api/rooms
Content-Type: application/json
```

```json
{
  "propertyId": 1,
  "ownerId": 1,
  "roomName": "거실",
  "pricePerNight": 100000,
  "maxGuests": 2
}
```

- 성공 상태: `201 Created`
- 응답의 `roomId`를 이후 모든 3D 작업 경로에 사용합니다.
- 이미지 URL은 이 요청에 포함하지 않습니다.

## 2. 여러 이미지 분석 시작

```http
POST /api/rooms/{roomId}/models/analyze
Content-Type: application/json
```

```json
{
  "imageUrls": [
    "https://bucket.example/room-1.jpg",
    "https://bucket.example/room-2.jpg"
  ]
}
```

- 성공 상태: `202 Accepted`
- `imageUrls`는 비어 있지 않은 문자열을 최소 1개 포함해야 합니다. 현재 백엔드는 각 문자열의 URL 형식이나 S3 주소 여부까지 검증하지 않습니다.
- 정상 응답의 초기 상태는 `ANALYZING`입니다.

## 3. 상태 조회

```http
GET /api/rooms/{roomId}/models/status
```

```json
{
  "modelId": 10,
  "roomId": 1,
  "status": "WAITING_FOR_CORRECTION",
  "modelUrl": null,
  "roomData": {
    "aiVertices": [],
    "aiEdges": []
  }
}
```

`roomData`는 JSON 문자열이 아니라 객체입니다. 별도의 `JSON.parse()`를 호출하지 않습니다. 1차 AI 결과는 `aiVertices`·`aiEdges`, 최종 재구성 결과는 `finalVertices`·`finalEdges` 키를 사용합니다. 프론트가 보정·재구성 API를 호출할 때에는 편집한 배열을 요청 필드 `vertices`·`edges`로 전달합니다.

| status | 프론트 동작 |
| --- | --- |
| `ANALYZING` | Polling 유지 |
| `WAITING_FOR_CORRECTION` | `roomData`로 좌표 편집 화면 표시 |
| `RECONSTRUCTING` | Polling 유지 |
| `WAITING_FOR_MODEL_UPLOAD` | `roomData`의 최종 좌표로 모델을 생성하고 영속 저장소에 업로드 |
| `SUCCESS` | `modelUrl`로 최종 모델 표시 |
| `FAILED` | 오류 안내 후 재시도 여부 결정 |

AI가 재구성을 마치고 최종 좌표를 `SUCCESS` Webhook으로 전달하면, 백엔드 상태는 곧바로 `SUCCESS`가 되지 않고 `WAITING_FOR_MODEL_UPLOAD`로 전이합니다. 이때 상태 조회 응답은 다음과 같습니다.

```json
{
  "modelId": 10,
  "roomId": 1,
  "status": "WAITING_FOR_MODEL_UPLOAD",
  "modelUrl": null,
  "roomData": {
    "finalVertices": [],
    "finalEdges": []
  }
}
```

프론트는 이 응답의 `modelId`와 최종 좌표를 사용해 다음 단계를 진행합니다. AI 요청 본문에 레거시 `modelUrl`, `fileSizeMb`, `format` 필드가 `null`로 포함되더라도 백엔드는 이를 사용하지 않습니다. 최종 `modelUrl`의 유일한 등록 경로는 프론트의 결과 등록 `PUT` API입니다.

## 4. 보정 좌표 제출 및 재구성 시작

```http
POST /api/rooms/{roomId}/models/reconstruct
Content-Type: application/json
```

```json
{
  "vertices": [
    {
      "id": "vertex-1",
      "x": 10.0,
      "y": 20.0,
      "z": 0.0,
      "imageIndex": 0
    }
  ],
  "edges": []
}
```

- 성공 상태: `202 Accepted`
- 프론트는 correction과 reconstruct를 별도로 호출하지 않습니다.
- 백엔드가 AI correction 응답 완료 후 reconstruct 요청을 순서대로 전송합니다.
- 정상 응답 상태는 `RECONSTRUCTING`입니다.
- `vertices`는 최소 1개가 필요하며 vertex·edge ID는 비어 있거나 중복될 수 없습니다.
- 모든 좌표는 유한한 숫자여야 하고, 각 edge의 양 끝점은 요청의 vertex ID를 참조해야 합니다.

## 5. 최종 모델 파일 업로드 결과 등록

`WAITING_FOR_MODEL_UPLOAD` 상태에서 프론트는 `roomData`의 최종 좌표로 모델 파일을 생성하고 S3 등의 영속 저장소에 업로드합니다. 업로드가 끝나면 상태 조회에서 받은 `modelId`로 결과를 등록합니다.

```http
PUT /api/rooms/{roomId}/models/{modelId}/result
Content-Type: application/json
```

```json
{
  "modelUrl": "https://bucket.example/models/room-1.glb",
  "fileSizeMb": 12.34,
  "format": "glb"
}
```

- 성공 상태: `200 OK`
- `modelUrl`은 필수이며, 2,048자 이하이면서 호스트를 포함한 유효한 `http` 또는 `https` 절대 주소여야 합니다.
- 프론트 계약상 `modelUrl`은 세션이 끝난 뒤에도 조회 가능한 S3/CDN 등의 영속 URL이어야 합니다. 현재 백엔드는 URL의 접근 가능성이나 만료 여부는 검사하지 않고 `http`·`https` 절대 주소와 호스트 형식만 검증합니다.
- `fileSizeMb`와 `format`은 선택값입니다. `fileSizeMb`는 양수이면서 정수 8자리와 소수 2자리까지 허용되고, `format`은 비어 있지 않은 20자 이하 문자열이어야 합니다. 전달한 `format`은 소문자로 정규화됩니다.
- 백엔드는 `roomId`와 `modelId`가 같은 작업을 가리키는지 확인합니다.
- 정상 처리 시 URL과 메타데이터를 저장하고 상태를 `SUCCESS`로 전이합니다.
- `PUT` 응답에 최종 `SUCCESS`와 `modelUrl`이 포함되므로 프론트는 이 응답을 바로 사용할 수 있습니다.
- 저장 상태를 다시 확인하려면 프론트가 `GET /api/rooms/{roomId}/models/status`를 명시적으로 호출합니다. 백엔드는 `PUT` 이후 `GET`을 자동 호출하지 않습니다.
- `fileSizeMb`와 `format`은 DB에 저장되지만 현재 상태 응답 DTO에는 포함되지 않습니다.
- 이미 `SUCCESS`인 작업에 같은 `modelUrl`과 동일한 메타데이터를 다시 보내는 요청은 멱등하게 성공합니다. 재요청에서 선택 메타데이터를 생략할 수는 있지만, 다른 URL이나 충돌하는 메타데이터로는 덮어쓸 수 없습니다.

```json
{
  "modelId": 10,
  "roomId": 1,
  "status": "SUCCESS",
  "modelUrl": "https://bucket.example/models/room-1.glb",
  "roomData": {
    "finalVertices": [],
    "finalEdges": []
  }
}
```

`POST /api/rooms/{roomId}/models/refresh`는 Webhook 유실에 대비해 AI 처리 상태와 1차 또는 최종 좌표를 다시 조회해 반영합니다. 최종 모델 URL을 만들거나 가져오는 API가 아니므로, 모델 업로드 완료 처리는 반드시 위 `PUT` API를 사용합니다.

## 오류 응답

```json
{
  "code": "INVALID_MODEL_STATE",
  "message": "현재 상태에서는 재구성을 시작할 수 없습니다. status=ANALYZING"
}
```

- 잘못된 요청 값 또는 JSON: `400 Bad Request`
- 존재하지 않는 `roomId`, `modelId`, `aiRoomId`: 현재 `400 Bad Request`의 `INVALID_ARGUMENT`
- 현재 작업 상태와 맞지 않는 요청: `409 Conflict`
- `WAITING_FOR_MODEL_UPLOAD` 이전의 최종 결과 등록 또는 완료된 모델의 URL 변경: `409 Conflict`

## 더 이상 사용하지 않는 경로와 필드

다음 경로는 중복 계약을 제거하면서 삭제했습니다.

```text
GET  /api/rooms/{roomId}/status
GET  /api/rooms/{roomId}
POST /api/rooms/{roomId}/correction
PATCH /api/rooms/{roomId}/models/correction
```

방 생성 요청의 `originalImageUrl`도 삭제했습니다. 모든 이미지 URL은 분석 요청의 `imageUrls` 배열로 전달합니다.
