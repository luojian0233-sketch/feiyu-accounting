package com.local.autobookkeeping.recognition.tile

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import com.local.autobookkeeping.R

sealed interface TileAddResult {
    data object Requested : TileAddResult
    data object AlreadyAdded : TileAddResult
    data object ManualInstructions : TileAddResult
    data object Failed : TileAddResult
}

internal fun interface TileAddRequester {
    fun request(callback: (Int) -> Unit)
}

class QuickSettingsTileLauncher internal constructor(
    private val sdkInt: Int,
    private val requester: TileAddRequester?,
) {
    constructor(context: Context) : this(
        sdkInt = Build.VERSION.SDK_INT,
        requester = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AndroidTileAddRequester(context.applicationContext)
        } else {
            null
        },
    )

    fun requestAdd(onResult: (TileAddResult) -> Unit = {}): TileAddResult {
        if (sdkInt < Build.VERSION_CODES.TIRAMISU) {
            return TileAddResult.ManualInstructions
        }
        val activeRequester = requester ?: return TileAddResult.Failed
        var immediate: TileAddResult = TileAddResult.Requested
        return try {
            activeRequester.request { status ->
                val mapped = status.toTileAddResult()
                immediate = mapped
                onResult(mapped)
            }
            immediate
        } catch (_: Exception) {
            TileAddResult.Failed
        }
    }

    private fun Int.toTileAddResult(): TileAddResult = when (this) {
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> TileAddResult.Requested
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> TileAddResult.AlreadyAdded
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> TileAddResult.Failed
        else -> TileAddResult.Failed
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AndroidTileAddRequester(
    private val context: Context,
) : TileAddRequester {
    override fun request(callback: (Int) -> Unit) {
        val manager = context.getSystemService(StatusBarManager::class.java)
            ?: throw IllegalStateException("Status bar service unavailable")
        manager.requestAddTileService(
            ComponentName(context, ScreenshotBookkeepingTileService::class.java),
            context.getString(R.string.screenshot_bookkeeping_tile_label),
            Icon.createWithResource(context, R.drawable.ic_screenshot_bookkeeping),
            context.mainExecutor,
            callback,
        )
    }
}
