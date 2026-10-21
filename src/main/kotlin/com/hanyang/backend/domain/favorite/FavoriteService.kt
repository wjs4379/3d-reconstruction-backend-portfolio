package com.hanyang.backend.domain.favorite

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.common.PageView
import com.hanyang.backend.common.pageRequest
import com.hanyang.backend.domain.listing.ListingRepository
import com.hanyang.backend.domain.listing.ListingStatus
import com.hanyang.backend.domain.listing.ListingView
import com.hanyang.backend.security.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class FavoriteState(val listingId: Long, val favorited: Boolean)
@Service
class FavoriteService(private val favorites: FavoriteRepository, private val listings: ListingRepository, private val user: CurrentUser) {
    @Transactional fun save(listingId: Long): FavoriteState {
        val userId = guardUser()
        val listing = listings.findById(listingId).orElseThrow { missing() }
        if (listing.status != ListingStatus.PUBLISHED) throw missing()
        if (favorites.findByUserIdAndListingId(userId, listingId) == null) favorites.saveAndFlush(ListingFavorite(userId = userId, listing = listing))
        return FavoriteState(listingId, true)
    }
    @Transactional fun remove(listingId: Long): FavoriteState {
        val userId = guardUser()
        favorites.findByUserIdAndListingId(userId, listingId)?.let(favorites::delete)
        return FavoriteState(listingId, false)
    }
    @Transactional(readOnly = true) fun mine(page: Int, size: Int) = PageView.from(favorites.findByUserIdAndListingStatus(user.id(),
        ListingStatus.PUBLISHED, pageRequest(page, size)).map { ListingView.from(it.listing) })
    @Transactional(readOnly = true) fun ids(): List<Long> = favorites.findPublishedIds(user.id(), ListingStatus.PUBLISHED)
    private fun guardUser(): Long {
        val id = user.id()
        if (favorites.lockUser(id) == null) throw ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "사용자를 찾을 수 없습니다.")
        return id
    }
    private fun missing() = ApiException(HttpStatus.NOT_FOUND, "LISTING_NOT_FOUND", "공개된 매물을 찾을 수 없습니다.")
}
