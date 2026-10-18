package io.github.clemsix6.narrator2.memory

/**
 * The French words a reminiscence looks for (M3 §10.5): by cause of death, by mob, by place, by
 * milestone and by kind of event.
 *
 * **These words are search data, not text for a human.** Events come with English game ids
 * (`minecraft:drown`, `minecraft:trial_chambers`) while the memories are written in French by the models
 * (M3 §2): a death by drowning must find « Kelvin est mort noyé », whatever its sentence. So a
 * reminiscence searches the French words a memory of that event would use, next to the English tags and
 * ids the memories also carry (the bridge of M3 §3.6). The French analyzer stems these words exactly as
 * it stems the memories (`noyade` → `noyad`), so their form does not matter, only their stem. They are
 * never shown to a model nor to a player: like the analyzer's stop words, they are the one legitimate
 * kind of French in the code.
 *
 * Ids are full ids (`minecraft:…`). A lookup that knows nothing of an id returns an empty string: the id
 * itself still goes into the search.
 */
object CueWords {
    /** Every death. */
    const val DEATH = "mort mourir"

    /** A player killed by a player or a mob. */
    const val KILLED = "tué"

    /** A player killed by another player. */
    const val PVP = "duel combat tué"

    /** A player broke what another player built. */
    const val GRIEF = "grief cassé détruit saccagé construction maison"

    /**
     * Words that say nothing in a message to the Narrator (how players call him, greetings, fillers):
     * left out of the search of an addressed message, so that « Narrateur, salut ! » does not bring back
     * any memory that names the Narrator.
     */
    const val CHAT_NOISE =
        "narrateur narrator dieu divin seigneur maître salut bonjour bonsoir coucou merci stp svp lol mdr ptdr haha ok oui " +
            "non quoi pourquoi comment hein bah ben euh alors"

    /** The words of a death by [cause] (a damage type id), besides [DEATH]. */
    fun death(cause: String): String = DEATH_CAUSES[cause].orEmpty()

    /** The French name of a mob type, when it differs from its id (`minecraft:skeleton` → « squelette »). */
    fun mob(type: String): String = MOBS[type].orEmpty()

    /** The French name of a structure; variants share their family's (`minecraft:village_plains` → « village »). */
    fun structure(id: String): String =
        STRUCTURES[id] ?: STRUCTURE_FAMILIES.entries.firstOrNull { id.startsWith(it.key) }?.value.orEmpty()

    /** The French name of a rare biome. */
    fun biome(id: String): String = BIOMES[id].orEmpty()

    /** The French words of a dimension. */
    fun dimension(id: String): String = DIMENSIONS[id].orEmpty()

    /** The French words of a milestone advancement. */
    fun advancement(id: String): String = ADVANCEMENTS[id].orEmpty()

    private val DEATH_CAUSES: Map<String, String> = mapOf(
        "minecraft:fall" to "chute tombé tomber",
        "minecraft:ender_pearl" to "chute perle ender",
        "minecraft:fly_into_wall" to "élytres percuté mur",
        "minecraft:stalagmite" to "chute stalagmite",
        "minecraft:out_of_world" to "vide tombé chute",
        "minecraft:lava" to "lave brûlé",
        "minecraft:hot_floor" to "magma brûlé",
        "minecraft:in_fire" to "feu brûlé flammes",
        "minecraft:on_fire" to "feu brûlé flammes",
        "minecraft:campfire" to "feu de camp brûlé",
        "minecraft:sulfur_cube_hot" to "soufre brûlé",
        "minecraft:lightning_bolt" to "foudre éclair orage",
        "minecraft:drown" to "noyé noyade eau",
        "minecraft:explosion" to "explosion explosé",
        "minecraft:player_explosion" to "explosion explosé tnt",
        "minecraft:fireworks" to "explosion feu d'artifice",
        "minecraft:bad_respawn_point" to "explosion lit ancre",
        "minecraft:starve" to "faim affamé",
        "minecraft:cactus" to "cactus",
        "minecraft:sweet_berry_bush" to "baies buisson",
        "minecraft:freeze" to "gelé froid neige poudreuse",
        "minecraft:in_wall" to "étouffé coincé",
        "minecraft:cramming" to "écrasé entassé",
        "minecraft:falling_anvil" to "enclume écrasé",
        "minecraft:falling_block" to "écrasé bloc",
        "minecraft:falling_stalactite" to "stalactite écrasé",
        "minecraft:magic" to "magie potion",
        "minecraft:indirect_magic" to "magie potion sorcière",
        "minecraft:wither" to "wither",
        "minecraft:wither_skull" to "wither crâne",
        "minecraft:dragon_breath" to "dragon souffle",
        "minecraft:sonic_boom" to "warden",
        "minecraft:arrow" to "flèche",
        "minecraft:trident" to "trident",
        "minecraft:fireball" to "boule de feu",
        "minecraft:sting" to "piqûre abeille",
        "minecraft:thorns" to "épines",
        "minecraft:wind_charge" to "charge de vent",
        "minecraft:mace_smash" to "masse",
        "minecraft:spear" to "lance",
        "minecraft:outside_border" to "bordure du monde",
    )

