package dev.ryan.throwerlist

object AutokickMessageFormatter {
    const val ignPlaceholder: String = "<IGN>"
    const val reasonPlaceholder: String = "<REASON>"

    fun format(username: String, reason: String, isRemote: Boolean): String {
        return ConfigManager.getAutokickMessageTemplate(isRemote)
            .replace(ignPlaceholder, username)
            .replace(reasonPlaceholder, reason)
    }

    fun isValidTemplate(template: String): Boolean {
        return template.contains(ignPlaceholder) && template.contains(reasonPlaceholder)
    }
}
