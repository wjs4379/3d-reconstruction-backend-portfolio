# Frontend–Backend 3D API Contract

이 문서는 방 생성부터 AI 분석, 사용자 보정, 최종 3D 재구성까지 프론트엔드가 사용하는 단일 API 계약입니다.

## 처리 순서

```text
방 생성
→ 여러 이미지 분석 요청
→ 상태 Polling
→ WAITING_FOR_CORRECTION에서 좌표 편집
→ 보정 좌표와 함께 재구성 요청
→ 상태 Polling
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
- `imageUrls`는 비어 있지 않은 배열이어야 합니다.
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
    "vertices": [],
    "edges": []
  }
}
```

`roomData`는 JSON 문자열이 아니라 객체입니다. 별도의 `JSON.parse()`를 호출하지 않습니다.

| status | 프론트 동작 |
| --- | --- |
| `ANALYZING` | Polling 유지 |
| `WAITING_FOR_CORRECTION` | `roomData`로 좌표 편집 화면 표시 |
| `RECONSTRUCTING` | Polling 유지 |
| `SUCCESS` | `modelUrl`로 최종 모델 표시 |
| `FAILED` | 오류 안내 후 재시도 여부 결정 |

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

## 오류 응답

```json
{
  "code": "INVALID_MODEL_STATE",
  "message": "현재 상태에서는 재구성을 시작할 수 없습니다. status=ANALYZING"
}
```

- 잘못된 요청 값 또는 JSON: `400 Bad Request`
- 현재 작업 상태와 맞지 않는 요청: `409 Conflict`

## 더 이상 사용하지 않는 경로와 필드

다음 경로는 중복 계약을 제거하면서 삭제했습니다.

```text
GET  /api/rooms/{roomId}/status
GET  /api/rooms/{roomId}
POST /api/rooms/{roomId}/correction
PATCH /api/rooms/{roomId}/models/correction
```

방 생성 요청의 `originalImageUrl`도 삭제했습니다. 모든 이미지 URL은 분석 요청의 `imageUrls` 배열로 전달합니다.
