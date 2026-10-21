package com.hanyang.backend.domain.favorite

import com.hanyang.backend.domain.listing.ListingStatus
import com.hanyang.backend.domain.user.User
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface FavoriteRepository : JpaRepository<ListingFavorite, Long> {
    fun findByUserIdAndListingId(userId: Long, listingId: Long): ListingFavorite?
    fun findByUserIdAndListingStatus(userId: Long, status: ListingStatus, pageable: Pageable): Page<ListingFavorite>
    @Query("select f.listing.id from ListingFavorite f where f.userId = :userId and f.listing.status = :status order by f.id desc")
    fun findPublishedIds(userId: Long, status: ListingStatus): List<Long>
    // Serializes PUT/DELETE for one account without a duplicate-insert race.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    fun lockUser(id: Long): User?
}
