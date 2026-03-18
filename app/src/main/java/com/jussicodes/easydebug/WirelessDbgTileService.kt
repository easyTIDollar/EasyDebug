package com.jussicodes.easydebug

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WirelessDbgTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    // 每次下拉状态栏，或者磁贴进入视野时，会调用此方法更新状态
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    // 用户点击磁贴时触发
    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        // 点击时先让磁贴变成“正在处理”的状态 (如果是 Android 14+ 可能会闪烁一下)
        tile.state = Tile.STATE_UNAVAILABLE
        tile.updateTile()

        serviceScope.launch {
            // 1. 先查询当前状态
            val currentState = runSuCommandSync("settings get global adb_wifi_enabled")

            // 2. 决定要切换成什么状态 (0 或 1)
            val nextState = if (currentState == "1") "0" else "1"

            // 3. 执行切换命令
            val result = runSuCommandSync("settings put global adb_wifi_enabled $nextState")

            // 4. 更新 UI
            withContext(Dispatchers.Main) {
                if (result == "PERMISSION_DENIED") {
                    // 如果 Root 权限被拒，让磁贴变灰不可用
                    tile.state = Tile.STATE_UNAVAILABLE
                    tile.subtitle = "无 Root 权限"
                } else {
                    // 重新获取真实状态并更新
                    updateTileStateSync()
                }
                tile.updateTile()
            }
        }
    }

    // 更新磁贴状态的逻辑
    private fun updateTileState() {
        serviceScope.launch {
            updateTileStateSync()
        }
    }

    // 同步更新状态 (在协程中调用)
    private suspend fun updateTileStateSync() {
        val tile = qsTile ?: return
        val state = runSuCommandSync("settings get global adb_wifi_enabled")

        withContext(Dispatchers.Main) {
            when (state) {
                "1" -> {
                    tile.state = Tile.STATE_ACTIVE // 亮起 (已开启)
                    tile.subtitle = "已开启"
                }
                "0", "" -> {
                    tile.state = Tile.STATE_INACTIVE // 暗淡 (已关闭)
                    tile.subtitle = "已关闭"
                }
                else -> {
                    tile.state = Tile.STATE_UNAVAILABLE // 灰色 (不可用/无权限)
                    tile.subtitle = "无 Root"
                }
            }
            tile.updateTile()
        }
    }

    // 为了在 Service 中也能用，我们把 runSuCommand 稍微包装一下，避免和 MainActivity 的冲突
    // 或者你可以把 MainActivity 里的那个函数提取到一个单独的 Utils.kt 里公用。这里为了简单，我直接写在这里。
    private fun runSuCommandSync(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            // 读取标准输出
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val output = reader.readText().trim()
            process.waitFor()

            if (process.exitValue() != 0) return "PERMISSION_DENIED"
            output
        } catch (e: Exception) {
            e.printStackTrace()
            "ERROR"
        }
    }
}