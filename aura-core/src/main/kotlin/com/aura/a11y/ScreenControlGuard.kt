package com.aura.a11y

/**
 * The rules that hold regardless of what the user has approved.
 *
 * A session bounds *how much* Aura may do. These bound *what*, and nothing
 * turns them off — a confirmation is a user saying "go ahead with this task",
 * not "you may now do anything in any app".
 */
object ScreenControlGuard {

    /** Why an action is refused outright. */
    sealed class Block(val code: String, val reason: String) {
        object SelfDrive : Block(
            "self_drive_denied",
            "Aura will not operate its own interface.",
        )

        class DeniedApp(pkg: String) : Block(
            "app_denied",
            "Screen control is not allowed in $pkg.",
        )

        object PasswordVisible : Block(
            "password_field_visible",
            "A password field is on screen. Aura will not act until it is gone.",
        )

        object PasswordTarget : Block(
            "password_target",
            "Aura will not type into a password field.",
        )
    }

    /**
     * Packages Aura may never drive, whatever the user approves.
     *
     * **Aura's own package is the critical entry.** Without it the agent can
     * tap its own confirmation dialogs, which collapses every gate in the
     * system into nothing — the tripwire, the session prompt, the permission
     * dialog, all of them become buttons the agent can press. Every other rule
     * here is downstream of that one.
     *
     * The settings package is denied because the accessibility toggle lives
     * there: an agent that can reach it can grant itself capabilities or
     * disable its own kill switch.
     */
    private val DENIED_PACKAGES = setOf(
        "com.aura",
        "com.aura.debug",
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
    )

    /**
     * Labels that mean an action is probably irreversible.
     *
     * The highest value-per-line rule in the design, and cheap: a regex over a
     * string already extracted. It converts "the agent might do something
     * terrible silently" into "the agent asks before anything that looks
     * terrible", which is a different product.
     *
     * It does not catch everything, and is not meant to. It catches the cases
     * where being wrong is expensive and irreversible.
     */
    private val DESTRUCTIVE_LABEL = Regex(
        "\\b(delete|remove|uninstall|erase|wipe|format|reset|" +
            "pay|buy|purchase|checkout|order|subscribe|transfer|send money|" +
            "confirm|submit|log ?out|sign ?out|deactivate|close account|unsubscribe)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The unconditional checks, run before any session or budget logic.
     *
     * @param targetIsPassword whether the element being acted on is a password
     *   field. Typing into one is refused even when the screen would otherwise
     *   allow acting.
     */
    fun block(
        packageName: String,
        snapshot: UiSnapshot?,
        targetIsPassword: Boolean = false,
        auraPackages: Set<String> = DENIED_PACKAGES,
    ): Block? = when {
        packageName in auraPackages && packageName.startsWith("com.aura") -> Block.SelfDrive
        packageName in auraPackages -> Block.DeniedApp(packageName)
        targetIsPassword -> Block.PasswordTarget
        // Refusing while ANY password field is visible, not just when the
        // target is one. A login screen is the worst possible place for an
        // agent to be improvising, and the cost of being conservative here is
        // one extra turn.
        snapshot?.hasPasswordField == true -> Block.PasswordVisible
        else -> null
    }

    /**
     * Whether [label] looks like it does something irreversible.
     *
     * A hit does not block — it forces a confirmation naming the literal button
     * and app, so the user approves the specific thing rather than the general
     * capability.
     */
    fun looksDestructive(label: String): Boolean = DESTRUCTIVE_LABEL.containsMatchIn(label)

    /** Denylist plus any user-configured blocked apps. */
    fun deniedPackages(extra: Set<String> = emptySet()): Set<String> = DENIED_PACKAGES + extra
}
