/** Browser-compatible client for the backend's verified V2 workflow.
 * The caller owns authentication state and rendering. No tokens are persisted here.
 */
export class EstateApiError extends Error {
  constructor(message, { status = 0, code = 'NETWORK_ERROR' } = {}) {
    super(message); this.name = 'EstateApiError'; this.status = status; this.code = code;
  }
}

const id = value => {
  if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError('A positive safe integer ID is required.');
  return value;
};
const imageIds = values => {
  if (!Array.isArray(values) || values.length !== 4 || new Set(values).size !== 4) {
    throw new TypeError('Four different verified image asset IDs are required.');
  }
  return values.map(id);
};
const optionalImageIds = values => {
  if (!Array.isArray(values) || values.length > 20 || new Set(values).size !== values.length) {
    throw new TypeError('Use at most twenty different verified image asset IDs.');
  }
  return values.map(id);
};
const file = (blob, kind) => {
  const types = kind === 'IMAGE' ? ['image/jpeg', 'image/png'] : ['model/gltf-binary'];
  const limit = kind === 'IMAGE' ? 5242880 : 26214400;
  if (!blob || typeof blob.arrayBuffer !== 'function' || !Number.isSafeInteger(blob.size) ||
      blob.size < 1 || blob.size > limit || !types.includes(blob.type)) {
    throw new TypeError(kind === 'IMAGE' ? 'Use a JPEG/PNG photo up to 5 MiB.' : 'Use a GLB model up to 25 MiB.');
  }
  return blob;
};

