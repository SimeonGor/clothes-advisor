package ru.itmo.clothesadvisor.accountservice.controller.user

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import reactor.core.publisher.Mono
import ru.itmo.clothesadvisor.accountservice.controller.InvalidRequestException
import ru.itmo.clothesadvisor.accountservice.dto.user.UpdateUserRoleAndStatusRequest
import ru.itmo.clothesadvisor.accountservice.model.user.UserRole
import ru.itmo.clothesadvisor.accountservice.model.user.UserStatus
import ru.itmo.clothesadvisor.accountservice.service.user.AdminUserService

class AdminUserControllerTests {
    private val users = mock(AdminUserService::class.java)
    private val controller = AdminUserController(users)
    private val request = UpdateUserRoleAndStatusRequest(UserRole.USER, UserStatus.ACTIVE, 1)

    @Test
    fun `header parsing rejects whitespace without trimming and invalid long values`() {
        // given
        val headers = listOf(null, "", " ", " 1", "1 ", "1\t", "1.0", "0", "-1",
            "9223372036854775808", "1,2", "text")

        for (header in headers) {
            // when
            val action = { controller.update(header, 2, request) }

            // then
            assertThatThrownBy { action() }.isInstanceOf(InvalidRequestException::class.java)
        }
        verifyNoInteractions(users)
    }

    @Test
    fun `header parsing retains positive long syntax accepted by existing endpoint`() {
        // given
        `when`(users.update(1, 2, request)).thenReturn(Mono.empty())
        `when`(users.update(Long.MAX_VALUE, 2, request)).thenReturn(Mono.empty())

        // when
        controller.update("+01", 2, request)
        controller.update(Long.MAX_VALUE.toString(), 2, request)

        // then
        verify(users).update(1, 2, request)
        verify(users).update(Long.MAX_VALUE, 2, request)
    }
}
