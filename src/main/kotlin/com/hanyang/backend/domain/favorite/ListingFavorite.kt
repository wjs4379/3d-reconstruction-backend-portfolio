package com.hanyang.backend.domain.favorite

import com.hanyang.backend.domain.listing.Listing
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "listing_favorites", uniqueConstraints = [UniqueConstraint(name = "uk_favorite_user_listing", columnNames = ["user_id", "listing_id"])],
    indexes = [Index(name = "idx_favorite_user", columnList = "user_id,favorite_id")])
class ListingFavorite(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "favorite_id") val id: Long? = null,
    @Column(name = "user_id", nullable = false) val userId: Long,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "listing_id", nullable = false) val listing: Listing,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime = LocalDateTime.now()
)
