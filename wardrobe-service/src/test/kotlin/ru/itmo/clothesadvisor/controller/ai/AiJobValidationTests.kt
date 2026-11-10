package ru.itmo.clothesadvisor.controller.ai

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import ru.itmo.clothesadvisor.config.AccountBackedIntegrationTest
import ru.itmo.clothesadvisor.config.insertItem
import ru.itmo.clothesadvisor.config.insertUser
import ru.itmo.clothesadvisor.dto.ai.CreateAiOutfitRequest
import ru.itmo.clothesadvisor.dto.outfit.OutfitWeatherDto
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.model.access.AccountUnavailableException
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.service.ai.AiJobService

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiJobValidationTests : AccountBackedIntegrationTest() {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var jobs: AiJobService
    private var owner = 0L
    private lateinit var job: UUID
    private lateinit var month: String
    private val basic = "Basic " + Base64.getEncoder().encodeToString("recommendation-service:validation-test-only".toByteArray())

    @BeforeEach
    fun accept() {
        owner = jdbc.insertUser("user", "unused", UserRole.USER).id
        val item = jdbc.insertItem(owner, CreateWardrobeItemRequest("Shirt", 1, "blue", "cotton")).id
        jdbc.update("INSERT INTO wardrobe_item_photo (wardrobe_item_id,file_id,content_type,size_bytes) VALUES (?,?,'image/png',42)",
            item, UUID.randomUUID())
        val weather = OutfitWeatherDto(10.toBigDecimal(), 1, 0.toBigDecimal())
        job = jobs.outfit(owner, CreateAiOutfitRequest("Daily", weather), null).jobId
        month = jdbc.queryForObject("SELECT request_month::text FROM ai_job", String::class.java)!!
    }

    @Test
    fun `only the scoped service credential validates a live original input`() {
        assertThat(call()).isEqualTo(204)
        assertThat(call(auth = bearer(owner.toString()))).isEqualTo(401)
        assertThat(call(auth = "Basic Zm9vOmJhcg==")).isEqualTo(401)
        assertThat(call(path = "/api/v2/me/ai-jobs", get = true)).isEqualTo(401)
        assertThat(call(month = "2000-01-01")).isEqualTo(404)
        jdbc.update("UPDATE wardrobe_item SET name='Updated',version=version+1")
        assertThat(call()).isEqualTo(204)
        jdbc.update("UPDATE wardrobe_item_photo SET file_id=?", UUID.randomUUID())
        assertThat(call()).isEqualTo(409)
    }

    @Test
    fun `expired and terminal jobs cannot authorize execution`() {
        jdbc.update("UPDATE ai_job SET start_deadline=clock_timestamp() - interval '1 second'")
        assertThat(call()).isEqualTo(409)
        jdbc.update("UPDATE ai_job SET start_deadline=clock_timestamp() + interval '1 hour',status='FAILED',error_code='source_changed'")
        assertThat(call()).isEqualTo(409)
    }

    @Test
    fun `revocation and temporary account failures are distinct`() {
        jdbc.update("UPDATE app_user SET login='other' WHERE id=?", owner)
        assertThat(call()).isEqualTo(403)
        jdbc.update("UPDATE app_user SET status='BLOCKED' WHERE id=?", owner)
        assertThat(call()).isEqualTo(403)
        doThrow(AccountUnavailableException()).`when`(accounts).getUser(owner)
        assertThat(call()).isEqualTo(503)
    }

    private fun call(path: String = "/internal/ai-jobs/$job/validate-input", month: String = this.month,
                     auth: String = basic, get: Boolean = false): Int {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).header("Authorization", auth)
        if (!get) request.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"requestMonth":"$month"}"""))
        return HttpClient.newHttpClient().use { it.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode() }
    }
}
