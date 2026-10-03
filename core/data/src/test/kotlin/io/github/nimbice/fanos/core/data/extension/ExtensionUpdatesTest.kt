package io.github.nimbice.fanos.core.data.extension

import io.github.nimbice.fanos.core.model.ExtensionInfo
import io.github.nimbice.fanos.core.model.ExtensionStatus
import io.github.nimbice.fanos.core.updater.PublishedExtension
import io.github.nimbice.fanos.source.api.Extensions
import org.junit.Test
import kotlin.test.assertEquals

class ExtensionUpdatesTest {

    private fun installed(site: String, versionCode: Long, status: ExtensionStatus = ExtensionStatus.Working) =
        ExtensionInfo(id = "ext.$site", name = site, versionName = "3.$versionCode", versionCode = versionCode, sources = emptyList(), status = status)

    private fun published(site: String, versionCode: Long, apiLevel: Int = Extensions.API_LEVEL) =
        PublishedExtension("ext.$site", site, versionCode, "$apiLevel.$versionCode", apiLevel, "en", 100, "abc", "https://example.com/$site")

    @Test
    fun `a newer version of an installed extension is an update, and one not installed is available`() {
        val compared =
            ExtensionUpdates.compare(
                installed = listOf(installed("patreon", 5), installed("royalroad", 2), installed("boxnovel", 1)),
                published = listOf(published("patreon", 6), published("royalroad", 2), published("wuxiaworld", 1), published("boxnovel", 1)),
            )

        assertEquals(listOf("ext.patreon"), compared.updates.map { it.id })
        assertEquals(listOf("ext.wuxiaworld"), compared.available.map { it.id })
    }

    @Test
    fun `an extension this app can't load isn't offered, and a broken one is updated`() {
        val compared =
            ExtensionUpdates.compare(
                installed = listOf(installed("patreon", 5), installed("lnmtl", 0, ExtensionStatus.Broken)),
                published = listOf(published("patreon", 6, apiLevel = Extensions.API_LEVEL + 1), published("lnmtl", 3), published("zeta", 1, apiLevel = 0)),
            )

        assertEquals(listOf("ext.lnmtl"), compared.updates.map { it.id })
        assertEquals(emptyList(), compared.available)
    }

    @Test
    fun `available extensions come in name order`() {
        val compared = ExtensionUpdates.compare(emptyList(), listOf(published("scribblehub", 1), published("Fanmtl", 1), published("lnmtl", 1)))

        assertEquals(listOf("Fanmtl", "lnmtl", "scribblehub"), compared.available.map { it.name })
    }
}
