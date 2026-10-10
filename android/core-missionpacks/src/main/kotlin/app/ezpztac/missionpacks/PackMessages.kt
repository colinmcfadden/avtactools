package app.ezpztac.missionpacks

import app.ezpztac.network.InviteAccepted

/**
 * Words for a person about a pack call that did not succeed, and about an invitation they accepted: the web's `packErrorMessage`
 * (packApi.js) and `joinedMessage` (useInviteLink.js). Only the app's own words: a server's text is never shown, since a failure the
 * server did not mean to happen can carry its internals.
 */
public object PackMessages {
    private val MESSAGES = mapOf(
        "feature_disabled" to "Mission Packs are not turned on for your account yet.",
        "pack_not_found" to "That pack is gone, or you are no longer in it.",
        "pack_read_only" to "You can view this pack but not change it.",
        "owner_only" to "Only the pack's owner can do that.",
        "pack_finished" to "This pack is finished. It is read-only until its owner reopens it.",
        "invalid_name" to "Give it a name of 1 to 100 characters.",
        "invalid_description" to "The description can be 2,000 characters at most.",
        "item_too_large" to "That is larger than a pack item can be (5 MB).",
        "item_not_found" to "That item is no longer in the pack.",
        "item_exists" to "The pack already has that item.",
        "source_not_found" to "That is no longer in your Library.",
        "mission_not_supported" to "Imported AMPS missions cannot go in a pack yet. Sketched routes can.",
        "unreadable_source" to "That Library item cannot be read.",
        "not_your_original" to "Only the person who copied this in can update it from the original.",
        "original_gone" to "The original is no longer in your Library.",
        "empty_point_set" to "A point set with no points cannot be saved.",
        "not_a_teammate" to "Only people on one of your teams can be added by name. Invite anyone else by email.",
        "already_member" to "They are already in it.",
        "member_not_found" to "They are no longer a member.",
        "owner_must_transfer" to "Make someone else the owner first.",
        "invalid_email" to "Enter a valid email address.",
        "invalid_role" to "Choose a role.",
        "rate_limited" to "Too many invitations for now. Try again later.",
        "invite_gone" to "That invitation has already been used or was withdrawn.",
        "invite_expired" to "That invitation has expired. Ask whoever sent it for a new one.",
        "invite_not_found" to "That invitation link is not valid.",
        "team_not_found" to "That team is gone, or you are no longer in it.",
        "team_managers_only" to "Only the team's owner or an admin can do that.",
        "not_in_team" to "You can only share a pack with a team you are in.",
        "invalid_query" to "Type at least two characters.",
    )

    /** What to tell the person about [failure] ([PackFailure.of] reads one from a call's exception). */
    public fun words(failure: PackFailure): String {
        if (failure.status == 0) return "There is no connection to the server. Try again when you are back online."
        failure.code?.let { code -> MESSAGES[code]?.let { return it } }
        return when (failure.status) {
            404 -> MESSAGES.getValue("pack_not_found")
            403 -> "You do not have permission to do that."
            429 -> "Too many requests. Wait a moment and try again."
            else -> "Something went wrong on the server. Try again."
        }
    }

    /** What to tell the person once an invitation has let them in. */
    public fun joinedMessage(answer: InviteAccepted?): String {
        val pack = answer?.pack
        if (pack != null) return "You joined ${pack.name} as ${roleWords(pack.role)}."
        val team = answer?.team
        if (team != null) return "You joined the team ${team.name}."
        return "You accepted the invitation."
    }

    private fun roleWords(role: String?): String = when (role) {
        "viewer" -> "a viewer"
        "admin" -> "an admin"
        "owner" -> "the owner"
        "member" -> "a member"
        else -> "an editor"
    }
}
