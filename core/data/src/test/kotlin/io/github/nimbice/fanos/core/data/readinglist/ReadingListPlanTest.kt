package io.github.nimbice.fanos.core.data.readinglist

import io.github.nimbice.fanos.core.data.readinglist.ListStep.Bookmark
import io.github.nimbice.fanos.core.data.readinglist.ListStep.Put
import io.github.nimbice.fanos.core.data.readinglist.ListStep.TakeOff
import io.github.nimbice.fanos.source.api.ReadingList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingListPlanTest {

    private val reading = ReadingList("0", "Reading List")
    private val later = ReadingList("2", "Plan to Read")
    private val lists = listOf(reading, ReadingList("1", "Completed"), later)
    private val lantern = "https://www.novelupdates.com/series/lantern-keeper/"
    private val harbour = "https://www.novelupdates.com/series/quiet-harbour/"
    private val chapter3 = "https://www.novelupdates.com/extnu/9003/"

    @Test
    fun `a novel goes on the list named like its first section that has one, else the first list`() {
        assertEquals(later, ReadingListPlan.listFor(listOf("Favourites", "plan to read"), lists))
        assertEquals(reading, ReadingListPlan.listFor(listOf("Reading"), lists))
        assertEquals(null, ReadingListPlan.listFor(listOf("Reading"), emptyList()))
    }

    @Test
    fun `a novel new to the lists is put on its list, then bookmarked where reading got to`() {
        val steps = ReadingListPlan.steps(listOf(WantedNovel(lantern, listOf("Plan to Read"), chapter3)), emptyList(), lists)

        assertEquals(listOf(Put(lantern, later), Bookmark(lantern, chapter3)), steps)
    }

    @Test
    fun `only what changed since the site was told goes`() {
        val told = listOf(ToldNovel(lantern, "2", chapter3), ToldNovel(harbour, "0", null))
        val wanted =
            listOf(
                WantedNovel(lantern, listOf("Plan to Read"), "https://www.novelupdates.com/extnu/9004/"),
                WantedNovel(harbour, listOf("Completed"), null),
            )

        assertEquals(
            listOf(Bookmark(lantern, "https://www.novelupdates.com/extnu/9004/"), Put(harbour, lists[1])),
            ReadingListPlan.steps(wanted, told, lists),
        )
        assertTrue(ReadingListPlan.steps(listOf(WantedNovel(lantern, listOf("Plan to Read"), chapter3)), told.take(1), lists).isEmpty())
    }

    @Test
    fun `a novel the app put there comes off once it's out of the library, and no other does`() {
        val told = listOf(ToldNovel(lantern, "2", chapter3), ToldNovel(harbour, "0", null))

        assertEquals(listOf(TakeOff(harbour)), ReadingListPlan.steps(listOf(WantedNovel(lantern, listOf("Plan to Read"), chapter3)), told, lists))
    }

    @Test
    fun `asked for everything, every novel is put there and bookmarked again`() {
        val told = listOf(ToldNovel(lantern, "2", chapter3))

        assertEquals(
            listOf(Put(lantern, later), Bookmark(lantern, chapter3)),
            ReadingListPlan.steps(listOf(WantedNovel(lantern, listOf("Plan to Read"), chapter3)), told, lists, everything = true),
        )
    }

    @Test
    fun `an address written another way is the same novel, not one to take off`() {
        val told = listOf(ToldNovel("http://novelupdates.com/series/Lantern-Keeper", "2", chapter3))

        assertTrue(ReadingListPlan.steps(listOf(WantedNovel(lantern, listOf("Plan to Read"), chapter3)), told, lists).isEmpty())
    }

    @Test
    fun `with no lists to put them on nothing is told`() {
        assertTrue(ReadingListPlan.steps(listOf(WantedNovel(lantern, emptyList(), chapter3)), listOf(ToldNovel(harbour, "0", null)), emptyList()).isEmpty())
    }
}
