package com.hanyang.backend.domain.furniture

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.media.MediaValidation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class FurnitureCatalogTest {
    @TempDir lateinit var directory: Path
    private val validation = MediaValidation(JsonMapper.builder().build())

    @Test
    fun `canonical six item catalog fixes missing object names and meter dimensions without exposing local paths`() {
        val catalog = FurnitureCatalog("", validation)
        val entries = catalog.list()
        assertEquals(6, entries.size)
        assertEquals(6, entries.map { it.type }.distinct().size)
        assertTrue(entries.none { it.available })
        assertEquals(listOf(0.363, 1.883, 0.294), entries.single { it.type == "air_conditioner" }.sizeMeters)
        assertEquals("refridgerators-RB10FSR4ESRAA.glb", furnitureDefinitions.single { it.type == "small_fridge" }.filename)
        assertEquals("samsung_ac_363x1883x294.glb", furnitureDefinitions.single { it.type == "air_conditioner" }.filename)
        entries.forEach { assertEquals("/api/v2/furniture/${it.type}/content", it.modelUrl); assertTrue(it.sha256.matches(Regex("[0-9a-f]{64}"))) }
    }

    @Test
    fun `unknown type or filesystem and remote URL input never become a storage path`() {
        val catalog = FurnitureCatalog(directory.toString(), validation)
        for (type in listOf("missing", "../file", "/etc/passwd", "https://example.com/model.glb", "microwave/../../secret")) {
            val error = assertThrows(ApiException::class.java) { catalog.read(type) }
            assertEquals("FURNITURE_NOT_FOUND", error.code)
            assertFalse(error.message.orEmpty().contains(directory.toString()))
        }
    }

    @Test
    fun `verified original bytes are readable without a network source or supplied content type`() {
        val bytes = triangle()
        val definition = definition(bytes)
        Files.write(directory.resolve(definition.filename), bytes)
        assertArrayEquals(bytes, VerifiedFurnitureFiles(directory, validation).read(definition))
    }

    @Test
    fun `unconfigured missing oversized truncated and substituted files are unavailable rather than a fallback model`() {
        val bytes = triangle()
        val definition = definition(bytes)
        val store = VerifiedFurnitureFiles(directory, validation)
        rejects { VerifiedFurnitureFiles(null, validation).read(definition) }
        rejects { store.read(definition) }
        Files.write(directory.resolve(definition.filename), bytes.copyOf(bytes.size - 1))
        rejects { store.read(definition) }
        Files.write(directory.resolve(definition.filename), ByteArray(VerifiedFurnitureFiles.MAX_BYTES + 1))
        rejects { store.read(definition) }
        val changed = bytes.clone().also { it[it.lastIndex] = 1 }
        Files.write(directory.resolve(definition.filename), changed)
        rejects { store.read(definition) }
        // Pinned expected length is bounded even before any filesystem operation.
        rejects { store.read(definition.copy(byteLength = Int.MAX_VALUE)) }
    }

    @Test
    fun `a checksum match cannot make an invalid header or external gltf resource publishable`() {
        val bytes = triangle().also { it[0] = 0 }
        Files.write(directory.resolve("fixture.glb"), bytes)
        rejects { VerifiedFurnitureFiles(directory, validation).read(definition(bytes)) }
        val external = triangle(external = true)
        Files.write(directory.resolve("fixture.glb"), external)
        rejects { VerifiedFurnitureFiles(directory, validation).read(definition(external)) }
    }

    @Test
    fun `symlink file and configured directory are rejected even when target bytes match`() {
        val bytes = triangle()
        val definition = definition(bytes)
        val outside = Files.createTempFile(directory.parent, "catalog-outside-", ".glb")
        try {
            Files.write(outside, bytes)
            Files.createSymbolicLink(directory.resolve(definition.filename), outside)
            rejects { VerifiedFurnitureFiles(directory, validation).read(definition) }
            val linkedRoot = directory.resolve("linked-root")
            val realRoot = Files.createDirectory(directory.resolve("real-root"))
            Files.write(realRoot.resolve(definition.filename), bytes)
            Files.createSymbolicLink(linkedRoot, realRoot)
            rejects { VerifiedFurnitureFiles(linkedRoot, validation).read(definition) }
        } finally { Files.deleteIfExists(outside) }
    }

    @Test
    fun `file definitions themselves cannot escape fixed catalog directory`() {
        val bytes = triangle()
        for (filename in listOf("../fixture.glb", "/fixture.glb", "nested/fixture.glb", "fixture.json")) {
            rejects { VerifiedFurnitureFiles(directory, validation).read(definition(bytes).copy(filename = filename)) }
        }
    }

    private fun definition(bytes: ByteArray) = FurnitureDefinition("fixture", "Fixture", listOf(1.0, 1.0, 1.0),
        "fixture.glb", bytes.size, MessageDigest.getInstance("SHA-256").digest(bytes).toHexString())

    private fun rejects(operation: () -> Unit) {
        val error = assertThrows(ApiException::class.java) { operation() }
        assertEquals("FURNITURE_ASSET_UNAVAILABLE", error.code)
        assertFalse(error.message.orEmpty().contains(directory.toString()))
        assertNull(error.cause)
    }

    private fun triangle(external: Boolean = false): ByteArray {
        val uri = if (external) ",\"uri\":\"https://example.com/private.bin\"" else ""
        val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],"buffers":[{"byteLength":36$uri}],"bufferViews":[{"buffer":0,"byteLength":36}],"accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"}]}""".toByteArray()
        val padded = (json.size + 3) / 4 * 4
        return ByteBuffer.allocate(28 + padded + 36).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x46546c67); putInt(2); putInt(capacity()); putInt(padded); putInt(0x4e4f534a)
            put(json); put(ByteArray(padded - json.size) { 32 }); putInt(36); putInt(0x004e4942)
            floatArrayOf(0f,0f,0f,1f,0f,0f,0f,1f,0f).forEach(::putFloat)
        }.array()
    }
}
