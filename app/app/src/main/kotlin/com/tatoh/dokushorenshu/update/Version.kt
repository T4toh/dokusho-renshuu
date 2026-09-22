package com.tatoh.dokushorenshu.update

/** Semver 2.0 sin metadata de build. Los tags de las releases son `vX.Y.Z-beta.N`;
 *  `db-vN` (releases del diccionario) no parsea a propósito. */
data class Version(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: List<String> = emptyList(),
) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
            .let { if (it != 0) return it }
        // Semver §11: a igual X.Y.Z, la versión SIN prerelease es la mayor.
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return other.prerelease.size.compareTo(prerelease.size)
        }
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease[i]
            val b = other.prerelease[i]
            val na = a.toIntOrNull()
            val nb = b.toIntOrNull()
            val c = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1 // numérico < alfanumérico
                nb != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (prerelease.isEmpty()) "" else "-" + prerelease.joinToString(".")

    companion object {
        private val PATRON = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$""")

        /** `v` opcional, `X.Y.Z` o `X.Y.Z-ident(.ident)*`. Cualquier otra cosa → null. */
        fun parse(texto: String): Version? {
            val m = PATRON.matchEntire(texto.trim()) ?: return null
            val (major, minor, patch, pre) = m.destructured
            return Version(
                major.toInt(), minor.toInt(), patch.toInt(),
                if (pre.isEmpty()) emptyList() else pre.split('.'),
            )
        }
    }
}
