package com.example.psnisignin

/** A cached recipient; a null ID represents the always-available Other option. */
data class SlackRecipient(val userId: String?, val displayName: String) {
    override fun toString(): String = displayName
}

/**
 * One sign-in record stored in SQLite.
 *
 * @property id Database primary key.
 * @property firstName Person's first name.
 * @property lastName Person's last name.
 * @property signInDateTime Required sign-in timestamp.
 * @property signOutDateTime Optional sign-out timestamp; null means still signed in.
 * @property reason Optional configured reason for the visit.
 * @property visiting Host display name captured at sign-in, or Other; null for unknown historical values.
 */
data class SignInRecord(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val signInDateTime: String,
    val signOutDateTime: String?,
    val reason: String?,
    val visiting: String? = null
)
