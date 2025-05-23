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

import android.annotation.TargetApi
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.github.xfalcon.vhosts.vservice.VhostsService

@TargetApi(Build.VERSION_CODES.N)
class QuickStartTileService : TileService() {
    override fun onClick() {
        val tile = qsTile
        if (tile == null) return
        val state = tile.state
        if (state == Tile.STATE_ACTIVE) {
            tile.state = Tile.STATE_INACTIVE
            VhostsService.stopVService(this.applicationContext)
        } else if (state == Tile.STATE_INACTIVE) {
            tile.state = Tile.STATE_ACTIVE
            VhostsService.startVService(this.applicationContext, 1)
        } else {
            tile.state = Tile.STATE_UNAVAILABLE
        }
        tile.updateTile()
    }

    override fun onStartListening() {
        val tile = qsTile
        if (tile == null) return
        if (VhostsService.isRunning) {
            tile.state = Tile.STATE_ACTIVE
        } else {
            tile.state = Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
