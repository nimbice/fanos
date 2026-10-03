package io.github.nimbice.fanos.core.model

/** An installed extension, as the list of extensions shows it. */
data class ExtensionInfo(
    /** Its package name, which identifies it across versions. */
    val id: String,
    val name: String,
    val versionName: String,
    /** Its version as a number, for telling whether a published one is newer; 0 when it can't be read. */
    val versionCode: Long = 0,
    /** The sources it adds, while it's [ExtensionStatus.Working]. */
    val sources: List<SourceInfo>,
    val status: ExtensionStatus,
    /** What went wrong, when it's [ExtensionStatus.Broken]. */
    val problem: String? = null,
)

enum class ExtensionStatus {
    /** Loaded: its sources are there to read from. */
    Working,

    /** Built for a newer Fanos than this one. */
    NeedsNewerApp,

    /** Built for a Fanos so old that this one no longer loads it. */
    Outdated,

    /** Signed with a key not (or no longer) trusted. */
    Untrusted,

    /** Couldn't be loaded; see [ExtensionInfo.problem]. */
    Broken,
}
