package ru.itmo.clothesadvisor.config

import org.springframework.core.env.PropertySource

internal data class SelectionPrompt(val version: Long, val text: String)

internal class SelectionPromptSettings(private val prefix: String, version: String?, text: String?) {
    @Volatile private var current = try {
        parse(version, text)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun snapshot(): SelectionPrompt? = current

    @Synchronized
    fun apply(source: PropertySource<*>): Boolean {
        // Read both remote fields; missing keys must never reuse an older field.
        val next = parse(
            source.getProperty("$prefix.version")?.toString(),
            source.getProperty("$prefix.text")?.toString(),
        )
        if (next == current) return false
        current = next
        return true
    }

    private fun parse(version: String?, text: String?): SelectionPrompt {
        val number = requireNotNull(version?.toLongOrNull())
        require(number > 0)
        require(!text.isNullOrBlank())
        return SelectionPrompt(number, text)
    }
}