    private val MOBS: Map<String, String> = mapOf(
        "minecraft:skeleton" to "squelette",
        "minecraft:stray" to "squelette vagabond",
        "minecraft:bogged" to "squelette embourbé",
        "minecraft:wither_skeleton" to "squelette wither",
        "minecraft:spider" to "araignée",
        "minecraft:cave_spider" to "araignée venimeuse",
        "minecraft:husk" to "zombie desséché",
        "minecraft:zombie_villager" to "zombie villageois",
        "minecraft:drowned" to "noyé zombie",
        "minecraft:zombified_piglin" to "piglin zombifié",
        "minecraft:witch" to "sorcière",
        "minecraft:pillager" to "pillard",
        "minecraft:vindicator" to "vindicateur",
        "minecraft:evoker" to "évocateur",
        "minecraft:ravager" to "ravageur",
        "minecraft:guardian" to "gardien",
        "minecraft:elder_guardian" to "gardien ancien",
        "minecraft:phantom" to "fantôme",
        "minecraft:magma_cube" to "cube de magma",
        "minecraft:silverfish" to "poisson d'argent",
        "minecraft:ender_dragon" to "dragon",
        "minecraft:creaking" to "grinceur",
        "minecraft:wolf" to "loup",
        "minecraft:iron_golem" to "golem de fer",
        "minecraft:polar_bear" to "ours",
        "minecraft:bee" to "abeille",
        "minecraft:llama" to "lama",
        "minecraft:goat" to "chèvre",
        "minecraft:dolphin" to "dauphin",
    )

    private val STRUCTURES: Map<String, String> = mapOf(
        "minecraft:ancient_city" to "cité antique",
        "minecraft:bastion_remnant" to "bastion vestiges",
        "minecraft:buried_treasure" to "trésor enfoui",
        "minecraft:desert_pyramid" to "temple du désert pyramide",
        "minecraft:end_city" to "cité de l'End",
        "minecraft:fortress" to "forteresse du Nether",
        "minecraft:igloo" to "igloo",
        "minecraft:jungle_pyramid" to "temple de la jungle",
        "minecraft:mansion" to "manoir",
        "minecraft:monument" to "monument océanique",
        "minecraft:nether_fossil" to "fossile",
        "minecraft:pillager_outpost" to "avant-poste pillards",
        "minecraft:stronghold" to "fort portail de l'End",
        "minecraft:swamp_hut" to "cabane de sorcière",
        "minecraft:trail_ruins" to "ruines des sentiers",
        "minecraft:trial_chambers" to "chambre d'épreuve",
    )

    /** Structures with variants, by the prefix of their ids. */
    private val STRUCTURE_FAMILIES: Map<String, String> = mapOf(
        "minecraft:village_" to "village",
        "minecraft:mineshaft" to "mine abandonnée",
        "minecraft:ocean_ruin" to "ruines sous-marines",
        "minecraft:ruined_portal" to "portail en ruine",
        "minecraft:shipwreck" to "épave",
        "minecraft:abandoned_camp" to "campement abandonné",
    )

    private val BIOMES: Map<String, String> = mapOf(
        "minecraft:mushroom_fields" to "champignons île",
        "minecraft:deep_dark" to "sombres profondeurs",
        "minecraft:cherry_grove" to "cerisiers",
        "minecraft:ice_spikes" to "pics de glace",
        "minecraft:eroded_badlands" to "badlands érodées",
        "minecraft:pale_garden" to "jardin pâle",
        "minecraft:bamboo_jungle" to "jungle de bambous",
        "minecraft:lush_caves" to "grottes luxuriantes",
        "minecraft:mangrove_swamp" to "mangrove",
        "minecraft:warped_forest" to "forêt biscornue",
        "minecraft:basalt_deltas" to "deltas de basalte",
    )

    private val DIMENSIONS: Map<String, String> = mapOf(
        "minecraft:the_nether" to "Nether enfer",
        "minecraft:the_end" to "End dragon",
    )

    private val ADVANCEMENTS: Map<String, String> = mapOf(
        "minecraft:story/mine_diamond" to "diamant",
        "minecraft:story/enter_the_nether" to "Nether portail",
        "minecraft:story/follow_ender_eye" to "œil de l'Ender fort",
        "minecraft:story/enter_the_end" to "End",
        "minecraft:story/shiny_gear" to "armure de diamant",
        "minecraft:nether/find_fortress" to "forteresse",
        "minecraft:nether/find_bastion" to "bastion",
        "minecraft:nether/obtain_ancient_debris" to "débris antiques",
        "minecraft:nether/netherite_armor" to "armure netherite",
        "minecraft:end/kill_dragon" to "dragon vaincu",
        "minecraft:end/find_end_city" to "cité de l'End",
        "minecraft:end/elytra" to "élytres",
        "minecraft:adventure/minecraft_trials_edition" to "chambre d'épreuve",
    )
}
