package com.hanyang.backend.domain.user

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Service
class OAuthService(
    private val userRepository: UserRepository,
    private val providers: OAuthProviderClient,
    transactionManager: PlatformTransactionManager
) {
    private val transactions = TransactionTemplate(transactionManager)

    // Provider network calls happen before acquiring a database transaction.
    fun loginWithKakao(accessToken: String): User = getOrCreateUser("KAKAO", providers.kakaoProfile(accessToken))

    fun loginWithKakaoCode(code: String): User = getOrCreateUser("KAKAO", providers.kakaoCodeProfile(code))

    fun loginWithGoogle(accessToken: String): User = getOrCreateUser("GOOGLE", providers.googleProfile(accessToken))

    private fun getOrCreateUser(provider: String, profile: SocialProfile): User {
        try {
            return requireNotNull(transactions.execute {
                userRepository.findByProviderAndProviderId(provider, profile.providerId).orElseGet {
                    userRepository.saveAndFlush(User(
                        email = profile.email,
                        name = profile.name,
                        provider = provider,
                        providerId = profile.providerId,
                        role = "GUEST"
                    ))
                }
            })
        } catch (exception: DataIntegrityViolationException) {
            // Concurrent first logins race at the unique social-identity constraint.
            // Read in a new transaction only after the unsuccessful insert has rolled back.
            return requireNotNull(transactions.execute {
                userRepository.findByProviderAndProviderId(provider, profile.providerId).orElseThrow { exception }
            })
        }
    }
}
