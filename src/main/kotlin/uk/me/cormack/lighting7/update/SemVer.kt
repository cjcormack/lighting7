package uk.me.cormack.lighting7.update

/**
 * A semantic version, parsed permissively and compared strictly per SemVer 2.0.0 §11.
 *
 * Deliberately has no dependency on `State` or on anything Ktor: comparing "what am I" against
 * "what is on GitHub" is the one decision in the update path that must never be wrong, and
 * keeping it a pure function is what makes it exhaustively testable.
 *
 * Release *tags* are hand-typed by a human at `git tag` time. The parser therefore returns null
 * rather than guessing on anything it does not fully understand, and callers treat null as
 * "don't offer an update" — see [compareVersions].
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Dot-separated prerelease identifiers. Empty means a stable release. */
    val prerelease: List<String> = emptyList(),
    /** Parsed so it can round-trip, but ignored for precedence (SemVer 2.0.0 §10). */
    val build: String? = null,
) : Comparable<SemVer> {

    val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }

        // §11.3: a version WITH a prerelease has lower precedence than the same version without.
        // 1.0.0-rc.1 < 1.0.0. Getting this backwards would make every released version look like
        // a downgrade from its own release candidate.
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        // §11.4: compare identifiers left to right.
        prerelease.zip(other.prerelease).forEach { (a, b) ->
            comparePrereleaseIdentifiers(a, b).let { if (it != 0) return it }
        }
        // §11.4.4: a larger set of fields wins when all preceding identifiers are equal.
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun toString(): String = buildString {
        append("$major.$minor.$patch")
        if (prerelease.isNotEmpty()) append("-").append(prerelease.joinToString("."))
        build?.let { append("+").append(it) }
    }

    private fun comparePrereleaseIdentifiers(a: String, b: String): Int {
        val aNum = a.toIntOrNull()?.takeIf { isNumericIdentifier(a) }
        val bNum = b.toIntOrNull()?.takeIf { isNumericIdentifier(b) }
        return when {
            // §11.4.1: numeric identifiers compare numerically, so rc.11 > rc.2.
            aNum != null && bNum != null -> aNum.compareTo(bNum)
            // §11.4.3: numeric identifiers always have LOWER precedence than alphanumeric ones.
            aNum != null -> -1
            bNum != null -> 1
            // §11.4.2: alphanumeric identifiers compare in ASCII sort order.
            else -> a.compareTo(b)
        }
    }

    private fun isNumericIdentifier(s: String): Boolean =
        s.isNotEmpty() && s.all { it.isDigit() }

    companion object {
        private val PATTERN = Regex(
            """^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+([0-9A-Za-z.-]+))?$"""
        )

        /**
         * Parse a version or a release tag. Tolerates a leading `v`/`V` and a missing minor or
         * patch (`v1.2` → `1.2.0`), because both are plausible things to find on a hand-cut tag.
         *
         * Returns null on anything else — including empty prerelease identifiers (`1.0.0-`) and
         * any non-numeric core component. Null is not an error state here; it is the signal that
         * makes [compareVersions] fail closed.
         */
        fun parse(raw: String?): SemVer? {
            val trimmed = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
            if (trimmed.isEmpty()) return null

            val match = PATTERN.matchEntire(trimmed) ?: return null
            val (majorRaw, minorRaw, patchRaw, prereleaseRaw, buildRaw) = match.destructured

            val prerelease = if (prereleaseRaw.isEmpty()) {
                emptyList()
            } else {
                val parts = prereleaseRaw.split(".")
                // A trailing or doubled dot yields an empty identifier, which SemVer forbids.
                if (parts.any { it.isEmpty() }) return null
                parts
            }

            return SemVer(
                major = majorRaw.toIntOrNull() ?: return null,
                minor = if (minorRaw.isEmpty()) 0 else minorRaw.toIntOrNull() ?: return null,
                patch = if (patchRaw.isEmpty()) 0 else patchRaw.toIntOrNull() ?: return null,
                prerelease = prerelease,
                build = buildRaw.takeIf { it.isNotEmpty() },
            )
        }
    }
}
