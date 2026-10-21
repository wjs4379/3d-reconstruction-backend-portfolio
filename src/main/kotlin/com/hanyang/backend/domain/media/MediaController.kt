package com.hanyang.backend.domain.media

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2")
class MediaController(private val media: MediaService, private val guard: MediaConcurrencyGuard) {
    @PostMapping("/media/upload-sessions")
    fun create(@Valid @RequestBody request: UploadSessionRequest) = media.create(request)
    @PutMapping("/media/upload-sessions/{id}/content")
    fun upload(@PathVariable id: String, request: HttpServletRequest): ResponseEntity<Void> {
        request.inputStream.use { media.upload(id,request.contentType,it) }
        return ResponseEntity.noContent().build()
    }
    @PostMapping("/media/upload-sessions/{id}/complete")
    fun complete(@PathVariable id: String) = media.complete(id)
    @PostMapping("/media/assets/{assetId}/image-metadata")
    fun repairImageMetadata(@PathVariable assetId: Long) = media.repairImageMetadata(assetId)
    @PostMapping("/media/import-models/{modelId}")
    fun importModel(@PathVariable modelId: Long,@Valid @RequestBody request: ImportModelRequest) = media.importModel(modelId,request.unitId)
    @GetMapping("/listings/{listingId}/assets/{assetId}")
    fun publicRead(@PathVariable listingId: Long,@PathVariable assetId: Long,response: HttpServletResponse) {
        guard.run { write(media.publicRead(listingId,assetId),response) }
    }
    @GetMapping("/media/assets/{assetId}/content")
    fun privateRead(@PathVariable assetId: Long,response: HttpServletResponse) {
        guard.run { write(media.privateRead(assetId),response) }
    }
    // Hold the buffer permit until the HTTP body has been written, including slow downloads.
    @GetMapping("/units/{unitId}/assets")
    fun mine(@PathVariable unitId: Long, @RequestParam(defaultValue="0") page: Int, @RequestParam(defaultValue="50") size: Int) = media.mine(unitId,page,size)

    private fun write(read: MediaRead,response: HttpServletResponse) {
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store")
        if (read.redirect != null) {
            response.status=302
            response.setHeader(HttpHeaders.LOCATION,read.redirect)
            return
        }
        val bytes=requireNotNull(read.bytes)
        response.contentType=read.contentType
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,"inline")
        response.setContentLength(bytes.size)
        response.outputStream.write(bytes)
        response.outputStream.flush()
    }
}
