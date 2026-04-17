package dev.ryan.throwerlist

object PartyFinderFloorTracker {
    private const val staleAfterMillis = 15 * 60 * 1000L

    @Volatile
    private var selectedFloor: String? = null

    @Volatile
    private var updatedAt: Long = 0L

    fun record(floor: String) {
        selectedFloor = floor
        updatedAt = System.currentTimeMillis()
    }

    fun currentFloor(): String? {
        val floor = selectedFloor ?: return null
        if (System.currentTimeMillis() - updatedAt > staleAfterMillis) {
            clear()
            return null
        }
        return floor
    }

    fun clear() {
        selectedFloor = null
        updatedAt = 0L
    }
}
