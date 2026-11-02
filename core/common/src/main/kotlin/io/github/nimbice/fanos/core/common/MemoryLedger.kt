package io.github.nimbice.fanos.core.common

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * What the app is holding in memory and what it's doing, as its parts say. Memory isn't marked with what it belongs to,
 * so the parts that can hold or use a lot (chapters cut into pages, a library update, a page in the hidden browser)
 * tell this what they hold and what they're doing, and the memory guard reads it out when memory runs high.
 */
object MemoryLedger {
    private class Holder(val name: String, val describe: () -> String, val shed: (() -> Unit)?)

    private class Work(val what: String, val since: Long)

    private val holders = CopyOnWriteArrayList<Holder>()
    private val doing = ConcurrentHashMap<Long, Work>()
    private val ids = AtomicLong()

    /**
     * [name] holds what [describe] says ("3 chapters, 412 pages"), until the entry is closed. [shed] lets go of what
     * it can do without when memory runs short; it's called from the guard's own thread.
     */
    fun hold(name: String, shed: (() -> Unit)? = null, describe: () -> String): AutoCloseable {
        val holder = Holder(name, describe, shed)
        holders += holder
        return AutoCloseable { holders -= holder }
    }

    /** [what] is under way until the entry is closed. */
    fun start(what: String): AutoCloseable {
        val id = ids.incrementAndGet()
        doing[id] = Work(what, System.currentTimeMillis())
        return AutoCloseable { doing.remove(id) }
    }

    /** [block], said to be under way as [what] while it runs. */
    inline fun <T> during(what: String, block: () -> T): T = start(what).use { block() }

    /** Asks everything holding memory to let go of what it can; the names of those that could. */
    fun shed(): List<String> = holders.mapNotNull { holder -> holder.shed?.let { shed -> runCatching(shed); holder.name } }

    /** The heap's use, what's holding memory, and what's under way, a line each. */
    fun report(now: Long = System.currentTimeMillis()): String =
        buildString {
            val runtime = Runtime.getRuntime()
            append("Heap: ").append((runtime.totalMemory() - runtime.freeMemory()) shr 20).append(" MB used of ").append(runtime.maxMemory() shr 20).append(" MB\n")
            append("Holding:\n")
            if (holders.isEmpty()) append("- nothing said\n")
            for (holder in holders) append("- ").append(holder.name).append(": ").append(runCatching(holder.describe).getOrElse { "(couldn't tell: $it)" }).append('\n')
            append("Under way:\n")
            if (doing.isEmpty()) append("- nothing said\n")
            for (work in doing.values.sortedBy { it.since }) append("- ").append(work.what).append(" (").append((now - work.since) / 1000).append(" s)\n")
        }
}
