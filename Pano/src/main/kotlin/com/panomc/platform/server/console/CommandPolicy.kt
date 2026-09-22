package com.panomc.platform.server.console

/**
 * Decides whether a console grant refuses a command (§2.4.12).
 *
 * Console access is operator access: `op` hands somebody the server, `stop` takes it away from
 * everyone on it. A permission node that lets a moderator watch the console and run `say` should
 * therefore be able to say which words they may not type, and that list lives in the node's own
 * `context.denyCommands` rather than in a second table nobody remembers to keep in step with the
 * grants.
 *
 * Only the first token is matched, and matching is case-insensitive. That is deliberate rather
 * than lazy: a Minecraft command's first word is the command, and everything after it is
 * arguments that no pattern language could usefully constrain. A pattern may end in `*`, which
 * matches any command starting with what precedes it — `whitelist*` covers `whitelist` and
 * plugins' `whitelistadd` alike.
 *
 * Pure, and deliberately without any notion of who is asking: the caller resolves which nodes
 * apply and hands their patterns in. That is what keeps this testable without a database and what
 * lets the same rule be applied to any future place a command can be entered.
 */
object CommandPolicy {
    /** The context key a permission node stores its policy under. */
    const val CONTEXT_KEY = "denyCommands"

    /**
     * Longest pattern taken seriously.
     *
     * Anything beyond it is a payload rather than a command name, and is ignored outright instead
     * of being truncated: a truncated `whitelistverylong…*` would quietly become a different rule
     * from the one somebody typed, and a deny list that does not mean what it says is worse than
     * one entry of it not working.
     */
    const val MAX_PATTERN_LENGTH = 64

    /**
     * The command word a pattern is matched against.
     *
     * A leading slash is dropped because a console takes commands without one and people type it
     * anyway, and letting `/op` slip past a deny list for `op` would make the whole feature a
     * decoration. Namespaced commands keep their namespace (`worldedit:set`), since that is part
     * of the name a server dispatches on.
     */
    fun firstToken(command: String): String = command
        .trim()
        .removePrefix("/")
        .trimStart()
        .takeWhile { !it.isWhitespace() }
        .lowercase()

    /** Whether [pattern] refuses [command]. */
    fun matches(pattern: String, command: String): Boolean {
        val cleaned = pattern.trim().lowercase()

        if (cleaned.isEmpty() || cleaned.length > MAX_PATTERN_LENGTH) {
            return false
        }

        val token = firstToken(command)

        if (token.isEmpty()) {
            return false
        }

        if (!cleaned.endsWith("*")) {
            return token == cleaned
        }

        val prefix = cleaned.dropLast(1)

        // A bare `*` denies everything, which is a legitimate way to grant read-only console.
        return prefix.isEmpty() || token.startsWith(prefix)
    }

    /**
     * The first pattern in [patterns] that refuses [command], or null when none does.
     *
     * The pattern is returned rather than a boolean because the panel shows it: "op" and "op*"
     * fail for visibly different reasons, and an admin editing the grant needs to know which
     * entry they are looking for.
     */
    fun deniedPattern(patterns: Collection<String>, command: String): String? =
        patterns.firstOrNull { matches(it, command) }?.trim()?.lowercase()
}
