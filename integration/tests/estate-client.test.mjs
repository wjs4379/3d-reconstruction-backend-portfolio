import test from 'node:test';
import assert from 'node:assert/strict';
import { createEstateClient, EstateApiError } from '../estate-client.mjs';

const sessionId = '8e71aee1-bb22-4d3e-aabb-556677889900';
const baseUrl = 'http://127.0.0.1:18084/api';
const jpeg = () => new Blob(['jpeg-fixture'], { type: 'image/jpeg' });
const glb = () => new Blob(['glb-fixture'], { type: 'model/gltf-binary' });
function fixture(replies) {
  const calls = [];
  const client = createEstateClient({ baseUrl, accessToken: () => 'fixture-bearer', fetchImpl: async (url, options) => {
    calls.push({ url, ...options });
    const r = replies.shift();
    assert.ok(r, `Unexpected request ${url}`);
    return r.status === 204 ? new Response(null, { status: 204 }) : new Response(JSON.stringify(r.body), { status: r.status ?? 200, headers: { 'Content-Type': 'application/json' } });
  } });
  return { client, calls };
}
const session = (url = `/api/v2/media/upload-sessions/${sessionId}/content`, type = 'image/jpeg') => ({ body: { id: sessionId, method: 'PUT', uploadUrl: url, headers: { 'Content-Type': type } } });

test('four photos use authenticated create PUT complete, preserving input order', async () => {
  const replies = Array.from({ length: 4 }, (_, i) => [session(), { status: 204 }, { body: { id: i + 1, kind: 'IMAGE', status: 'VERIFIED' } }]).flat();
  const { client, calls } = fixture(replies);
  const assets = await client.uploadSourcePhotos({ unitId: 7, photos: [jpeg(), jpeg(), jpeg(), jpeg()] });
  assert.deepEqual(assets.map(x => x.id), [1, 2, 3, 4]);
  assert.equal(calls.length, 12);
  calls.forEach(call => assert.equal(call.headers.Authorization, 'Bearer fixture-bearer'));
  assert.deepEqual(calls.map(c => c.method), ['POST', 'PUT', 'POST', 'POST', 'PUT', 'POST', 'POST', 'PUT', 'POST', 'POST', 'PUT', 'POST']);
  assert.deepEqual(JSON.parse(calls[0].body), { unitId: 7, kind: 'IMAGE', contentType: 'image/jpeg', sizeBytes: 12 });
});

test('invalid photo count and invalid later file cause no network or partial upload', async () => {
  const { client, calls } = fixture([]);
  await assert.rejects(client.uploadSourcePhotos({ unitId: 7, photos: [jpeg()] }), /four/);
  await assert.rejects(client.uploadSourcePhotos({ unitId: 7, photos: [jpeg(), jpeg(), jpeg(), new Blob(['bad'], { type: 'text/plain' })] }), /JPEG/);
  assert.equal(calls.length, 0);
});

test('analysis sends four unique verified asset IDs to V2 only', async () => {
  const { client, calls } = fixture([{ body: { modelId: 14 } }]);
  assert.throws(() => client.analyze(9, [1, 2, 3, 3]), /different/);
  await client.analyze(9, [1, 2, 3, 4]);
  assert.equal(calls[0].url, baseUrl + '/v2/spaces/9/models/analyze');
  assert.deepEqual(JSON.parse(calls[0].body), { imageAssetIds: [1, 2, 3, 4] });
});

test('read correction and refresh always keep an explicit model ID', async () => {
  const { client, calls } = fixture([{ body: {} }, { body: {} }, { body: {} }]);
  await client.getModel(9, 14);
  const correction = { vertices: [{ imageIndex: 0, z: 0 }], edges: [] };
  await client.reconstruct(9, 14, correction);
  await client.refreshModel(9, 14);
  assert.deepEqual(calls.map(c => c.url), ['/v2/spaces/9/models/14', '/v2/spaces/9/models/14/reconstruct', '/v2/spaces/9/models/14/refresh'].map(x => baseUrl + x));
  assert.deepEqual(JSON.parse(calls[1].body), correction);
});

test('generated model upload binds unit space model and four source assets atomically', async () => {
  const { client, calls } = fixture([session(undefined, 'model/gltf-binary'), { status: 204 }, { body: { id: 48, sourceModelId: 14 } }]);
  const result = await client.uploadGeneratedModel({ unitId: 7, spaceId: 9, modelId: 14, sourceImageAssetIds: [1, 2, 3, 4], data: glb() });
  assert.equal(result.sourceModelId, 14);
  assert.deepEqual(JSON.parse(calls[0].body), { unitId: 7, kind: 'MODEL', contentType: 'model/gltf-binary', sizeBytes: 11, spaceId: 9, modelId: 14, sourceImageAssetIds: [1, 2, 3, 4] });
  assert.ok(calls.every(c => !c.url.includes('/s3/') && !c.url.includes('/result')));
});

