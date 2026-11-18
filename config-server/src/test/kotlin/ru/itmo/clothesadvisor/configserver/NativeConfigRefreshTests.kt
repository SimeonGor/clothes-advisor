package ru.itmo.clothesadvisor.configserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NativeConfigRefreshTests {
    @TempDir lateinit var repository: Path

    @Test
    fun `startup and uncertain sends reconcile while unchanged files stay quiet`() {
        val recommendation = Files.createDirectory(repository.resolve("recommendation-service"))
        val wardrobe = Files.createDirectory(repository.resolve("wardrobe-service"))
        val file = Files.writeString(recommendation.resolve("application.yml"), "initial")
        Files.writeString(wardrobe.resolve("application-kafka.yml"), "unchanged")
        val sent = mutableListOf<String>()
        var fail = true
        val watcher = NativeConfigRefresh(repository) { service ->
            if (service == "recommendation-service" && fail) {
                fail = false
                error("Uncertain send")
            }
            sent.add(service)
        }

        watcher.scan()
        watcher.scan()
        watcher.scan()
        assertThat(sent).containsExactly("wardrobe-service", "recommendation-service")

        val replacement = Files.writeString(recommendation.resolve("edit.tmp"), "updated")
        watcher.scan()
        Files.move(replacement, file, ATOMIC_MOVE)
        watcher.scan()
        watcher.scan()
        assertThat(sent).containsExactly("wardrobe-service", "recommendation-service", "recommendation-service")

        Files.writeString(file, "initial")
        watcher.scan()
        Files.delete(file)
        watcher.scan()
        assertThat(sent.takeLast(2)).containsExactly("recommendation-service", "recommendation-service")
        assertThat(sent).hasSize(5)
    }
}
