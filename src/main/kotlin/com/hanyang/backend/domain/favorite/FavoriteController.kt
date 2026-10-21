package com.hanyang.backend.domain.favorite

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2")
class FavoriteController(private val service: FavoriteService) {
    @PutMapping("/listings/{listingId}/favorite") fun save(@PathVariable listingId: Long) = service.save(listingId)
    @DeleteMapping("/listings/{listingId}/favorite") fun remove(@PathVariable listingId: Long) = service.remove(listingId)
    @GetMapping("/me/favorites") fun mine(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) = service.mine(page, size)
    @GetMapping("/me/favorites/ids") fun ids() = service.ids()
}
