package ru.itmo.clothesadvisor.initialization.user

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.boot.DefaultApplicationArguments
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.service.user.AppUserService

class LocalAccountsTests {
    private val users = mock(AppUserService::class.java)
    private val initializer = LocalAccountsInitializer(users)

    @Test
    fun `missing accounts use sentinel hashes and expected roles`() {

        // given: none of the local accounts exist

        // when
        initializer.run(DefaultApplicationArguments())

        // then
        UserRole.entries.forEach { role ->
            verify(users).findByLogin(role.name.lowercase())
            verify(users).create(role.name.lowercase(), "!", role)
        }
        verifyNoMoreInteractions(users)
    }

    @Test
    fun `existing accounts are never overwritten`() {

        // given
        UserRole.entries.forEach {
            `when`(users.findByLogin(it.name.lowercase())).thenReturn(mock(AppUser::class.java))
        }

        // when
        initializer.run(DefaultApplicationArguments())

        // then
        UserRole.entries.forEach { verify(users).findByLogin(it.name.lowercase()) }
        verifyNoMoreInteractions(users)
    }
}
