package dev.ryan.throwerlist

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Path

object SkylistPlusRuntimeVersion {
    private const val modId = "skylistplus"
    private val minecraftVersionPattern = Regex("""-(\d+\.\d+\.\d+)$""")

    fun currentVersion(): String =
        FabricLoader.getInstance()
            .getModContainer(modId)
            .orElseThrow { IllegalStateException("Missing Fabric metadata for $modId") }
            .metadata
            .version
            .friendlyString

    fun minecraftVersion(): String =
        minecraftVersionPattern.find(currentVersion())?.groupValues?.get(1).orEmpty()

    fun featureVersion(): String {
        val currentVersion = currentVersion()
        val minecraftVersion = minecraftVersion()
        return if (minecraftVersion.isNotEmpty() && currentVersion.endsWith("-$minecraftVersion")) {
            currentVersion.removeSuffix("-$minecraftVersion")
        } else {
            currentVersion
        }
    }

    fun currentJarPath(): Path? =
        runCatching {
            Path.of(SkylistPlusRuntimeVersion::class.java.protectionDomain.codeSource.location.toURI())
                .takeIf { path -> path.fileName?.toString()?.endsWith(".jar", ignoreCase = true) == true }
        }.getOrNull()
}
