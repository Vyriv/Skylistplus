package dev.ryan.throwerlist

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

object ReminderManager {
    private const val displayDurationMillis = 4_500L
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val reminderCounter = AtomicLong()

    @Volatile
    private var activeReminder: ActiveReminder? = null

    fun register() {
        HudRenderCallback.EVENT.register(HudRenderCallback { context, _ ->
            render(context)
        })
    }

    fun schedule(delayMillis: Long, reminderText: String) {
        val normalizedReminder = reminderText.trim()
        if (delayMillis <= 0L || normalizedReminder.isEmpty()) {
            return
        }

        val reminderId = reminderCounter.incrementAndGet()
        scheduler.schedule({
            ThrowerListMod.client.execute {
                activeReminder = ActiveReminder(
                    id = reminderId,
                    text = normalizedReminder,
                    shownAtMillis = System.currentTimeMillis(),
                )
                ThrowerListMod.client.player?.sendMessage(Text.literal("[SL] Reminder: $normalizedReminder"), false)
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
    }

    private fun render(context: DrawContext) {
        val client = ThrowerListMod.client
        val reminder = activeReminder ?: return
        val elapsed = System.currentTimeMillis() - reminder.shownAtMillis
        if (elapsed >= displayDurationMillis) {
            activeReminder = null
            return
        }

        val textRenderer = client.textRenderer ?: return
        val alpha = when {
            elapsed <= 3_000L -> 255
            else -> (((displayDurationMillis - elapsed).toDouble() / 1_500.0) * 255.0).roundToInt().coerceIn(0, 255)
        }
        val color = (alpha shl 24) or 0xFFFFFF
        val shadowColor = (alpha shl 24) or 0x101010
        val screenWidth = client.window.scaledWidth
        val screenHeight = client.window.scaledHeight
        val text = reminder.text
        val x = (screenWidth - textRenderer.getWidth(text)) / 2
        val y = (screenHeight * 0.22f).roundToInt()

        ThemeRenderer.drawText(context, textRenderer, text, x + 1, y + 1, shadowColor)
        ThemeRenderer.drawText(context, textRenderer, text, x, y, color)
    }

    private data class ActiveReminder(
        val id: Long,
        val text: String,
        val shownAtMillis: Long,
    )
}
