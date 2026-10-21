package com.hanyang.backend.domain.inquiry

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2")
class InquiryController(private val service: InquiryService) {
    @PostMapping("/listings/{listingId}/inquiries") fun create(@PathVariable listingId: Long, @Valid @RequestBody request: InquiryCreateRequest) = service.create(listingId, request)
    @GetMapping("/me/inquiries") fun mine(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) = service.mine(page, size)
    @GetMapping("/units/{unitId}/inquiries") fun received(@PathVariable unitId: Long, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) = service.received(unitId, page, size)
    @GetMapping("/inquiries/{inquiryId}") fun get(@PathVariable inquiryId: Long) = service.get(inquiryId)
    @PostMapping("/inquiries/{inquiryId}/reply") fun reply(@PathVariable inquiryId: Long, @Valid @RequestBody request: InquiryReplyRequest) = service.reply(inquiryId, request)
    @PostMapping("/inquiries/{inquiryId}/close") fun close(@PathVariable inquiryId: Long) = service.close(inquiryId)
}
