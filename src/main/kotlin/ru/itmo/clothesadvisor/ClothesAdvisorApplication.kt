package ru.itmo.clothesadvisor

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication class ClothesAdvisorApplication

fun main(args: Array<String>) {
    runApplication<ClothesAdvisorApplication>(*args)
}
