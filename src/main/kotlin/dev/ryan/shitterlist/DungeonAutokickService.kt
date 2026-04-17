package dev.ryan.throwerlist

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

object DungeonAutokickService {
    private const val hypixelRelayPath = "/hypixel/skyblock/profiles/"
    private val executor = Executors.newCachedThreadPool()

    fun checkPlayer(username: String, uuid: String, floor: String): CompletableFuture<CheckResult?> {
        val normalizedFloor = floor.trim().uppercase(Locale.ROOT)
        val pbThreshold = ConfigManager.getDungeonPbThreshold(normalizedFloor)
        val spiritCheck = ConfigManager.isDungeonNoSpiritPetEnabled()
        val princeCheck = ConfigManager.isDungeonNoPrinceAttributeShardEnabled()
        val thornsCheck = ConfigManager.isDungeonThornsOnEquippedArmourEnabled()
        if (pbThreshold.isNullOrBlank() && !spiritCheck && !princeCheck && !thornsCheck) {
            return CompletableFuture.completedFuture(null)
        }

        return CompletableFuture.supplyAsync({
            val root = fetchDungeonProfileRoot(uuid, username)

            val member = selectMember(root, uuid)
                ?: throw IllegalStateException("No SkyBlock profile data was returned for $username")

            val failures = mutableListOf<String>()
            val announcementParts = mutableListOf<String>()

            if (!pbThreshold.isNullOrBlank()) {
                val thresholdMillis = parseDurationMillis(pbThreshold)
                    ?: throw IllegalArgumentException("Invalid configured PB threshold \"$pbThreshold\" for $normalizedFloor")
                val profilePb = readFastestSPlusMillis(member, normalizedFloor)
                when {
                    profilePb == null -> {
                        failures += "no $normalizedFloor S+ PB"
                        announcementParts += "$normalizedFloor has no recorded S+ PB"
                    }

                    profilePb > thresholdMillis -> {
                        failures += "$normalizedFloor PB ${formatDuration(profilePb)} > ${formatDuration(thresholdMillis)}"
                        announcementParts += "$normalizedFloor PB ${formatDuration(profilePb)} is slower than ${formatDuration(thresholdMillis)}"
                    }
                }
            }

            if (spiritCheck && !hasSpiritPet(member)) {
                failures += "no Spirit pet"
                announcementParts += "has no Spirit pet"
            }

            if (princeCheck && !hasPrinceAttributeShard(member)) {
                failures += "no Prince attribute shard"
                announcementParts += "has no Prince attribute shard"
            }

            if (thornsCheck && !hasThornsOnEquippedArmourSet(member)) {
                failures += "no Thorns on equipped armour set"
                announcementParts += "has no Thorns on equipped armour set"
            }

            if (failures.isEmpty()) {
                null
            } else {
                CheckResult(
                    reason = failures.joinToString(", "),
                    partyMessage = "[SL] $username failed dungeon checks: ${announcementParts.joinToString(", ")}",
                )
            }
        }, executor).exceptionally {
            ThrowerListMod.logger.warn("Failed to run dungeon autokick check for {} on {}", username, floor, it)
            null
        }
    }

    private fun fetchDungeonProfileRoot(uuid: String, username: String): JsonObject {
        val normalizedUuid = uuid.replace("-", "").lowercase(Locale.ROOT)

        val root = WorkerRelay.fetchJson("$hypixelRelayPath$normalizedUuid")
            ?: throw IllegalStateException("Hypixel relay returned no SkyBlock profile data for $username")
        if (root.get("success")?.asBoolean == false) {
            throw IllegalStateException(root.getOptionalString("cause") ?: root.getOptionalString("message") ?: "Hypixel relay lookup failed")
        }
        if (root.getAsJsonArray("profiles") == null) {
            throw IllegalStateException("Hypixel relay returned no SkyBlock profile data for $username")
        }
        return root
    }

    fun normalizeConfiguredTime(value: String): String? = parseDurationMillis(value)?.let(::formatDuration)

    private fun selectMember(root: JsonObject, uuid: String): JsonObject? {
        val normalizedUuid = uuid.replace("-", "").lowercase(Locale.ROOT)
        val profiles = root.getAsJsonArray("profiles") ?: return null
        val profileObjects = profiles.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        val selectedProfile = profileObjects.firstOrNull { it.get("selected")?.asBoolean == true } ?: profileObjects.firstOrNull()
        return selectedProfile
            ?.getAsJsonObject("members")
            ?.getAsJsonObject(normalizedUuid)
    }

    private fun readFastestSPlusMillis(member: JsonObject, floor: String): Long? {
        val dungeonType = when {
            floor.startsWith("M") -> "master_catacombs"
            else -> "catacombs"
        }
        val floorNumber = floor.drop(1)
        val dungeonTypes = member.getAsJsonObject("dungeons")
            ?.getAsJsonObject("dungeon_types")
            ?: return null
        val floorStats = dungeonTypes.getAsJsonObject(dungeonType)
            ?.getAsJsonObject("fastest_time_s_plus")
            ?: return null
        return floorStats.getAsLongOrNull(floorNumber)
    }

    private fun hasSpiritPet(member: JsonObject): Boolean {
        val pets = member.getAsJsonObject("pets_data")
            ?.getAsJsonArray("pets")
            ?: return false
        return pets.any { pet ->
            pet.asJsonObject.getOptionalString("type")?.equals("SPIRIT", ignoreCase = true) == true
        }
    }

    private fun hasPrinceAttributeShard(member: JsonObject): Boolean {
        val equipment = readInventoryItems(member, "equipment_contents")
        return equipment.any { item ->
            val hasPrince = item.containsString("prince")
            val hasShard = item.containsString("attribute shard") ||
                item.itemId?.contains("shard", ignoreCase = true) == true
            hasPrince && hasShard
        }
    }

    private fun hasThornsOnEquippedArmourSet(member: JsonObject): Boolean {
        val equippedArmour = readInventoryItems(member, "inv_armor")
        return equippedArmour.size == 4 && equippedArmour.all { (it.enchantments["thorns"] ?: 0) > 0 }
    }

    private fun readInventoryItems(member: JsonObject, inventoryKey: String): List<HypixelInventoryDecoder.InventoryItem> {
        val encoded = member.getAsJsonObject("inventory")
            ?.getAsJsonObject(inventoryKey)
            ?.getOptionalString("data")
        return HypixelInventoryDecoder.decodeItems(encoded)
    }

    private fun parseDurationMillis(value: String): Long? {
        val trimmed = value.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) {
            return null
        }

        Regex("""^(\d+):([0-5]\d)$""").matchEntire(trimmed)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: return null
            val seconds = match.groupValues[2].toLongOrNull() ?: return null
            return (minutes * 60 + seconds) * 1000
        }

        Regex("""^(\d+)m([0-5]?\d)s$""").matchEntire(trimmed)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: return null
            val seconds = match.groupValues[2].toLongOrNull() ?: return null
            return (minutes * 60 + seconds) * 1000
        }

        return null
    }

    private fun formatDuration(valueMillis: Long): String {
        val totalSeconds = (valueMillis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    private fun JsonObject.getOptionalString(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.getAsLongOrNull(key: String): Long? =
        get(key)?.takeIf { !it.isJsonNull }?.asDouble?.toLong()

    data class CheckResult(
        val reason: String,
        val partyMessage: String,
    )
}
