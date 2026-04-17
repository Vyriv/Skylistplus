package dev.ryan.throwerlist

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

class SillyBanScreen : Screen(Text.literal("Connection Lost")) {
    private var backButton: ButtonWidget? = null
    private val banId = generateBanId()

    private fun generateBanId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return "#" + (1..6).map { chars.random() }.joinToString("")
    }

    override fun init() {
        super.init()
        backButton = ButtonWidget.builder(Text.literal("Back to server list")) {
            client?.setScreen(MultiplayerScreen(TitleScreen()))
        }.dimensions(width / 2 - 100, height / 2 + 54, 200, 20).build()
        addDrawableChild(backButton)
    }

    override fun shouldCloseOnEsc(): Boolean = false

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        context.fill(0, 0, width, height, 0xFF100A10.toInt())
        super.render(context, mouseX, mouseY, deltaTicks)

        val centerX = width / 2
        val baseY = height / 2 - 72
        val lineHeight = textRenderer.fontHeight + 4

        drawCenteredLine(context, title.string, centerX, baseY, 0xFFFFFFFF.toInt())
        drawCenteredSegments(
            context,
            centerX,
            baseY + lineHeight * 2,
            listOf(
                TextSegment("You are temporarily banned for ", 0xFFFF5555.toInt()),
                TextSegment("29d 23h 59m 58s", 0xFFFFFFFF.toInt()),
                TextSegment(" from this server!", 0xFFFF5555.toInt()),
            ),
        )
        drawCenteredSegments(
            context,
            centerX,
            baseY + lineHeight * 4,
            listOf(
                TextSegment("Reason: ", 0xFFAAAAAA.toInt()),
                TextSegment("Cheating through the use of unfair game advantages.", 0xFFFFFFFF.toInt()),
            ),
        )
        drawCenteredSegments(
            context,
            centerX,
            baseY + lineHeight * 5,
            listOf(
                TextSegment("Find out more: ", 0xFFAAAAAA.toInt()),
                TextSegment("https://www.hypixel.net/appeal", 0xFF55FFFF.toInt()),
            ),
        )
        drawCenteredSegments(
            context,
            centerX,
            baseY + lineHeight * 7,
            listOf(
                TextSegment("Ban ID: ", 0xFFAAAAAA.toInt()),
                TextSegment(banId, 0xFFFFFFFF.toInt()),
            ),
        )
        drawCenteredLine(
            context,
            "Sharing your Ban ID may affect the processing of your appeal!",
            centerX,
            baseY + lineHeight * 8,
            0xFFAAAAAA.toInt(),
        )
    }

    private fun drawCenteredLine(context: DrawContext, line: String, centerX: Int, y: Int, color: Int) {
        val x = centerX - textRenderer.getWidth(line) / 2
        context.drawTextWithShadow(textRenderer, line, x, y, color)
    }

    private fun drawCenteredSegments(context: DrawContext, centerX: Int, y: Int, segments: List<TextSegment>) {
        val totalWidth = segments.sumOf { textRenderer.getWidth(it.text) }
        var x = centerX - totalWidth / 2

        for (segment in segments) {
            context.drawTextWithShadow(textRenderer, segment.text, x, y, segment.color)
            x += textRenderer.getWidth(segment.text)
        }
    }

    private data class TextSegment(val text: String, val color: Int)

    companion object {
        fun show(client: MinecraftClient?) {
            if (client == null || client.currentScreen is SillyBanScreen) {
                return
            }

            if (client.world != null || client.networkHandler != null) {
                client.world?.disconnect(Text.literal("Disconnected"))
                client.disconnect(SillyBanScreen(), false)
                return
            }

            client.setScreen(SillyBanScreen())
        }
    }
}
