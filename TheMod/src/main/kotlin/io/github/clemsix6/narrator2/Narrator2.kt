package io.github.clemsix6.narrator2

import net.fabricmc.api.ModInitializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Narrator2 : ModInitializer {
    const val MOD_ID = "narrator2"
    val log: Logger = LoggerFactory.getLogger("Narrator2")

    override fun onInitialize() {
        log.info("The Narrator 2 — initialisation")
    }
}
