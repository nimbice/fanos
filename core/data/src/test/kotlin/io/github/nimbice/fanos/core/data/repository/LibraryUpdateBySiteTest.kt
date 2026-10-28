package io.github.nimbice.fanos.core.data.repository

import io.github.nimbice.fanos.source.api.ChallengeRequiredException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals

class LibraryUpdateBySiteTest {

    /** Checks that take a moment, so overlapping ones show; novels are named "<site><n>". */
    private class Checks(private val answer: (String) -> Result<String> = { Result.success(it) }) {
        val asked = ArrayList<String>()
        val settled = ArrayList<String>()
        private val busy = HashMap<String, Int>()
        var mostAtOnce = 0
        var mostAtOnceFromOneSite = 0

        suspend fun check(novel: String): Result<String> {
            asked += novel
            val site = siteOf(novel)
            busy[site] = (busy[site] ?: 0) + 1
            mostAtOnce = maxOf(mostAtOnce, busy.values.sum())
            mostAtOnceFromOneSite = maxOf(mostAtOnceFromOneSite, busy.getValue(site))
            try {
                delay(100)
                return answer(novel)
            } finally {
                busy[site] = busy.getValue(site) - 1
            }
        }

        suspend fun run(novels: List<String>, parallelSites: Int = 4) =
            bySite(
                novels,
                siteOf = ::siteOf,
                parallelSites = parallelSites,
                stopsSite = { it is ChallengeRequiredException },
                check = ::check,
                settle = { novel, _ -> settled += novel },
            )

        private fun siteOf(novel: String) = novel.take(1)
    }

    @Test
    fun `a site's novels go one at a time, in order, beside the other sites`() =
        runTest {
            val checks = Checks()
            val answers = checks.run(listOf("a1", "a2", "b1", "a3", "c1", "b2"))
            assertEquals(listOf("a1", "a2", "b1", "a3", "c1", "b2"), answers.map { it.getOrThrow() })
            assertEquals(1, checks.mostAtOnceFromOneSite)
            assertEquals(3, checks.mostAtOnce)
            assertEquals(listOf("a1", "a2", "a3"), checks.asked.filter { it.startsWith("a") })
            // Three sites side by side: as long as the busiest one, not all six in a row.
            assertEquals(300, currentTime)
        }

    @Test
    fun `no more sites at once than allowed`() =
        runTest {
            val checks = Checks()
            checks.run(listOf("a1", "b1", "c1", "d1"), parallelSites = 2)
            assertEquals(2, checks.mostAtOnce)
        }

    @Test
    fun `a novel that fails does not stop its site`() =
        runTest {
            val checks = Checks { if (it == "a1") Result.failure(IOException("reset")) else Result.success(it) }
            val answers = checks.run(listOf("a1", "a2"))
            assertEquals(listOf(true, false), answers.map { it.isFailure })
            assertEquals(listOf("a1", "a2"), checks.asked)
        }

    @Test
    fun `a site wanting a browser check is not asked again, its other novels told why`() =
        runTest {
            val challenge = ChallengeRequiredException("https://a/1")
            val checks = Checks { if (it == "a1") Result.failure(challenge) else Result.success(it) }
            val answers = checks.run(listOf("a1", "b1", "a2", "a3"))
            assertEquals(listOf("a1", "b1"), checks.asked.sorted())
            assertEquals(listOf(challenge, null, challenge, challenge), answers.map { it.exceptionOrNull() })
            assertEquals(listOf("a1", "a2", "a3", "b1"), checks.settled.sorted())
        }
}
