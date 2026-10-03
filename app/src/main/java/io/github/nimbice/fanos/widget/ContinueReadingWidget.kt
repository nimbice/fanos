package io.github.nimbice.fanos.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.nimbice.fanos.core.data.notification.NewChapterNotifier
import io.github.nimbice.fanos.core.data.repository.RecentRepository
import io.github.nimbice.fanos.core.designsystem.component.number
import io.github.nimbice.fanos.core.model.ContinueReading
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import io.github.nimbice.fanos.core.data.R as DataR

/**
 * The home screen's continue-reading widget: the novels read last, the last one first, each with the chapter the reader
 * was on and how many are unread. A novel opens that chapter; the title opens the app. It shows as many as its size fits
 * and takes the wallpaper's colours where Android has them. It is redrawn when the app is left and when a check finds
 * chapters (see core/data's Widgets).
 */
class ContinueReadingWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val recent = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).recent()
        val novels = recent.continueReading(MOST)
        val covers = coroutineScope { novels.map { novel -> async { novel.novelId to coverBitmap(context, novel.coverUrl, COVER_PX_WIDTH, COVER_PX_HEIGHT) } }.awaitAll().toMap() }
        provideContent {
            GlanceTheme { Content(context, novels, covers) }
        }
    }

    private companion object {
        const val MOST = 8
        const val COVER_PX_WIDTH = 104
        const val COVER_PX_HEIGHT = 152
    }
}

class ContinueReadingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueReadingWidget()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun recent(): RecentRepository
}

private val HEADER = 26.dp
private val ROW = 46.dp
private val PADDING = 12.dp

@Composable
private fun Content(context: Context, novels: List<ContinueReading>, covers: Map<Long, Bitmap?>) {
    val colors = GlanceTheme.colors
    val height = LocalSize.current.height
    // A widget a row high shows the last novel alone, without the title.
    val titled = height >= HEADER + ROW + PADDING * 2
    val fits = ((height - PADDING * 2 - if (titled) HEADER else 0.dp) / ROW).toInt().coerceAtLeast(1)
    Column(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(24.dp)
            .background(colors.widgetBackground)
            .padding(horizontal = 14.dp, vertical = PADDING),
    ) {
        if (titled || novels.isEmpty()) {
            Row(
                GlanceModifier.fillMaxWidth().height(HEADER).clickable(actionStartActivity(launchIntent(context))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    ImageProvider(DataR.drawable.ic_notification_lamp),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.primary),
                    modifier = GlanceModifier.size(18.dp),
                )
                Spacer(GlanceModifier.width(8.dp))
                Text("Continue reading", style = TextStyle(color = colors.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            }
        }
        if (novels.isEmpty()) {
            Text(
                "Novels you read show here, the last one first.",
                style = TextStyle(color = colors.onSurfaceVariant, fontSize = 13.sp),
                modifier = GlanceModifier.padding(top = 4.dp).clickable(actionStartActivity(launchIntent(context))),
            )
        } else {
            novels.take(fits).forEach { novel -> NovelRow(context, novel, covers[novel.novelId]) }
        }
    }
}

@Composable
private fun NovelRow(context: Context, novel: ContinueReading, cover: Bitmap?) {
    val colors = GlanceTheme.colors
    Row(
        GlanceModifier.fillMaxWidth().height(ROW).clickable(actionStartActivity(readIntent(context, novel))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = GlanceModifier.size(26.dp, 38.dp).cornerRadius(4.dp)
        if (cover != null) {
            Image(ImageProvider(cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = shape)
        } else {
            Box(shape.background(colors.secondaryContainer)) {}
        }
        Spacer(GlanceModifier.width(12.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(novel.title, maxLines = 1, style = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            val line = listOfNotNull(novel.chapterTitle, novel.unread.takeIf { it > 0 }?.let { "${number(it)} unread" }).joinToString(" · ")
            if (line.isNotEmpty()) Text(line, maxLines = 1, style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp))
        }
    }
}

/**
 * A cover as a small bitmap, from the app's image loader and its cache; null without one. Pictures shown outside the app
 * are sent across to the home screen: no hardware bitmaps, and no bigger than [width] by [height], or, to [fill] them,
 * no smaller.
 */
internal suspend fun coverBitmap(context: Context, url: String?, width: Int, height: Int, fill: Boolean = false): Bitmap? {
    if (url == null) return null
    val request = ImageRequest.Builder(context).data(url).size(width, height).scale(if (fill) Scale.FILL else Scale.FIT).allowHardware(false).build()
    return (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
}

/** Opens the app as its icon does; one already open takes it in onNewIntent, as with the notifications. */
private fun launchIntent(context: Context): Intent =
    requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Opens the chapter the reader was on in [novel], or the novel without one. Its own address keeps each novel's apart. */
internal fun readIntent(context: Context, novel: ContinueReading): Intent =
    launchIntent(context)
        .setData(Uri.parse("fanos://continue/${novel.novelId}"))
        .putExtra(NewChapterNotifier.EXTRA_NOVEL_ID, novel.novelId)
        .apply { novel.chapterId?.let { putExtra(NewChapterNotifier.EXTRA_CHAPTER_ID, it) } }
