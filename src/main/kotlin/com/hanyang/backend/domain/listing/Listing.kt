package com.hanyang.backend.domain.listing

import com.hanyang.backend.domain.unit.Unit
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

enum class DealType { MONTHLY_RENT, JEONSE, SALE }
enum class ListingStatus { DRAFT, PUBLISHED, HIDDEN, COMPLETED }

@Entity
@Table(name = "listings", uniqueConstraints = [UniqueConstraint(name = "uk_listing_unit_deal", columnNames = ["unit_id", "deal_type"])],
    indexes = [Index(name = "idx_listing_public_order", columnList = "status,published_at,listing_id")])
class Listing(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "listing_id") val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "unit_id", nullable = false) val unit: Unit,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "deal_type", nullable = false, length = 20) val dealType: DealType = DealType.MONTHLY_RENT,
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 20) var status: ListingStatus = ListingStatus.DRAFT,
    @Column(nullable = false, length = 200) var title: String,
    @Column(columnDefinition = "TEXT") var description: String? = null,
    @Column var deposit: Long? = null,
    @Column(name = "monthly_rent") var monthlyRent: Long? = null,
    @Column(name = "management_fee") var managementFee: Long? = null,
    @Column(name = "management_fee_includes", length = 500) var managementFeeIncludes: String? = null,
    @Column(name = "representative_asset_id") var representativeAssetId: Long? = null,
    @ElementCollection @CollectionTable(name = "listing_photos", joinColumns = [JoinColumn(name = "listing_id")])
    @Column(name = "asset_id", nullable = false) @OrderColumn(name = "photo_order") var photoAssetIds: MutableList<Long> = mutableListOf(),
    @Column(name = "model_asset_id") var modelAssetId: Long? = null,
    @Column(name = "selected_model_id") var selectedModelId: Long? = null,
    @Column(name = "selected_space_id") var spaceId: Long? = null,
    @Column(name = "sale_price") var salePrice: Long? = null,
    @ElementCollection @CollectionTable(name = "listing_models", joinColumns = [JoinColumn(name = "listing_id")])
    @OrderColumn(name = "model_order") var modelSelections: MutableList<ListingModelSelection> = mutableListOf(),
    @Version @Column(nullable = false) var version: Long = 0,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "published_at") var publishedAt: LocalDateTime? = null,
    @Column(name = "completed_at") var completedAt: LocalDateTime? = null
)

@Embeddable
data class ListingModelSelection(
    @Column(name = "space_id", nullable = false) val spaceId: Long = 0,
    @Column(name = "model_asset_id", nullable = false) val modelAssetId: Long = 0,
    @Column(name = "selected_model_id", nullable = false) val selectedModelId: Long = 0,
    @Column(name = "space_name", length = 255) val spaceName: String? = null
)
