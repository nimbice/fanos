package io.github.nimbice.fanos.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.nimbice.fanos.R
import io.github.nimbice.fanos.core.common.ApplicationScope
import io.github.nimbice.fanos.core.data.repository.RecentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app icon's shortcuts, shown on a long press: the novels read last, the last one first, each opening the chapter the
 * reader was on, as the continue-reading widget's rows do. Kept up to date when the app is left.
 */
@Singleton
class ReadingShortcuts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recent: RecentRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    fun refresh() {
        scope.launch {
            val novels = recent.continueReading(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceAtMost(MOST))
            val shortcuts =
                novels.mapIndexed { rank, novel ->
                    // A launcher crops a shortcut's icon to its own shape: the cover's middle, square.
                    val icon = coverBitmap(context, novel.coverUrl, ICON_PX, ICON_PX, fill = true)
                        ?.let { Bitmap.createScaledBitmap(it.centreSquare(), ICON_PX, ICON_PX, true) }
                        ?.let(IconCompat::createWithAdaptiveBitmap)
                        ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
                    ShortcutInfoCompat.Builder(context, "novel:${novel.novelId}")
                        .setShortLabel(novel.title)
                        .setLongLabel(novel.title)
                        .setIcon(icon)
                        .setIntent(readIntent(context, novel))
                        .setRank(rank)
                        .build()
                }
            runCatching { ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts) }
        }
    }

    private companion object {
        const val MOST = 3

        // An adaptive icon's full square, the launcher showing the middle two thirds of it.
        const val ICON_PX = 216
    }
}

/** The bitmap cut to a square from its middle. */
private fun Bitmap.centreSquare(): Bitmap {
    val side = minOf(width, height)
    return Bitmap.createBitmap(this, (width - side) / 2, (height - side) / 2, side, side)
}
