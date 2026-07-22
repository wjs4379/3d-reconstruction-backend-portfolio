package com.hanyang.backend.domain.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository//DB에서 어떻게 User를 찾는가?
interface UserRepository : JpaRepository<User, Long> {
    //JpaRepository 써서 save, findById, findAll, delete, count 생김
    fun findByEmail(email: String): Optional<User>
    fun findByProviderAndProviderId(provider: String, providerId: String): Optional<User>
}