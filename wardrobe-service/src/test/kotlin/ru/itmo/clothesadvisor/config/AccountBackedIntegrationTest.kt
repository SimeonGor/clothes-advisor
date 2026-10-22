package ru.itmo.clothesadvisor.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionSynchronizationManager
import ru.itmo.clothesadvisor.client.account.AccountClient
import ru.itmo.clothesadvisor.dto.account.AccountUserResponse
import ru.itmo.clothesadvisor.model.AccessDeniedException
import ru.itmo.clothesadvisor.model.EntityNotFoundException
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus

// JDBC rows represent the remote account fixture; transport is tested by AccountClientTests.
abstract class AccountBackedIntegrationTest : PostgresIntegrationTest() {
    @Autowired private lateinit var accountFixtures: JdbcTemplate
    @MockitoBean internal lateinit var accounts: AccountClient

    @BeforeEach
    fun configureAccountFixture() {
        doAnswer { call ->
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                accountFixtures.query(
                    "SELECT id, login, role, status FROM app_user WHERE id = ?",
                    { row, _ ->
                        AccountUserResponse(
                            row.getLong("id"),
                            row.getString("login"),
                            UserRole.valueOf(row.getString("role")),
                            UserStatus.valueOf(row.getString("status")),
                        )
                    },
                    call.getArgument<Long>(0),
                ).singleOrNull() ?: throw EntityNotFoundException()
            }
            .`when`(accounts).getUser(anyLong())
        doAnswer { call ->
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                val allowed = accountFixtures.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM access_grant g
                        JOIN app_user o ON o.id = g.owner_id
                        JOIN app_user s ON s.id = g.stylist_id
                        WHERE g.stylist_id = ? AND g.owner_id = ?
                            AND o.role = 'USER' AND o.status = 'ACTIVE'
                            AND s.role = 'STYLIST' AND s.status = 'ACTIVE'
                    )
                    """,
                    Boolean::class.java,
                    call.getArgument<Long>(0), call.getArgument<Long>(1),
                )!!
                if (!allowed) throw EntityNotFoundException()
                null
            }
            .`when`(accounts).requireStylistAccess(anyLong(), anyLong())
        doAnswer { call ->
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
                val activeAdmin = accountFixtures.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM app_user WHERE id = ? AND role = 'ADMIN' AND status = 'ACTIVE')",
                    Boolean::class.java,
                    call.getArgument<Long>(0),
                )!!
                if (!activeAdmin) throw AccessDeniedException("Active administrator required")
                val ownerExists = accountFixtures.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM app_user WHERE id = ?)",
                    Boolean::class.java,
                    call.getArgument<Long>(1),
                )!!
                if (!ownerExists) throw EntityNotFoundException()
                null
            }
            .`when`(accounts).requireAdminAccess(anyLong(), anyLong())
    }
}
