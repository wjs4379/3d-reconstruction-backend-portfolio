package com.hanyang.backend.domain.inquiry

import com.hanyang.backend.domain.listing.DealType
import com.hanyang.backend.domain.listing.Listing
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

enum class InquiryStatus { OPEN, RESPONDED, CLOSED }
@Entity
@Table(name = "inquiries", indexes = [Index(name = "idx_inquiry_author", columnList = "author_user_id,inquiry_id"), Index(name = "idx_inquiry_unit", columnList = "unit_id,inquiry_id")])
class Inquiry(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "inquiry_id") val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "listing_id", nullable = false) val listing: Listing,
    @Column(name = "unit_id", nullable = false) val unitId: Long,
    @Column(name = "author_user_id", nullable = false) val authorUserId: Long,
    @Column(name = "listing_version", nullable = false) val listingVersion: Long,
    @Column(name = "title_snapshot", nullable = false, length = 200) val titleSnapshot: String,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "deal_type_snapshot", nullable = false, length = 20) val dealTypeSnapshot: DealType,
    @Column(name = "deposit_snapshot") val depositSnapshot: Long?,
    @Column(name = "rent_snapshot") val rentSnapshot: Long?,
    @Column(name = "sale_price_snapshot") val salePriceSnapshot: Long? = null,
    @Column(name = "management_fee_snapshot") val managementFeeSnapshot: Long? = null,
    @Column(name = "management_fee_includes_snapshot", length = 500) val managementFeeIncludesSnapshot: String? = null,
    @Column(nullable = false, length = 2000) val message: String,
    @Column(length = 2000) var reply: String? = null,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 20) var status: InquiryStatus = InquiryStatus.OPEN,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "responded_at") var respondedAt: LocalDateTime? = null,
    @Column(name = "closed_at") var closedAt: LocalDateTime? = null
)
