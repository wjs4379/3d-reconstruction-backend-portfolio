package com.hanyang.backend.domain.media

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.location.KakaoLocationProvider
import com.hanyang.backend.domain.location.LocationService
import com.hanyang.backend.domain.model3d.AiServerClient
import com.hanyang.backend.domain.model3d.ManagedModelService
import com.hanyang.backend.domain.model3d.Room3DModelService
import com.hanyang.backend.domain.property.Property
import com.hanyang.backend.domain.property.PropertyRepository
import com.hanyang.backend.domain.unit.UnitCreateRequest
import com.hanyang.backend.domain.unit.UnitService
import com.hanyang.backend.domain.user.User
import com.hanyang.backend.domain.user.UserRepository
import com.hanyang.backend.security.AuthenticatedUser
import com.hanyang.backend.security.CurrentUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.TestPropertySource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

@DataJpaTest
@TestPropertySource(properties=["app.ai.pipeline-enabled=true","app.ai.service-key=test-only-ai-service-secret-at-least-32-bytes","app.ai.backend-base-url=http://localhost:8080","outbox.worker.enabled=true"])
@Import(LocationService::class,KakaoLocationProvider::class,ManagedModelService::class,Room3DModelService::class,AiServerClient::class,MediaConcurrencyGuard::class,MediaService::class,MediaValidation::class,MediaConfiguration::class,UnitService::class,CurrentUser::class,MediaWorkflowIntegrationTest.JsonConfig::class)
class MediaImageMetadataIntegrationTest @Autowired constructor(
    private val media: MediaService, private val assets: MediaAssetRepository,
    private val users: UserRepository, private val properties: PropertyRepository,
    private val units: UnitService, private val storage: MediaStorage
) {
    private var owner=0L; private var other=0L; private var unit=0L
    @BeforeEach fun setup() {
        val suffix=java.util.UUID.randomUUID().toString()
        owner=requireNotNull(users.saveAndFlush(User(email="dimensions-owner-$suffix@example.com",name="Owner",provider="TEST",providerId="dimensions-owner-$suffix",role="HOST")).id)
        other=requireNotNull(users.saveAndFlush(User(email="dimensions-other-$suffix@example.com",name="Other",provider="TEST",providerId="dimensions-other-$suffix",role="GUEST")).id)
        asUser(owner)
        val property=properties.saveAndFlush(Property(title="Dimensions building",address="private",creatorUserId=owner))
        unit=units.create(UnitCreateRequest(requireNotNull(property.id),"101","SEOUL","서울")).id
    }
    @AfterEach fun clear() { SecurityContextHolder.clearContext() }

    @Test fun `verified PNG and ordinary JPEG store actual dimensions and retain original bytes and asset on replay`() {
        for ((format,width,height) in listOf(Triple("png",64,32),Triple("jpeg",19,7))) {
            val bytes=image(format,width,height)
            val id=upload(bytes,"image/$format")
            val asset=assets.findById(id).orElseThrow()
            assertEquals(width,asset.pixelWidth); assertEquals(height,asset.pixelHeight)
            val originalKey=asset.objectKey; val originalChecksum=asset.checksum
            assertEquals(MediaImageMetadataView(id,width,height),media.repairImageMetadata(id))
            assertEquals(originalKey,assets.findById(id).orElseThrow().objectKey)
            assertEquals(originalChecksum,assets.findById(id).orElseThrow().checksum)
            assertArrayEquals(bytes,media.privateRead(id).bytes)
        }
    }

    @Test fun `non-square EXIF JPEG extents follow browser and AI orientation without reencoding bytes`() {
        for (littleEndian in listOf(true,false)) for (orientation in 1..8) {
            val bytes=orientedJpeg(orientation,littleEndian)
            val id=upload(bytes,"image/jpeg")
            val asset=assets.findById(id).orElseThrow()
            assertEquals(if (orientation in 5..8) 7 else 19,asset.pixelWidth)
            assertEquals(if (orientation in 5..8) 19 else 7,asset.pixelHeight)
            assertArrayEquals(bytes,media.privateRead(id).bytes)
        }
    }

    @Test fun `malformed EXIF directory offsets safely retain decodable raw extents`() {
        val bytes=orientedJpeg(6,true)
        // JPEG marker+length+Exif signature occupy 12 bytes before the TIFF header.
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16,Int.MAX_VALUE)
        val id=upload(bytes,"image/jpeg")
        val asset=assets.findById(id).orElseThrow()
        assertEquals(19,asset.pixelWidth); assertEquals(7,asset.pixelHeight)
        assertArrayEquals(bytes,media.privateRead(id).bytes)
    }

    @Test fun `old image metadata repairs idempotently without changing asset checksum file version or source IDs`() {
        val bytes=orientedJpeg(6,true)
        val id=upload(bytes,"image/jpeg")
        val asset=assets.findById(id).orElseThrow()
        val key=asset.objectKey; val version=asset.objectVersion; val checksum=asset.checksum
        val createdAt=asset.createdAt; val sourceModel=asset.sourceModelId
        asset.pixelWidth=null; asset.pixelHeight=null; assets.saveAndFlush(asset)
        val first=media.repairImageMetadata(id)
        assertEquals(MediaImageMetadataView(id,7,19),first)
        assertEquals(first,media.repairImageMetadata(id))
        val restored=assets.findById(id).orElseThrow()
        assertEquals(key,restored.objectKey); assertEquals(version,restored.objectVersion)
        assertEquals(checksum,restored.checksum); assertEquals(createdAt,restored.createdAt)
        assertEquals(sourceModel,restored.sourceModelId)
        assertArrayEquals(bytes,media.privateRead(id).bytes)
    }

    @Test fun `foreign unverified and model assets cannot repair image metadata`() {
        val id=upload(image("png",64,32),"image/png")
        asUser(other)
        assertEquals(403,assertThrows(ApiException::class.java) { media.repairImageMetadata(id) }.status.value())
        asUser(owner)
        val model=assets.saveAndFlush(MediaAsset(unitId=unit,ownerUserId=owner,kind=MediaKind.MODEL,
            objectKey="assets/fixture-no-file",sizeBytes=100,contentType="model/gltf-binary"))
        assertEquals("IMAGE_METADATA_IMAGE_REQUIRED",assertThrows(ApiException::class.java) { media.repairImageMetadata(requireNotNull(model.id)) }.code)
        val pending=assets.saveAndFlush(MediaAsset(unitId=unit,ownerUserId=owner,kind=MediaKind.IMAGE,status=MediaStatus.PENDING,
            objectKey="staging/fixture-no-file",sizeBytes=100,contentType="image/png"))
        assertEquals(403,assertThrows(ApiException::class.java) { media.repairImageMetadata(requireNotNull(pending.id)) }.status.value())
        assertNull(model.pixelWidth); assertNull(pending.pixelWidth)
    }

    @Test fun `metadata repair refuses altered immutable bytes and missing checksum without storing extents`() {
        val original=image("png",64,32)
        val id=upload(original,"image/png")
        val asset=assets.findById(id).orElseThrow()
        asset.pixelWidth=null; asset.pixelHeight=null; assets.saveAndFlush(asset)
        val changed=original.copyOf().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() }
        storage.putStaging(asset.objectKey,changed,asset.contentType)
        assertEquals("IMAGE_METADATA_INTEGRITY_MISMATCH",assertThrows(ApiException::class.java) { media.repairImageMetadata(id) }.code)
        assertNull(assets.findById(id).orElseThrow().pixelWidth)
        assertNull(assets.findById(id).orElseThrow().pixelHeight)
        storage.putStaging(asset.objectKey,original,asset.contentType)
        val missing=assets.saveAndFlush(MediaAsset(unitId=unit,ownerUserId=owner,kind=MediaKind.IMAGE,
            objectKey="assets/missing-checksum",sizeBytes=100,contentType="image/png"))
        assertEquals("IMAGE_METADATA_INTEGRITY_REQUIRED",assertThrows(ApiException::class.java) { media.repairImageMetadata(requireNotNull(missing.id)) }.code)
        assertNull(missing.pixelWidth); assertNull(missing.pixelHeight)
    }

    private fun image(format:String,width:Int,height:Int)=ByteArrayOutputStream().also {
        assertTrue(ImageIO.write(BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),format,it))
    }.toByteArray()
    private fun orientedJpeg(orientation:Int,little:Boolean):ByteArray {
        val jpeg=image("jpeg",19,7)
        val tiff=ByteBuffer.allocate(26).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        tiff.put(if (little) byteArrayOf(73,73) else byteArrayOf(77,77))
        tiff.putShort(42).putInt(8).putShort(1).putShort(0x0112).putShort(3).putInt(1)
            .putShort(orientation.toShort()).putShort(0).putInt(0)
        val payload=byteArrayOf(69,120,105,102,0,0)+tiff.array()
        val segment=ByteBuffer.allocate(payload.size+4).order(ByteOrder.BIG_ENDIAN)
            .put(255.toByte()).put(0xe1.toByte()).putShort((payload.size+2).toShort()).put(payload).array()
        return jpeg.copyOfRange(0,2)+segment+jpeg.copyOfRange(2,jpeg.size)
    }
    private fun upload(bytes:ByteArray,type:String):Long {
        val session=media.create(UploadSessionRequest(unit,MediaKind.IMAGE,type,bytes.size.toLong()))
        media.upload(session.id,type,bytes.inputStream())
        val completed=media.complete(session.id)
        assertEquals(completed.id,media.complete(session.id).id)
        return completed.id
    }
    private fun asUser(id:Long) { SecurityContextHolder.getContext().authentication=UsernamePasswordAuthenticationToken(AuthenticatedUser(id),null,emptyList()) }
}
