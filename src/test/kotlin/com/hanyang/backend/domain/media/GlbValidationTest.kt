package com.hanyang.backend.domain.media

import com.hanyang.backend.common.ApiException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlbValidationTest {
    private val validation = MediaValidation(JsonMapper.builder().build())
    private val triangle = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"buffers":[{"byteLength":42}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],"accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}]}"""
    private val binary: ByteArray get() = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        floatArrayOf(0f,0f,0f,1f,0f,0f,0f,1f,0f).forEach(::putFloat)
        putShort(0); putShort(1); putShort(2); putShort(0)
    }.array()
    private fun glb(json: String = triangle, bin: ByteArray = binary): ByteArray {
        val text=json.toByteArray(); val padded=(text.size+3)/4*4
        return ByteBuffer.allocate(28+padded+bin.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546c67); putInt(2); putInt(capacity()); putInt(padded); putInt(0x4e4f534a)
            put(text); put(ByteArray(padded-text.size){32}); putInt(bin.size); putInt(0x004e4942); put(bin)
        }.array()
    }
    private fun rejects(json: String, bin: ByteArray = binary) {
        assertEquals("INVALID_FILE", assertThrows(ApiException::class.java) {
            validation.validate(MediaKind.MODEL,"model/gltf-binary",glb(json,bin))
        }.code)
    }
    @Test fun `indexed triangle and unlit lines preserve their native scene metadata`() {
        validation.validate(MediaKind.MODEL,"model/gltf-binary",glb())
        validation.validate(MediaKind.MODEL,"model/gltf-binary",glb(triangle.replace("\"asset\":", "\"extensionsUsed\":[\"KHR_materials_unlit\"],\"asset\":").replace("\"indices\":1", "\"indices\":1,\"mode\":1")))
    }
    @Test fun `huge zero backed and undersized accessors are rejected before visitor allocation`() {
        rejects(triangle.replace("\"bufferView\":0,", "").replace("\"count\":3", "\"count\":2147483647"))
        rejects(triangle.replace("\"count\":3", "\"count\":999999"))
        rejects(triangle.replace("\"bufferView\":0,", ""))
    }
    @Test fun `buffer ranges strides sparse and nonfinite positions are rejected`() {
        rejects(triangle.replace("\"byteLength\":36", "\"byteLength\":48"))
        rejects(triangle.replace("\"byteLength\":36", "\"byteLength\":36,\"byteStride\":4096"))
        rejects(triangle.replace("\"type\":\"VEC3\"", "\"type\":\"VEC3\",\"sparse\":{}"))
        val bad=binary; ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN)
        rejects(triangle,bad)
        val huge=binary; ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.MAX_VALUE)
        rejects(triangle,huge)
    }
    @Test fun `indices must point to an existing position`() {
        val bad=binary; ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putShort(36,500)
        rejects(triangle,bad)
    }
    @Test fun `cyclic graph and unsupported decompression cannot reach the viewer`() {
        rejects(triangle.replace("\"mesh\":0", "\"mesh\":0,\"children\":[0]"))
        rejects(triangle.replace("\"asset\":", "\"extensionsRequired\":[\"KHR_draco_mesh_compression\"],\"asset\":"))
        rejects(triangle.replace("\"primitives\":[", "\"primitives\":[{\"extensions\":{\"EXT_meshopt_compression\":{}}},"))
    }
    @Test fun `malformed and overflowing node transforms are rejected`() {
        rejects(triangle.replace("\"mesh\":0", "\"mesh\":0,\"matrix\":[1e308]"))
        rejects(triangle.replace("\"mesh\":0", "\"mesh\":0,\"translation\":[1,2]"))
        rejects(triangle.replace("\"mesh\":0", "\"mesh\":0,\"rotation\":[0,0,0,0]"))
        rejects(triangle.replace("\"mesh\":0", "\"mesh\":0,\"scale\":[1e308,1,1]"))
        rejects(triangle.replace("\"nodes\":[{\"mesh\":0}]", "\"nodes\":[{\"scale\":[10000000,10000000,10000000],\"children\":[1]},{\"mesh\":0,\"scale\":[10000000,10000000,10000000]}]"))
    }
    @Test fun `repeated index references are bounded and a reverse ordered deep graph is rejected`() {
        val primitives = (1..2048).joinToString(",") { "{\"attributes\":{\"POSITION\":0},\"indices\":1}" }
        val repeated = triangle.replace("{\"attributes\":{\"POSITION\":0},\"indices\":1}", primitives)
            .replace("\"byteLength\":42","\"byteLength\":2000036")
            .replace("\"byteLength\":6","\"byteLength\":2000000")
            .replace("\"bufferView\":1,\"componentType\":5123,\"count\":3","\"bufferView\":1,\"componentType\":5123,\"count\":1000000")
        val large = ByteArray(2000036); binary.copyInto(large,endIndex=36)
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3)) {
            assertThrows(ApiException::class.java) { validation.validate(MediaKind.MODEL,"model/gltf-binary",glb(repeated,large)) }
            validation.validate(MediaKind.MODEL,"model/gltf-binary",glb(triangle.replace("{\"attributes\":{\"POSITION\":0},\"indices\":1}",primitives)))
        }
        val nodes = (0..140).joinToString(",") { if (it == 0) "{\"mesh\":0}" else "{\"children\":[${it-1}]}" }
        rejects(triangle.replace("\"nodes\":[{\"mesh\":0}]", "\"nodes\":[$nodes]").replace("\"nodes\":[0]", "\"nodes\":[140]"))
    }
    @Test fun `empty scene and external or inline resource requests cannot be published`() {
        rejects(triangle.replace("\"nodes\":[0]", "\"nodes\":[]"))
        rejects(triangle.replace("\"byteLength\":42", "\"byteLength\":42,\"uri\":\"https://private.example/buffer\""))
        rejects(triangle.replace("\"asset\":", "\"images\":[{\"uri\":\"data:image/png;base64,AAAA\"}],\"asset\":"))
    }
}
