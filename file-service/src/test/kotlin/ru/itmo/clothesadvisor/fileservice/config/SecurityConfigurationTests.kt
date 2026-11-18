package ru.itmo.clothesadvisor.fileservice.config

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SecurityConfigurationTests {
    @TempDir lateinit var directory: Path

    @Test
    fun `missing blank or shared passwords prevent building service accounts`() {
        // given
        val config = SecurityConfiguration()
        val secret = "synthetic-private-value"
        val valid = Files.writeString(directory.resolve("valid"), secret)
        val missing = directory.resolve("absent")
        val blank = Files.writeString(directory.resolve("blank"), " \n")
        val equal = Files.writeString(directory.resolve("duplicate"), "$secret\r\n")
        val encoder = config.passwordEncoder()

        for (invalid in listOf(missing, blank, equal)) {
            for ((wardrobe, recommendation) in listOf(invalid to valid, valid to invalid)) {
                // when
                val error = catchThrowable {
                    config.serviceAccounts(wardrobe.toString(), recommendation.toString(), encoder)
                }

                // then
                assertThat(error).isNotNull()
                assertThat(error.message).contains("password").doesNotContain(secret)
                assertThat(error.cause).isNull()
            }
        }
    }
}
