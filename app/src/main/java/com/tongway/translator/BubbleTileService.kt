package com.tongway.translator

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Кнопка в шторке уведомлений: показать или убрать кружок одним касанием. */
class BubbleTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val service = TranslatorService.instance
        if (service == null) {
            // Служба выключена — открываем приложение, там написано, что делать.
            openApp()
            return
        }
        service.setBubbleVisible(!service.isBubbleShown)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val service = TranslatorService.instance
        tile.state = if (service?.isBubbleShown == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                service == null -> getString(R.string.tile_service_off)
                service.isBubbleShown -> getString(R.string.bubble_shown)
                else -> getString(R.string.bubble_hidden)
            }
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Попросить систему обновить вид кнопки (после включения или выключения кружка). */
        fun refresh(context: Context) {
            runCatching {
                TileService.requestListeningState(context, ComponentName(context, BubbleTileService::class.java))
            }
        }
    }
}
