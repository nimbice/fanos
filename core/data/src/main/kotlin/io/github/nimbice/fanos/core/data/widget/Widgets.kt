package io.github.nimbice.fanos.core.data.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's home-screen widgets, whichever are placed: asks them to redraw with what changed, such as what was read or
 * chapters a check found. It goes by the app's own widget providers, so this module needs no widget of its own.
 */
@Singleton
class Widgets @Inject constructor(@ApplicationContext private val context: Context) {

    fun refresh() {
        val manager = AppWidgetManager.getInstance(context) ?: return
        manager.getInstalledProvidersForPackage(context.packageName, null).forEach { info ->
            val ids = manager.getAppWidgetIds(info.provider)
            if (ids.isNotEmpty()) {
                context.sendBroadcast(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setComponent(info.provider).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                )
            }
        }
    }
}
