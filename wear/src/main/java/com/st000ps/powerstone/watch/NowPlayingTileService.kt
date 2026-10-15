package com.st000ps.powerstone.watch

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.st000ps.powerstone.wear.WearProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Swipe-to tile: what the phone is playing, with ⏮ ⏯ ⏭ and ♥ that act on the phone. */
class NowPlayingTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                val link = watchApp.link
                val clicked = requestParams.currentState.lastClickableId
                if (clicked in setOf(WearProtocol.PLAY_PAUSE, WearProtocol.NEXT, WearProtocol.PREVIOUS, WearProtocol.TOGGLE_FAV)) {
                    link.send(clicked)
                    delay(600) // let the phone answer with the new state
                }
                completer.set(tile(link.refresh() ?: link.now.value))
            }
            "now-tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { it.set(ResourceBuilders.Resources.Builder().setVersion("1").build()); "now-res" }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, maxLines: Int = 1) =
        Text.Builder().setText(s).setMaxLines(maxLines).setFontStyle(
            FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build(),
        ).build()

    private fun button(id: String, glyph: String, size: Float, filled: Boolean = false, color: Int = WHITE): LayoutElementBuilders.LayoutElement {
        val click = ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(ActionBuilders.LoadAction.Builder().build()).build()
        val bg = ModifiersBuilders.Background.Builder()
            .setColor(argb(if (filled) WHITE else 0x00000000))
            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(size / 2)).build()).build()
        val border = ModifiersBuilders.Border.Builder().setWidth(dp(1f)).setColor(argb(if (filled) WHITE else EDGE)).build()
        return Box.Builder().setWidth(dp(size)).setHeight(dp(size))
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(click).setBackground(bg).setBorder(border).build())
            .addContent(text(glyph, size * 0.42f, if (filled) INK else color, bold = true))
            .build()
    }

    private fun tile(now: PhoneNow?): TileBuilders.Tile {
        val open = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(MainActivity::class.java.name).build(),
            ).build(),
        ).build()
        val col = Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        col.addContent(text("88.8FM", 11f, DIM, bold = true))
        col.addContent(Spacer.Builder().setHeight(dp(4f)).build())
        if (now == null || !now.hasTrack) {
            col.addContent(text("Nothing playing on the phone", 13f, DIM, maxLines = 2))
        } else {
            col.addContent(
                Box.Builder().setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build())
                    .addContent(
                        Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                            .addContent(text(now.title, 15f, WHITE, bold = true, maxLines = 2))
                            .addContent(text(listOfNotNull(now.artist.ifEmpty { null }, now.bpm.takeIf { it > 0 }?.let { "$it BPM" }).joinToString(" · "), 11f, DIM))
                            .build(),
                    ).build(),
            )
        }
        col.addContent(Spacer.Builder().setHeight(dp(8f)).build())
        col.addContent(
            Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                .addContent(button(WearProtocol.PREVIOUS, "⏮", 36f))
                .addContent(Spacer.Builder().setWidth(dp(8f)).build())
                .addContent(button(WearProtocol.PLAY_PAUSE, if (now?.playing == true) "❚❚" else "▶", 48f, filled = true))
                .addContent(Spacer.Builder().setWidth(dp(8f)).build())
                .addContent(button(WearProtocol.NEXT, "⏭", 36f))
                .build(),
        )
        if (now?.canFav == true) {
            col.addContent(Spacer.Builder().setHeight(dp(6f)).build())
            col.addContent(button(WearProtocol.TOGGLE_FAV, if (now.fav) "♥" else "♡", 30f, color = if (now.fav) ROSE else WHITE))
        }
        val root = Box.Builder().setWidth(expand()).setHeight(expand()).addContent(col.build()).build()
        return TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
    }

    companion object {
        private const val WHITE = 0xFFF2F2F2.toInt()
        private const val DIM = 0xFF9A9A9A.toInt()
        private const val EDGE = 0xFF2A2A2A.toInt()
        private const val INK = 0xFF0A0A0A.toInt()
        private const val ROSE = 0xFFC04A5E.toInt()
    }
}
