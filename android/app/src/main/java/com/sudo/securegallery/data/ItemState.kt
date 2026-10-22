package com.sudo.securegallery.data

/**
 * Where one gallery image is in the pipeline (§8.4).
 *
 * [PENDING] is the only state not named in §8.4: the scan has indexed the image and it waits
 * for detection. Every other state comes from the spec. If any step fails the item becomes
 * [FAILED] and the original is left untouched.
 */
enum class ItemState {
    PENDING,
    NOT_DETECTED,
    REDACTED,
    NEEDS_CHECK,
    VAULTED,
    ORIGINAL_STILL_PUBLIC,
    RESTORED,
    FAILED;

    /** True if the pipeline may move an item from this state to [next]. */
    fun canMoveTo(next: ItemState): Boolean = next in ALLOWED.getValue(this)

    companion object {
        private val ALLOWED: Map<ItemState, Set<ItemState>> = mapOf(
            // Detection decides; a failure keeps the original.
            PENDING to setOf(NOT_DETECTED, REDACTED, NEEDS_CHECK, FAILED),
            // A changed file is indexed again.
            NOT_DETECTED to setOf(PENDING),
            // Redacted copy saved: the user approves or denies deletion, corrects boxes,
            // or marks the image "not sensitive".
            REDACTED to setOf(NEEDS_CHECK, VAULTED, ORIGINAL_STILL_PUBLIC, RESTORED, FAILED),
            NEEDS_CHECK to setOf(REDACTED, VAULTED, ORIGINAL_STILL_PUBLIC, RESTORED, FAILED),
            // Deletion denied: the user can retry it or restore the original.
            ORIGINAL_STILL_PUBLIC to setOf(VAULTED, RESTORED),
            VAULTED to setOf(RESTORED),
            // Restored images are never flagged again automatically (§8.4).
            RESTORED to emptySet(),
            // A failed item can be scanned again.
            FAILED to setOf(PENDING),
        )
    }
}
