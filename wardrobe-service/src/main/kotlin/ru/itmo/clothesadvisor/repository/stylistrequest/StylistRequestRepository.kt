package ru.itmo.clothesadvisor.repository.stylistrequest

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequest
import ru.itmo.clothesadvisor.model.stylistrequest.StylistRequestWeather

private const val REQUEST_WITH_WEATHER = """
    SELECT r.*, w.temperature_c AS weather_temperature_c,
        w.precipitation_type_id AS weather_precipitation_type_id,
        w.wind_speed_mps AS weather_wind_speed_mps
    FROM stylist_request r JOIN stylist_request_weather w ON w.request_id = r.id
"""
private const val REQUEST_OWNER = """
    WHERE r.owner_id = :ownerId AND (CAST(:stylistId AS BIGINT) IS NULL OR r.stylist_id = :stylistId)
"""

internal interface StylistRequestRepository : Repository<StylistRequest, Long> {
    @Query("INSERT INTO stylist_request (owner_id, stylist_id, wishes) VALUES (:ownerId, :stylistId, :wishes) RETURNING id")
    fun create(ownerId: Long, stylistId: Long, wishes: String): Long

    @Modifying
    @Query("""
        INSERT INTO stylist_request_weather (request_id, temperature_c, precipitation_type_id, wind_speed_mps)
        VALUES (:id, :#{#weather.temperatureC}, :#{#weather.precipitationTypeId}, :#{#weather.windSpeedMps})
    """)
    fun insertWeather(id: Long, weather: StylistRequestWeather)

    @Query(REQUEST_WITH_WEATHER + REQUEST_OWNER + " AND r.id = :id")
    fun findOwned(id: Long, ownerId: Long, stylistId: Long?): StylistRequest?

    @Query(REQUEST_WITH_WEATHER + " WHERE r.id = :id AND r.owner_id = :ownerId FOR UPDATE OF r")
    fun findLocked(id: Long, ownerId: Long): StylistRequest?

    @Query(REQUEST_WITH_WEATHER + REQUEST_OWNER + " ORDER BY r.id DESC LIMIT :limit OFFSET :offset")
    fun findPage(ownerId: Long, stylistId: Long?, limit: Int, offset: Long): List<StylistRequest>

    @Query("SELECT count(*) FROM stylist_request r " + REQUEST_OWNER)
    fun countOwned(ownerId: Long, stylistId: Long?): Long

    @Modifying
    @Query("""
        INSERT INTO stylist_request_history
            (request_id, version, owner_id, stylist_id, wishes, status, created_at, modified_at)
        SELECT id, version, owner_id, stylist_id, wishes, status, created_at, modified_at
        FROM stylist_request WHERE id = :id AND owner_id = :ownerId AND version = :version
    """)
    fun archive(id: Long, ownerId: Long, version: Long): Int

    @Modifying
    @Query("""
        UPDATE stylist_request SET status = 'CANCELLED', version = version + 1, modified_at = CURRENT_TIMESTAMP
        WHERE id = :id AND owner_id = :ownerId AND version = :version AND status = 'PENDING'
    """)
    fun cancel(id: Long, ownerId: Long, version: Long): Int
}
