package io.github.nimbice.fanos.core.designsystem.component

import java.text.NumberFormat

/** "1 chapter", "12 chapters", "1,432 chapters". */
fun countOf(n: Int, noun: String): String = if (n == 1) "1 $noun" else "${number(n)} ${noun}s"

/** A count as the phone's language writes it: "1,432". */
fun number(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** A novel's chapters in the same words everywhere: "12 chapters · 3 unread", "12 chapters · all read". */
fun chapterSummary(total: Int, unread: Int): String =
    when {
        total == 0 -> "No chapters yet"
        unread == 0 -> "${countOf(total, "chapter")} · all read"
        else -> "${countOf(total, "chapter")} · ${number(unread)} unread"
    }
