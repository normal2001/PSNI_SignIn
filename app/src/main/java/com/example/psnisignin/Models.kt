package com.example.psnisignin

/**
 * One sign-in record stored in SQLite.
 *
 * @property id Database primary key.
 * @property firstName Person's first name.
 * @property lastName Person's last name.
 * @property signInDateTime Required sign-in timestamp.
 * @property signOutDateTime Optional sign-out timestamp; null means still signed in.
 * @property visiting Optional name of the person being visited.
 * @property reason Optional configured reason for the visit.
 */
data class SignInRecord(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val signInDateTime: String,
    val signOutDateTime: String?,
    val visiting: String?,
    val reason: String?
)

/** One Slack user cached locally from the configured user group. */
data class SlackUser(
    val userId: String,
    val displayName: String
)