test('generated upload cannot silently create a manual model when modelId is missing', () => {
  const { client, calls } = fixture([]);
  assert.throws(() => client.uploadGeneratedModel({ unitId: 7, spaceId: 9, sourceImageAssetIds: [1, 2, 3, 4], data: glb() }), /integer/);
  assert.equal(calls.length, 0);
});

test('manual upload deliberately omits modelId and may omit source photos', async () => {
  const { client, calls } = fixture([session(undefined, 'model/gltf-binary'), { status: 204 }, { body: { id: 49, sourceModelId: 15 } }]);
  await client.uploadManualModel({ unitId: 7, spaceId: 9, data: glb() });
  const body = JSON.parse(calls[0].body);
  assert.deepEqual(body.sourceImageAssetIds, []);
  assert.equal(body.modelId, undefined);
});

test('same origin API paths cannot normalize into an attacker origin', () => {
  for (const baseUrl of ['/\\attacker.invalid/api', '/\t/attacker.invalid/api', '//attacker.invalid/api', '/api\u0000x', 'https://user:password@server.invalid/api']) {
    assert.throws(() => createEstateClient({ baseUrl, accessToken: () => 'fixture-bearer', fetchImpl: async () => assert.fail('must not call fetch') }), /URL|path|credentials/);
  }
});

test('failed PUT never calls upload completion', async () => {
  const { client, calls } = fixture([session(undefined, 'model/gltf-binary'), { status: 413, body: { code: 'FILE_SIZE_MISMATCH', message: '크기 불일치' } }]);
  await assert.rejects(client.uploadGeneratedModel({ unitId: 7, spaceId: 9, modelId: 14, sourceImageAssetIds: [1, 2, 3, 4], data: glb() }), e => e instanceof EstateApiError && e.status === 413 && e.code === 'FILE_SIZE_MISMATCH');
  assert.equal(calls.length, 2);
});

test('foreign upload target cannot receive a bearer token or blob', async () => {
  const { client, calls } = fixture([session('https://foreign.invalid/collect', 'model/gltf-binary')]);
  await assert.rejects(client.uploadGeneratedModel({ unitId: 7, spaceId: 9, modelId: 14, sourceImageAssetIds: [1, 2, 3, 4], data: glb() }), e => e.code === 'INVALID_UPLOAD_TARGET');
  assert.equal(calls.length, 1);
});

test('wrong session type or malformed ID cannot produce a PUT', async () => {
  const { client, calls } = fixture([session(undefined, 'text/plain')]);
  await assert.rejects(client.uploadGeneratedModel({ unitId: 7, spaceId: 9, modelId: 14, sourceImageAssetIds: [1, 2, 3, 4], data: glb() }), e => e.code === 'INVALID_UPLOAD_TARGET');
  assert.equal(calls.length, 1);
});

test('private asset downloads use the JWT, public furniture and code exchange do not', async () => {
  const { client, calls } = fixture([{ body: {} }, { body: [] }, { body: {} }]);
  assert.ok((await client.privateAssetBlob(48)) instanceof Blob);
  await client.furnitureCatalog();
  await client.loginWithKakaoCode('one-use-code');
  assert.equal(calls[0].headers.Authorization, 'Bearer fixture-bearer');
  assert.equal(calls[1].headers.Authorization, undefined);
  assert.equal(calls[2].headers.Authorization, undefined);
  assert.deepEqual(JSON.parse(calls[2].body), { code: 'one-use-code' });
});

test('missing login refuses private requests without calling fetch', async () => {
  let count = 0;
  const client = createEstateClient({ baseUrl, accessToken: () => '', fetchImpl: async () => { count++; } });
  await assert.rejects(client.me(), e => e.status === 401);
  assert.equal(count, 0);
});

test('catalog keeps the backend PC origin and rejects foreign model URLs', async () => {
  const { client, calls } = fixture([{ body: [{ type: 'air_conditioner', modelUrl: '/api/v2/furniture/air_conditioner/content', sizeMeters: [0.363, 1.883, 0.294] }] }, { body: [{ type: 'microwave', modelUrl: 'https://foreign.invalid/file.glb' }] }]);
  const [item] = await client.furnitureCatalog();
  assert.equal(item.modelUrl, baseUrl + '/v2/furniture/air_conditioner/content');
  assert.deepEqual(item.sizeMeters, [0.363, 1.883, 0.294]);
  await assert.rejects(client.furnitureCatalog(), e => e.code === 'INVALID_FURNITURE_CATALOG');
  assert.ok(calls.every(c => c.headers.Authorization === undefined));
});

test('an aborted upload forwards the signal and never registers completion', async () => {
  const controller = new AbortController(); controller.abort();
  let count = 0;
  const client = createEstateClient({ baseUrl, accessToken: () => 'fixture-bearer', fetchImpl: async (_, { signal }) => { count++; signal.throwIfAborted(); } });
  await assert.rejects(client.uploadSourcePhotos({ unitId: 7, photos: [jpeg(), jpeg(), jpeg(), jpeg()], signal: controller.signal }), { name: 'AbortError' });
  assert.equal(count, 1);
});
