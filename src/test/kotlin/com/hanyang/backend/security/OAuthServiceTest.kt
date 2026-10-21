package com.hanyang.backend.security

import com.hanyang.backend.common.ApiException
import com.hanyang.backend.domain.user.OAuthProviderClient
import com.hanyang.backend.domain.user.OAuthService
import com.hanyang.backend.domain.user.SocialProfile
import com.hanyang.backend.domain.user.User
import com.hanyang.backend.domain.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.SimpleTransactionStatus
import java.util.Optional

class OAuthServiceTest {
    private val users = Mockito.mock(UserRepository::class.java)
    private val providers = Mockito.mock(OAuthProviderClient::class.java)
    private val manager = Mockito.mock(PlatformTransactionManager::class.java)
    private val service = OAuthService(users, providers, manager)
    private val profile = SocialProfile("google-7", "Owner", "owner@example.com")
    private val user = User(id = 7, email = profile.email, name = profile.name, provider = "GOOGLE", providerId = profile.providerId, role = "GUEST")

    @Test
    fun `provider validation finishes before opening database transaction`() {
        Mockito.`when`(providers.googleProfile("provider-token")).thenReturn(profile)
        Mockito.`when`(manager.getTransaction(any(TransactionDefinition::class.java))).thenReturn(SimpleTransactionStatus())
        Mockito.`when`(users.findByProviderAndProviderId("GOOGLE", "google-7")).thenReturn(Optional.of(user))
        assertEquals(7L, service.loginWithGoogle("provider-token").id)
        val order = Mockito.inOrder(providers, manager, users)
        order.verify(providers).googleProfile("provider-token")
        order.verify(manager).getTransaction(any(TransactionDefinition::class.java))
        order.verify(users).findByProviderAndProviderId("GOOGLE", "google-7")
        order.verify(manager).commit(any())
    }

    @Test
    fun `invalid social token never reaches user persistence`() {
        Mockito.`when`(providers.googleProfile("invalid-token")).thenThrow(ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SOCIAL_TOKEN", "invalid"))
        assertThrows(ApiException::class.java) { service.loginWithGoogle("invalid-token") }
        Mockito.verifyNoInteractions(manager, users)
    }

    @Test
    fun `concurrent first login reads winning identity only after failed transaction rollback`() {
        val first = SimpleTransactionStatus()
        val second = SimpleTransactionStatus()
        Mockito.`when`(providers.googleProfile("provider-token")).thenReturn(profile)
        Mockito.`when`(manager.getTransaction(any(TransactionDefinition::class.java))).thenReturn(first, second)
        Mockito.`when`(users.findByProviderAndProviderId("GOOGLE", "google-7"))
            .thenReturn(Optional.empty(), Optional.of(user))
        Mockito.`when`(users.saveAndFlush(any(User::class.java))).thenThrow(DataIntegrityViolationException("duplicate identity"))
        assertEquals(7L, service.loginWithGoogle("provider-token").id)
        val order = Mockito.inOrder(manager, users)
        order.verify(manager).getTransaction(any(TransactionDefinition::class.java))
        order.verify(users).findByProviderAndProviderId("GOOGLE", "google-7")
        order.verify(users).saveAndFlush(any(User::class.java))
        order.verify(manager).rollback(first)
        order.verify(manager).getTransaction(any(TransactionDefinition::class.java))
        order.verify(users).findByProviderAndProviderId("GOOGLE", "google-7")
        order.verify(manager).commit(second)
    }
}
