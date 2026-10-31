package app.ezpztac.packs

import app.ezpztac.designsystem.EzpzIcons
import app.ezpztac.missionpacks.PackStatus

/**
 * How the open pack stands with the server, as the phone's pack chip shows it in place of its group icon (the owner's decision of 2026-10-13): a shape of its
 * own for each ([icon]: never colour alone), drawn in its [tone], and whether it wants the person's [attention] (the words under the row: offline, paused, cannot
 * reach, no longer available). Read in the order [PackWords.status] reads it, so the icon and the words always say the same.
 */
enum class PackStanding(val icon: Int, val tone: PackTone, val attention: Boolean) {
    /** Everything has reached everyone, live or polling. */
    SYNCED(EzpzIcons.cloudDone, PackTone.SUCCESS, attention = false),

    /** This person's changes are on their way. */
    SAVING(EzpzIcons.cloudUpload, PackTone.WARNING, attention = false),

    /** No answer from the server: the device's copy, its changes waiting (or none). */
    OFFLINE(EzpzIcons.cloudOff, PackTone.WARNING, attention = true),

    /** Nothing held yet: the device has no copy and the server has not answered. */
    OPENING(EzpzIcons.hourglassTop, PackTone.WARNING, attention = false),

    /** Stopped for the account's sake: every change kept on the device. */
    PAUSED(EzpzIcons.pauseFill, PackTone.WARNING, attention = true),

    /** It could not be loaded, and the device has no copy. */
    UNREACHABLE(EzpzIcons.error, PackTone.ERROR, attention = true),

    /** Deleted, or no longer this person's to see. */
    GONE(EzpzIcons.error, PackTone.ERROR, attention = true),

    /** Finished: read-only for everyone (the chip says so in words, and every panel has the banner). */
    FINISHED(EzpzIcons.groups, PackTone.PACK, attention = false),
    ;

    companion object {
        fun of(pack: OpenPackUi): PackStanding = when {
            pack.status == PackStatus.LOADING -> OPENING
            pack.status == PackStatus.ERROR -> UNREACHABLE
            pack.status == PackStatus.GONE -> GONE
            pack.status == PackStatus.PAUSED -> PAUSED
            pack.finished -> FINISHED
            pack.status == PackStatus.OFFLINE -> OFFLINE
            pack.pending > 0 -> SAVING
            else -> SYNCED
        }
    }
}

/** The colour role a [PackStanding] is drawn in: the status colours, and a finished pack's own (its group icon in the pack's colour). */
enum class PackTone { SUCCESS, WARNING, ERROR, PACK }
