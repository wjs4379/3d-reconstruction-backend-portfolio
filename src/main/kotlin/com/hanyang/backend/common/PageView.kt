package com.hanyang.backend.common

import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus

data class PageView<T : Any>(val content: List<T>, val totalElements: Long, val totalPages: Int, val number: Int, val size: Int) {
    companion object {
        fun <T : Any> from(page: Page<T>) = PageView(page.content, page.totalElements, page.totalPages, page.number, page.size)
    }
}
fun pageRequest(page: Int, size: Int, sort: Sort = Sort.by(Sort.Direction.DESC, "id")): PageRequest {
    if (page < 0 || page > 10000 || size !in 1..100) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION", "page는 0~10000, size는 1~100이어야 합니다.")
    return PageRequest.of(page, size, sort)
}
