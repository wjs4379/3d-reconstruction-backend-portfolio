# 프론트 연동용 V2 클라이언트

`estate-client.mjs`는 백엔드 저장소에서 관리하는 브라우저용 JavaScript 모듈이다. 프론트 화면·세션 저장 방식을 변경하지 않으며, 담당자가 기존 요청 코드에서 가져다 사용할 수 있다. 별도 패키지 설치가 필요 없다.

```js
import { createEstateClient } from './estate-client.mjs';

const client = createEstateClient({
  baseUrl: '/api', // Vite 프록시. 다른 PC로 직접 연결할 때는 실제 백엔드 주소 + /api
  accessToken: () => session.tokenInfo.accessToken,
});
```

## 사진 4장과 명시적인 작업 ID

```js
const assets = await client.uploadSourcePhotos({ unitId, photos: fourFiles });
const imageAssetIds = assets.map(asset => asset.id);
const model = await client.analyze(spaceId, imageAssetIds);

// 이후 모든 조회·보정은 처음 받은 modelId로 진행한다.
const current = await client.getModel(spaceId, model.modelId);
// WAITING_FOR_CORRECTION일 때 원본 사진 좌표와 점/간선 ID를 유지해 전달한다.
await client.reconstruct(spaceId, model.modelId, { vertices, edges });
```

사진은 JPEG·PNG 각 5 MiB 이내이며 정확히 네 장이다. 백엔드가 실제 이미지 내용, 원본 크기, 서로 다른 체크섬과 소유권을 검증한다. 분석·재구성 중에는 같은 작업을 조회하고, `FAILED`이면 자동 반복 요청 대신 오류를 표시한다. 필요할 때 `refreshModel(spaceId, modelId)`로 AI 상태를 재조회할 수 있다.

## 방과 가구가 포함된 GLB의 저장

```js
// WAITING_FOR_MODEL_UPLOAD일 때 프론트 GLBExporter의 결과를 저장한다.
const data = new Blob([exportedArrayBuffer], { type: 'model/gltf-binary' });
const asset = await client.uploadGeneratedModel({
  unitId, spaceId, modelId: model.modelId,
  sourceImageAssetIds: imageAssetIds, data,
});
const completed = await client.getModel(spaceId, model.modelId);
```

이 모듈은 인증된 업로드 세션 발급, 파일 PUT, 완료 등록 순서로 처리한다. PUT이 실패하면 완료 등록을 하지 않으며, 임의의 파일 URL로 모델 완료를 요청하지 않는다. `asset.sourceModelId`와 기존 `modelId`가 연결되고 최종 완료는 서버가 판단한다. `uploadGeneratedModel`은 modelId가 없으면 거부한다. 이미 완성된 GLB를 새 수동 모델로 등록할 때만 별도 `uploadManualModel`을 사용하며 원본 사진 ID는 선택 사항이다.

```js
// 보호된 파일 URL을 인증 없이 useGLTF에 직접 전달하지 않는다.
const blob = await client.privateAssetBlob(completed.assetId);
const objectUrl = URL.createObjectURL(blob);
try {
  // GLTFLoader.loadAsync(objectUrl), 또는 해당 화면의 뷰어에 전달한다.
} finally {
  // 뷰어가 읽기를 마치거나 화면을 닫을 때 해제한다.
  URL.revokeObjectURL(objectUrl);
}
```

## AWS 없이 가구 파일 제공

```sh
python3 scripts/import_furniture_catalog.py \
  --source-dir /absolute/path/to/original-furniture \
  --output-dir /absolute/path/to/local-media/furniture
```

백엔드는 기본적으로 `MEDIA_LOCAL_DIRECTORY/furniture`에서 검증된 가구 파일을 읽는다. 일반 Spring 실행에서는 `FURNITURE_LOCAL_DIRECTORY`로 별도 경로를 지정할 수 있다. 격리 실행 도구 `real_integration.py`를 사용할 때는 도구가 관리하는 `runtime/media/furniture` 경로에 준비하면 기존 설정 형식 그대로 사용할 수 있다. 파일은 Git·JAR에 포함하지 않는다.

```js
const furniture = (await client.furnitureCatalog())
  .filter(item => item.available)
  .map(item => ({ ...item, size: item.sizeMeters }));
// 각 항목의 modelUrl을 GLTFLoader/useGLTF에 사용한다.
```

가구는 백엔드의 고정 카탈로그에 있는 여섯 모델만 제공한다. 각 URL은 지정한 백엔드 origin을 유지한다. 파일명, byte 수, SHA256과 GLB 구조를 검증한 후 `model/gltf-binary`로 응답한다. 에어컨 크기는 `[0.363, 1.883, 0.294]`m다. 가구의 미터 크기와 AI가 만든 방의 좌표 단위는 별도이므로 모델링 좌표계를 팀과 맞춰 적용한다. 파일을 불러오지 못한 대체 상자를 실제 가구 모델로 저장하지 않도록 화면에서 구분한다.

## 로그인 연결

기존 프론트의 state 검증을 통과한 인가 코드만 `loginWithKakaoCode(code)`로 교환한다. 반환된 서비스 JWT는 프론트의 기존 세션 저장 함수를 통해 보관한다. 이 모듈은 카카오 Client secret을 받거나 저장하지 않는다. 앱 ID·REST 키·Client secret·고정 Redirect URI는 백엔드 비공개 설정으로 관리한다. 기본 로그인 설정은 카카오의 활성화된 Client secret 흐름을 사용한다.

## 검증

```sh
node --test integration/tests/*.test.mjs
```

업로드 순서, 실패 PUT의 완료 금지, 정확한 작업 ID, 토큰 전달 대상, 사설 파일 인증, 가구 주소 정규화와 취소 동작을 검사한다. 이 클라이언트 검사만으로 실제 소셜 로그인이나 GPU 추론이 실행된 것으로 판단하지 않는다.
