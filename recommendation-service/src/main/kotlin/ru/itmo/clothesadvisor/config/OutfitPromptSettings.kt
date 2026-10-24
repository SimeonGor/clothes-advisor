package ru.itmo.clothesadvisor.config

import org.springframework.core.env.PropertySource

internal data class OutfitPrompt(val version: Long, val text: String)

internal class OutfitPromptSettings(version: String?, text: String?) {
    @Volatile private var current = try {
        parse(version, text)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun snapshot(): OutfitPrompt? = current

    @Synchronized
    fun apply(source: PropertySource<*>): Boolean {
        // Read both remote fields; missing keys must never reuse an older field.
        val next = parse(
            source.getProperty("app.openai.prompt.version")?.toString(),
            source.getProperty("app.openai.prompt.text")?.toString(),
        )
        if (next == current) return false
        current = next
        return true
    }

    private fun parse(version: String?, text: String?): OutfitPrompt {
        val number = requireNotNull(version?.toLongOrNull())
        require(number > 0)
        require(!text.isNullOrBlank())
        return OutfitPrompt(number, text)
    }
}
