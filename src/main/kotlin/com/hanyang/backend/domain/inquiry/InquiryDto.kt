package com.hanyang.backend.domain.inquiry

import com.fasterxml.jackson.annotation.JsonAlias
import com.hanyang.backend.domain.listing.DealType
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class InquiryCreateRequest(
    @field:Min(0) @param:JsonProperty(required = true) @param:JsonSetter(nulls = Nulls.FAIL) val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 2000) @param:JsonAlias("message") val content: String
)
data class InquiryReplyRequest(@field:NotBlank @field:Size(max = 2000) val reply: String)
data class ListingSnapshot(
    val title: String, val dealType: DealType, val depositWon: Long?, val monthlyRentWon: Long?,
    val managementFeeWon: Long?, val managementFeeIncludes: String?, val version: Long, val salePriceWon: Long?
)
data class InquiryView(
    val id: Long, val listingId: Long, val unitId: Long, val listingSnapshot: ListingSnapshot,
    val content: String, val reply: String?, val status: InquiryStatus, val createdAt: LocalDateTime,
    val respondedAt: LocalDateTime?, val closedAt: LocalDateTime?
) {
    companion object {
        fun from(i: Inquiry) = InquiryView(requireNotNull(i.id), requireNotNull(i.listing.id), i.unitId,
            ListingSnapshot(i.titleSnapshot, i.dealTypeSnapshot, i.depositSnapshot, i.rentSnapshot,
                i.managementFeeSnapshot, i.managementFeeIncludesSnapshot, i.listingVersion, i.salePriceSnapshot),
            i.message, i.reply, i.status, i.createdAt, i.respondedAt, i.closedAt)
    }
}
