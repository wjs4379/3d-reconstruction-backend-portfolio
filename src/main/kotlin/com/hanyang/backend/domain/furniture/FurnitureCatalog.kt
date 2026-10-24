package com.hanyang.backend.domain.furniture

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.media.MediaKind
import com.hanyang.backend.domain.media.MediaValidation
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Catalog dimensions are furniture dimensions in meters, not a claim about AI room scale. */
data class FurnitureCatalogItem(
    val type: String,
    val label: String,
    val sizeMeters: List<Double>,
    val modelUrl: String,
    val byteLength: Int,
    val sha256: String,
    val available: Boolean
)

data class FurnitureContent(val bytes: ByteArray, val sha256: String)

internal data class FurnitureDefinition(
    val type: String,
    val label: String,
    val sizeMeters: List<Double>,
    val filename: String,
    val byteLength: Int,
    val sha256: String
)

/** Only these previously reviewed, self-contained model files can leave this endpoint. */
internal val furnitureDefinitions = listOf(
    FurnitureDefinition("small_fridge", "소형 냉장고", listOf(0.48, 0.84, 0.52), "refridgerators-RB10FSR4ESRAA.glb", 595204,
        "a470d2190db519fbdd5cb66e2583ac2bf6cb36aa62e1cde87e8ab517a3617643"),
    FurnitureDefinition("air_conditioner", "에어컨", listOf(0.363, 1.883, 0.294), "samsung_ac_363x1883x294.glb", 71360,
        "1d9f129f69370425321916c58a14adde02f8bdb64b629c4f11461bf92c5a6529"),
    FurnitureDefinition("fridge", "냉장고", listOf(0.912, 1.853, 0.683), "samsung_fridge_912x1853x683.glb", 532700,
        "24202a148d31c0d6f4c3b642c2ef9806c82c16b03b9a7c04f0259121fb852110"),
    FurnitureDefinition("microwave", "전자레인지", listOf(0.489, 0.275, 0.381), "samsung_microwave_489x275x381_v2.glb", 172888,
        "6a33e519e3d51e2442b13f21096c8fa5a9d9e0283735c4e6fcdefe9705217fc1"),
    FurnitureDefinition("standbyme", "스탠바이미", listOf(0.716, 1.106, 0.550), "samsung_standbyme_716x1106x550base.glb", 634608,
        "39e3737504abdd382e9f47e2cf0a745b7526dde6268bb52f81bf09fd9268a79d"),
    FurnitureDefinition("washer", "세탁기", listOf(0.686, 0.984, 0.875), "samsung_washer_686x984x875_hires_v2.glb", 594800,
        "d512db7b5e73bdd45ec57931e3c11f2228ff2e777f5dba34a3052819de7c6e8f")
)

@Component
class FurnitureCatalog(
    @param:Value("\${app.furniture.local-directory:}") directory: String,
    validation: MediaValidation
) {
    private val files = VerifiedFurnitureFiles(directory.takeIf { it.isNotBlank() }?.let(Path::of), validation)

    fun list(): List<FurnitureCatalogItem> = furnitureDefinitions.map { definition ->
        val available = try { files.read(definition); true } catch (_: ApiException) { false }
        FurnitureCatalogItem(definition.type, definition.label, definition.sizeMeters,
            "/api/v2/furniture/${definition.type}/content", definition.byteLength, definition.sha256, available)
    }

    fun read(type: String): FurnitureContent {
        val definition = furnitureDefinitions.firstOrNull { it.type == type }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "FURNITURE_NOT_FOUND", "등록된 가구를 찾을 수 없습니다.")
        return FurnitureContent(files.read(definition), definition.sha256)
    }
}

/** No URL fetch, redirect, directory listing, fallback box or caller-supplied filesystem path. */
internal class VerifiedFurnitureFiles(directory: Path?, private val validation: MediaValidation) {
    private val root = directory?.toAbsolutePath()?.normalize()

    fun read(definition: FurnitureDefinition): ByteArray {
        try {
            val directory = root ?: unavailable()
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) unavailable()
            if (definition.byteLength !in 20..MAX_BYTES ||
                !definition.filename.matches(Regex("[A-Za-z0-9_-]+\\.glb"))) unavailable()
            val file = directory.resolve(definition.filename)
            if (file.parent != directory || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) unavailable()
            val bytes = Files.newByteChannel(file, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                if (channel.size() != definition.byteLength.toLong()) unavailable()
                Channels.newInputStream(channel).readNBytes(definition.byteLength + 1)
            }
            if (bytes.size != definition.byteLength ||
                MessageDigest.getInstance("SHA-256").digest(bytes).toHexString() != definition.sha256) unavailable()
            // MIME supplied by a CDN is never trusted; validate actual self-contained GLB content.
            try { validation.validate(MediaKind.MODEL, "model/gltf-binary", bytes) }
            catch (_: ApiException) { unavailable() }
            return bytes
        } catch (_: IOException) {
            unavailable()
        } catch (_: SecurityException) {
            unavailable()
        }
    }

    private fun unavailable(): Nothing = throw ApiException(HttpStatus.SERVICE_UNAVAILABLE,
        "FURNITURE_ASSET_UNAVAILABLE", "가구 모델 파일을 준비할 수 없습니다. 관리자에게 문의해 주세요.")

    companion object { const val MAX_BYTES = 1_048_576 }
}
