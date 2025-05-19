/*
**Copyright (C) 2017  xfalcon
**
**This program is free software: you can redistribute it and/or modify
**it under the terms of the GNU General Public License as published by
**the Free Software Foundation, either version 3 of the License, or
**(at your option) any later version.
**
**This program is distributed in the hope that it will be useful,
**but WITHOUT ANY WARRANTY; without even the implied warranty of
**MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**GNU General Public License for more details.
**
**You should have received a copy of the GNU General Public License
**along with this program.  If not, see <http://www.gnu.org/licenses/>.
**
*/
package com.github.xfalcon.vhosts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.github.xfalcon.vhosts.vservice.VhostsService

/**
 * Implementation of App Widget functionality.
 */
class QuickStartWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // There may be multiple widgets active, so update all of them
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val views = RemoteViews(context.packageName, R.layout.quick_start_widget)

        if (action == ACTION_QUICK_START_BUTTON) {
            if (VhostsService.isRunning) {
                VhostsService.stopVService(context)
                views.setImageViewResource(R.id.imageButton, R.drawable.quick_start_off)
            } else {
                VhostsService.startVService(context, 1)
                views.setImageViewResource(R.id.imageButton, R.drawable.quick_start_on)
            }
        }
        val appWidgetManager = AppWidgetManager.getInstance(context)
        appWidgetManager.updateAppWidget(
            ComponentName(context, QuickStartWidget::class.java),
            views
        )
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_QUICK_START_BUTTON: String = "com.github.xfalcon.ACTION_QUICK_START_BUTTON"

        fun updateAppWidget(
            context: Context, appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.quick_start_widget)
            val intent = Intent().setAction(ACTION_QUICK_START_BUTTON)
            val pendingIntent =
                PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            views.setImageViewResource(R.id.imageButton, R.drawable.quick_start_off)
            views.setOnClickPendingIntent(R.id.imageButton, pendingIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

