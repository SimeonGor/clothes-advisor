package ru.itmo.clothesadvisor.config

import java.math.BigDecimal
import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import ru.itmo.clothesadvisor.dto.wardrobe.CreateWardrobeItemRequest
import ru.itmo.clothesadvisor.dto.wardrobe.WardrobeItemResponse
import ru.itmo.clothesadvisor.model.user.AppUser
import ru.itmo.clothesadvisor.model.user.UserRole
import ru.itmo.clothesadvisor.model.user.UserStatus

internal fun JdbcTemplate.insertAccessGrant(ownerId: Long, stylistId: Long) {
    update("INSERT INTO access_grant (owner_id, stylist_id) VALUES (?, ?)", ownerId, stylistId)
}

internal fun JdbcTemplate.insertOutfit(
    ownerId: Long, itemIds: List<Long>, authorId: Long = ownerId, source: String = "USER",
    name: String = "Daily", temperatureC: BigDecimal = BigDecimal.TEN, precipitationTypeId: Long = 1,
    windSpeedMps: BigDecimal = BigDecimal.ZERO,
): Long {
    val id = queryForObject("""
        INSERT INTO outfit (owner_id, author_id, source, name, created_at)
        VALUES (?, ?, ?::outfit_source, ?, CURRENT_TIMESTAMP) RETURNING id
    """, Long::class.java, ownerId, authorId, source, name)!!
    itemIds.forEachIndexed { position, itemId ->
        update("INSERT INTO outfit_item (outfit_id, wardrobe_item_id, position) VALUES (?, ?, ?)", id, itemId, position)
    }
    update("""INSERT INTO outfit_weather (outfit_id, temperature_c, precipitation_type_id, wind_speed_mps)
        VALUES (?, ?, ?, ?)""", id, temperatureC, precipitationTypeId, windSpeedMps)
    return id
}

internal fun JdbcTemplate.insertRating(outfitId: Long, stylistId: Long, vote: String = "LIKE") {
    update("""INSERT INTO outfit_rating
        (outfit_id, stylist_id, vote, version, created_at, modified_at)
        VALUES (?, ?, ?::rating_vote, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""", outfitId, stylistId, vote)
}

internal fun JdbcTemplate.insertUser(
    login: String, passwordHash: String, role: UserRole, status: UserStatus = UserStatus.ACTIVE,
): AppUser {
    val now = TestTimeConfiguration.FIXED_TIME
    val id = queryForObject("""
        INSERT INTO app_user (login, password_hash, role, status, version, created_at, modified_at)
        VALUES (?, ?, ?::user_role, ?::user_status, 1, ?, ?) RETURNING id
    """, Long::class.java, login, passwordHash, role.name, status.name, Timestamp.from(now), Timestamp.from(now))!!
    return AppUser(login, passwordHash, role, status, now).apply { this.id = id }
}

internal fun JdbcTemplate.insertItem(ownerId: Long, request: CreateWardrobeItemRequest): WardrobeItemResponse {
    val now = TestTimeConfiguration.FIXED_TIME
    val id = queryForObject("""
        INSERT INTO wardrobe_item (owner_id, category_id, name, color, material, version, created_at, modified_at)
        VALUES (?, ?, ?, ?, ?, 1, ?, ?) RETURNING id
    """, Long::class.java, ownerId, request.categoryId, request.name, request.color, request.material,
        Timestamp.from(now), Timestamp.from(now))!!
    return WardrobeItemResponse(id, request.name, request.categoryId, request.color, request.material, 1, now, now)
}
