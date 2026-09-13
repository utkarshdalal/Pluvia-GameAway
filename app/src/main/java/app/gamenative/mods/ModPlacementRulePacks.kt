package app.gamenative.mods

import java.util.Locale

data class ModPlacementRulePack(
    val stableId: String,
    val version: Int,
    val directoryTargets: Map<String, String> = emptyMap(),
    val looseExtensions: Set<String> = emptySet(),
    val gameNameTokens: Set<String> = emptySet(),
)

object ModPlacementRulePacks {
    val bethesda = ModPlacementRulePack(
        stableId = "bethesda-data",
        version = 1,
        directoryTargets = setOf(
            "meshes", "textures", "scripts", "interface", "sound", "sounds", "strings", "skse", "f4se",
            "sfse", "seq", "video", "music", "lodsettings", "calientetools", "nemesis_engine",
        ).associateWith { "Data" },
        looseExtensions = setOf("esp", "esm", "esl", "bsa", "ba2"),
    )
    val bepInEx = ModPlacementRulePack("bepinex", 1, mapOf("BepInEx" to "BepInEx"))
    val melonLoader = ModPlacementRulePack(
        "melonloader",
        1,
        setOf("Mods", "UserData", "Plugins").associateWith { it },
    )
    val unreal = ModPlacementRulePack(
        "unreal-paks",
        1,
        mapOf("Content/Paks" to "Content/Paks", "Paks" to "Content/Paks"),
        setOf("pak", "ucas", "utoc"),
    )
    val redmod = ModPlacementRulePack(
        "redmod",
        1,
        setOf("archive/pc/mod", "r6", "red4ext", "bin/x64/plugins", "mods").associateWith { it },
        gameNameTokens = setOf("cyberpunk", "redmod"),
    )

    val builtIns: List<ModPlacementRulePack> = listOf(bethesda, bepInEx, melonLoader, unreal, redmod)

    val archiveSemanticAnchors: Set<String> = builtIns.flatMapTo(mutableSetOf()) { rule ->
        rule.directoryTargets.keys.flatMap { it.lowercase(Locale.ROOT).split('/') }
    }
}
