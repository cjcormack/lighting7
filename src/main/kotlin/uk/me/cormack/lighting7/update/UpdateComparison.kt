package uk.me.cormack.lighting7.update

import kotlinx.serialization.Serializable

/** The result of comparing the running build against the latest published release. */
@Serializable
enum class UpdateAvailability {
    /** Running the latest published release, or newer in a way that isn't [AHEAD]. */
    UP_TO_DATE,

    /** A strictly newer release exists. The only value that offers an update. */
    UPDATE_AVAILABLE,

    /** The running build is newer than the latest release — a local build from `main`. */
    AHEAD,

    /** Either side was unparseable, so no claim can be made. Never offers an update. */
    UNKNOWN,
}

/**
 * Compare the running version against a release tag.
 *
 * **Fails closed.** A version that cannot be parsed on either side yields [UNKNOWN], never
 * [UPDATE_AVAILABLE]. Release tags are typed by hand, and `v1.2.3-hotfix wednesday` must not be
 * silently treated as newer than what is installed — the consequence of being wrong here is
 * restarting a lighting desk to install something arbitrary.
 *
 * The dev-build case never reaches this function: `UpdateService` short-circuits on
 * [BuildInfo.Channel] and [InstallKind] first. Gate on those flags, never on a version-string
 * heuristic — the packaged default is `1.0.0` while `project.version` is `0.0.1`, so any
 * heuristic would be wrong in both directions.
 */
fun compareVersions(currentVersion: String?, latestTag: String?): UpdateAvailability {
    val current = SemVer.parse(currentVersion) ?: return UpdateAvailability.UNKNOWN
    val latest = SemVer.parse(latestTag) ?: return UpdateAvailability.UNKNOWN

    return when {
        latest > current -> UpdateAvailability.UPDATE_AVAILABLE
        latest < current -> UpdateAvailability.AHEAD
        else -> UpdateAvailability.UP_TO_DATE
    }
}
