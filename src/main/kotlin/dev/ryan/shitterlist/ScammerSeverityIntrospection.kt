package dev.ryan.throwerlist

object ScammerSeverityIntrospection {
    data class Breakdown(
        val severityLabel: String?,
        val severityColor: Int?,
        val score: Double?,
        val actionLabel: String?,
        val reasons: List<String>,
    )

    fun fromEntry(entry: ScammerListManager.ScammerEntry): Breakdown? =
        extract(entry, "getSeverityResult")

    fun fromCheckResult(result: ScammerCheckService.CheckResult): Breakdown? =
        extract(result, "getSeverityResult")

    private fun extract(target: Any, methodName: String): Breakdown? {
        val result = runCatching {
            target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }?.invoke(target)
        }.getOrNull() ?: return null

        val severity = getter(result, "getSeverity")
        return Breakdown(
            severityLabel = getter(severity, "getLabel") as? String,
            severityColor = getter(severity, "getColor") as? Int,
            score = getter(result, "getScore") as? Double,
            actionLabel = (getter(result, "getRecommendedAction") as? Enum<*>)?.name?.lowercase()?.replace('_', ' '),
            reasons = (getter(result, "getReasons") as? List<*>)?.filterIsInstance<String>().orEmpty(),
        )
    }

    private fun getter(target: Any?, name: String): Any? {
        if (target == null) {
            return null
        }
        return runCatching {
            target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target)
        }.getOrNull()
    }
}