export function createEstateClient({ baseUrl = '/api', accessToken, fetchImpl = globalThis.fetch } = {}) {
  if (typeof fetchImpl !== 'function' || typeof accessToken !== 'function') throw new TypeError('Provide fetch and an accessToken getter.');
  const base = String(baseUrl).replace(/\/+$/, '');
  if (!base || /[?#\\\u0000-\u0020\u007f]/.test(base) || !(base.startsWith('/') && !base.startsWith('//') || /^https?:\/\//.test(base))) {
    throw new TypeError('Use an HTTP backend base URL or a same-origin API path.');
  }
  if (/^https?:/.test(base)) {
    const parsed = new URL(base);
    if (parsed.username || parsed.password) throw new TypeError('Backend URLs must not contain credentials.');
  }
  async function request(path, { method = 'GET', body, signal, auth = true, blob = false } = {}) {
    const headers = { Accept: blob ? '*/*' : 'application/json' };
    if (auth) {
      const token = accessToken();
      if (typeof token !== 'string' || !token || /[\r\n]/.test(token)) throw new EstateApiError('로그인이 필요합니다.', { status: 401, code: 'AUTHENTICATION_REQUIRED' });
      headers.Authorization = `Bearer ${token}`;
    }
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetchImpl(base + path, { method, headers, signal, redirect: blob ? 'follow' : 'error', ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    if (!response.ok) {
      const error = await response.json().catch(() => null);
      throw new EstateApiError(error?.message || `요청을 처리하지 못했습니다. (${response.status})`, { status: response.status, code: error?.code || 'REQUEST_FAILED' });
    }
    return blob ? response.blob() : response.status === 204 ? undefined : response.json();
  }

  async function upload({ unitId, kind, data, spaceId, modelId, sourceImageAssetIds, signal }) {
    id(unitId); file(data, kind);
    const body = { unitId, kind, contentType: data.type, sizeBytes: data.size };
    if (kind === 'MODEL') {
      body.spaceId = id(spaceId);
      if (modelId !== undefined) body.modelId = id(modelId);
      body.sourceImageAssetIds = modelId === undefined ? optionalImageIds(sourceImageAssetIds ?? []) : imageIds(sourceImageAssetIds);
    }
    const session = await request('/v2/media/upload-sessions', { method: 'POST', body, signal });
    if (typeof session.id !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(session.id)) {
      throw new EstateApiError('업로드 세션 응답을 확인할 수 없습니다.', { code: 'INVALID_UPLOAD_SESSION' });
    }
    const path = `/v2/media/upload-sessions/${session.id}/content`;
    const expected = base + path;
    const relative = `/api/v2/media/upload-sessions/${session.id}/content`;
    if (session.method !== 'PUT' || ![expected, relative].includes(session.uploadUrl) || session.headers?.['Content-Type'] !== data.type) {
      throw new EstateApiError('서버가 발급한 업로드 경로와 파일 형식이 일치하지 않습니다.', { code: 'INVALID_UPLOAD_TARGET' });
    }
    // Do not send the bearer token to an arbitrary URL returned by another service.
    const token = accessToken();
    if (typeof token !== 'string' || !token || /[\r\n]/.test(token)) throw new EstateApiError('로그인이 필요합니다.', { status: 401, code: 'AUTHENTICATION_REQUIRED' });
    const response = await fetchImpl(expected, { method: 'PUT', headers: { Authorization: `Bearer ${token}`, 'Content-Type': data.type }, body: data, signal, redirect: 'error' });
    if (!response.ok) {
      const error = await response.json().catch(() => null);
      throw new EstateApiError(error?.message || '파일을 업로드하지 못했습니다.', { status: response.status, code: error?.code || 'UPLOAD_FAILED' });
    }
    // Never complete a failed upload or register a URL as a verified asset.
    return request(`/v2/media/upload-sessions/${session.id}/complete`, { method: 'POST', signal });
  }

  return Object.freeze({
    me: options => request('/auth/me', options),
    loginWithKakaoCode: (code, { signal } = {}) => {
      if (typeof code !== 'string' || !code.trim() || code.length > 8192) throw new TypeError('A Kakao authorization code is required.');
      return request('/auth/kakao/code', { method: 'POST', body: { code }, signal, auth: false });
    },
    async uploadSourcePhotos({ unitId, photos, signal }) {
      id(unitId);
      if (!Array.isArray(photos) || photos.length !== 4) throw new TypeError('Exactly four photos are required.');
      photos.forEach(photo => file(photo, 'IMAGE'));
      // Validate all inputs before creating an upload session. The server also verifies bytes and duplicates.
      const result = [];
      for (const photo of photos) result.push(await upload({ unitId, kind: 'IMAGE', data: photo, signal }));
      return result;
    },
    analyze: (spaceId, assets, { signal } = {}) => request(`/v2/spaces/${id(spaceId)}/models/analyze`, { method: 'POST', body: { imageAssetIds: imageIds(assets) }, signal }),
    listModels: (spaceId, { signal } = {}) => request(`/v2/spaces/${id(spaceId)}/models`, { signal }),
    getModel: (spaceId, modelId, { signal } = {}) => request(`/v2/spaces/${id(spaceId)}/models/${id(modelId)}`, { signal }),
    reconstruct: (spaceId, modelId, correction, { signal } = {}) => request(`/v2/spaces/${id(spaceId)}/models/${id(modelId)}/reconstruct`, { method: 'POST', body: correction, signal }),
    refreshModel: (spaceId, modelId, { signal } = {}) => request(`/v2/spaces/${id(spaceId)}/models/${id(modelId)}/refresh`, { method: 'POST', signal }),
    uploadGeneratedModel: ({ unitId, spaceId, modelId, sourceImageAssetIds, data, signal }) => {
      id(modelId);
      return upload({ unitId, spaceId, modelId, sourceImageAssetIds, data, kind: 'MODEL', signal });
    },
    uploadManualModel: ({ unitId, spaceId, sourceImageAssetIds, data, signal }) => upload({ unitId, spaceId, sourceImageAssetIds, data, kind: 'MODEL', signal }),
    // Read using asset ID and Bearer instead of passing a protected modelUrl directly to useGLTF.
    privateAssetBlob: (assetId, { signal } = {}) => request(`/v2/media/assets/${id(assetId)}/content`, { signal, blob: true }),
    async furnitureCatalog({ signal } = {}) {
      const catalog = await request('/v2/furniture', { signal, auth: false });
      if (!Array.isArray(catalog)) throw new EstateApiError('가구 목록 응답을 확인할 수 없습니다.', { code: 'INVALID_FURNITURE_CATALOG' });
      return catalog.map(item => {
        if (!/^[a-z_]+$/.test(item.type) || item.modelUrl !== `/api/v2/furniture/${item.type}/content`) {
          throw new EstateApiError('가구 파일 경로를 확인할 수 없습니다.', { code: 'INVALID_FURNITURE_CATALOG' });
        }
        return { ...item, modelUrl: `${base}/v2/furniture/${item.type}/content` };
      });
    },
  });
}
