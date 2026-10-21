package com.hanyang.backend.domain.inquiry

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.common.PageView
import com.hanyang.backend.common.pageRequest
import com.hanyang.backend.domain.listing.ListingRepository
import com.hanyang.backend.domain.listing.ListingStatus
import com.hanyang.backend.domain.unit.UnitService
import com.hanyang.backend.security.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class InquiryService(
    private val inquiries: InquiryRepository, private val listings: ListingRepository,
    private val units: UnitService, private val currentUser: CurrentUser
) {
    @Transactional
    fun create(listingId: Long, request: InquiryCreateRequest): InquiryView {
        validateText(request.content)
        val listing = listings.findForUpdate(listingId) ?: throw ApiException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND", "공개된 매물을 찾을 수 없습니다.")
        if (listing.status != ListingStatus.PUBLISHED) throw ApiException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND", "공개된 매물을 찾을 수 없습니다.")
        if (listing.version != request.expectedVersion) throw ApiException(HttpStatus.CONFLICT, "LISTING_VERSION_CONFLICT", "매물이 변경되었습니다. 새로고침 후 조건을 확인해 주세요.")
        // Snapshot exactly the conditions reviewed by the caller, while publication and edit commands are blocked by this row lock.
        return InquiryView.from(inquiries.saveAndFlush(Inquiry(listing = listing, unitId = requireNotNull(listing.unit.id),
            authorUserId = currentUser.id(), listingVersion = listing.version, titleSnapshot = listing.title,
            dealTypeSnapshot = listing.dealType, salePriceSnapshot = listing.salePrice, depositSnapshot = listing.deposit, rentSnapshot = listing.monthlyRent,
            managementFeeSnapshot = listing.managementFee, managementFeeIncludesSnapshot = listing.managementFeeIncludes,
            message = request.content.trim())))
    }
    @Transactional(readOnly = true)
    fun mine(page: Int, size: Int) = PageView.from(inquiries.findByAuthorUserId(currentUser.id(), pageRequest(page, size)).map(InquiryView::from))
    @Transactional(readOnly = true)
    fun received(unitId: Long, page: Int, size: Int): PageView<InquiryView> {
        units.requireManagedUnit(unitId)
        return PageView.from(inquiries.findByUnitId(unitId, pageRequest(page, size)).map(InquiryView::from))
    }
    @Transactional(readOnly = true)
    fun get(id: Long): InquiryView {
        val inquiry = inquiries.findById(id).orElseThrow { notFound() }
        requireParticipant(inquiry)
        return InquiryView.from(inquiry)
    }
    @Transactional
    fun reply(id: Long, request: InquiryReplyRequest): InquiryView {
        validateText(request.reply)
        val inquiry = inquiries.findForUpdate(id) ?: throw notFound()
        if (inquiry.listing.unit.creatorUserId != currentUser.id()) forbidden()
        if (inquiry.status == InquiryStatus.CLOSED) throw ApiException(HttpStatus.CONFLICT, "INQUIRY_CLOSED", "종료된 문의에는 답변할 수 없습니다.")
        inquiry.reply = request.reply.trim(); inquiry.status = InquiryStatus.RESPONDED; inquiry.respondedAt = LocalDateTime.now()
        return InquiryView.from(inquiries.saveAndFlush(inquiry))
    }
    @Transactional
    fun close(id: Long): InquiryView {
        val inquiry = inquiries.findForUpdate(id) ?: throw notFound()
        requireParticipant(inquiry)
        if (inquiry.status != InquiryStatus.CLOSED) { inquiry.status = InquiryStatus.CLOSED; inquiry.closedAt = LocalDateTime.now() }
        return InquiryView.from(inquiries.saveAndFlush(inquiry))
    }
    private fun requireParticipant(inquiry: Inquiry) {
        val userId = currentUser.id()
        if (inquiry.authorUserId != userId && inquiry.listing.unit.creatorUserId != userId) forbidden()
    }
    private fun validateText(text: String) {
        if (text.isBlank() || text.length > 2000) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_INQUIRY_TEXT", "문의와 답변은 1~2000자여야 합니다.")
    }
    private fun forbidden(): Nothing = throw ApiException(HttpStatus.FORBIDDEN, "INQUIRY_FORBIDDEN", "문의 접근 권한이 없습니다.")
    private fun notFound() = ApiException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다.")
}
