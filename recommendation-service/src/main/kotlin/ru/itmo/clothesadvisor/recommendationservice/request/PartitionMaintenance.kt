package ru.itmo.clothesadvisor.recommendationservice.request

import java.time.LocalDate
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@EnableScheduling
@ConditionalOnProperty("spring.r2dbc.url")
class PartitionMaintenance(private val database: DatabaseClient) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
    fun maintain() {
        // Each call completes in autocommit before a new step is started.
        while (true) {
            val step = database.sql("SELECT action, partition_month, reason FROM maintain_recommendation_partitions()")
                .map { row, _ ->
                    Step(
                        row.get("action", String::class.java)!!,
                        row.get("partition_month", LocalDate::class.java)!!,
                        row.get("reason", String::class.java),
                    )
                }.one().block() ?: error("Missing partition maintenance result")
            when (step.action) {
                "ready" -> return
                "blocked" -> {
                    logger.warn("Recommendation partition {} retained: {}", step.month, step.reason)
                    return
                }
                "closed", "removed" -> logger.info("Recommendation month {} {}", step.month, step.action)
                else -> error("Unknown partition maintenance action ${step.action}")
            }
        }
    }

    private data class Step(val action: String, val month: LocalDate, val reason: String?)
}
