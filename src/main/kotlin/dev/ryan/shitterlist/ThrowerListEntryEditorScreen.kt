package dev.ryan.throwerlist

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text

class ThrowerListEntryEditorScreen(
    private val parent: ThrowerListListScreen,
    private val targetEntry: ThrowerListListScreen.GuiEntry?,
) : Screen(Text.literal(if (targetEntry == null) "Add Skylist Entry" else "Edit Skylist Entry")) {
    private var usernameField: TextFieldWidget? = null
    private var tagsButton: ButtonWidget? = null
    private var reasonField: TextFieldWidget? = null
    private var autoRemoveField: TextFieldWidget? = null
    private var ignoreButton: ButtonWidget? = null
    private var saveButton: ButtonWidget? = null
    private var cancelButton: ButtonWidget? = null
    private var statusMessage: Text? = null
    private var statusColor = 0xFFFF7777.toInt()
    private var leftMouseDown = false
    private var selectedTags = linkedSetOf<String>()
    private var draftUsername: String? = null
    private var draftReason: String? = null
    private var draftAutoRemove: String? = null
    private var draftIgnored = false
    private var initializedDraft = false
    private val layout: UiLayoutManager.EntryEditorLayout
        get() = UiLayoutManager.entryEditor()

    private data class VerticalLayout(
        val titleY: Int,
        val primaryLabelsY: Int,
        val primaryFieldsY: Int,
        val reasonLabelY: Int,
        val reasonFieldY: Int,
        val actionsY: Int,
    )

    override fun init() {
        super.init()
        val panel = panelRect()
        if (!initializedDraft) {
            draftUsername = targetEntry?.username.orEmpty()
            draftReason = targetEntry?.reason.orEmpty()
            draftAutoRemove = targetEntry?.autoRemoveAfter.orEmpty()
            draftIgnored = targetEntry?.ignored ?: false
            selectedTags.clear()
            selectedTags.addAll(targetEntry?.tags.orEmpty())
            initializedDraft = true
        }

        val vertical = verticalLayout(panel)
        val tagsWidth = panel.width() - layout.contentInset * 2 - layout.primaryFieldWidth - layout.columnGap
        usernameField = TextFieldWidget(
            textRenderer,
            panel.left + layout.contentInset,
            vertical.primaryFieldsY,
            layout.primaryFieldWidth,
            layout.fieldHeight,
            Text.literal("Username"),
        ).also {
            it.setMaxLength(16)
            it.setDrawsBackground(false)
            ThemeRenderer.applyTextFieldInset(it)
            targetEntry?.let { entry ->
                it.text = draftUsername.orEmpty()
                it.setEditable(false)
            } ?: run {
                it.text = draftUsername.orEmpty()
            }
            addDrawableChild(it)
            setInitialFocus(it)
        }

        tagsButton = ThemedButtonWidget.builder(tagButtonLabel()) {
            captureDraft()
            client?.setScreen(ThrowerListTagPickerScreen(this, selectedTags))
        }.dimensions(
            panel.left + layout.contentInset + layout.primaryFieldWidth + layout.columnGap,
            vertical.primaryFieldsY,
            tagsWidth,
            layout.fieldHeight,
        ).build().also { addDrawableChild(it) }

        reasonField = TextFieldWidget(
            textRenderer,
            panel.left + layout.contentInset,
            vertical.reasonFieldY,
            layout.primaryFieldWidth,
            layout.fieldHeight,
            Text.literal("Reason"),
        ).also {
            it.setMaxLength(120)
            it.setDrawsBackground(false)
            ThemeRenderer.applyTextFieldInset(it)
            it.text = draftReason.orEmpty()
            addDrawableChild(it)
        }

        autoRemoveField = TextFieldWidget(
            textRenderer,
            panel.left + layout.contentInset + layout.primaryFieldWidth + layout.columnGap,
            vertical.reasonFieldY,
            tagsWidth,
            layout.fieldHeight,
            Text.literal("Auto Remove"),
        ).also {
            it.setMaxLength(64)
            it.setDrawsBackground(false)
            ThemeRenderer.applyTextFieldInset(it)
            it.text = draftAutoRemove.orEmpty()
            addDrawableChild(it)
        }

        ignoreButton = ThemedButtonWidget.builder(ignoreButtonLabel()) {
            draftIgnored = !draftIgnored
            ignoreButton?.message = ignoreButtonLabel()
        }.dimensions(
            panel.centerX() - layout.actions.width / 2,
            vertical.actionsY,
            layout.actions.width,
            layout.actions.height,
        ).build().also { addDrawableChild(it) }

        saveButton = ThemedButtonWidget.builder(Text.literal(if (targetEntry == null) "Add" else "Save")) {
            parent.submitEditor(
                this,
                targetEntry,
                usernameField?.text.orEmpty(),
                reasonField?.text.orEmpty(),
                selectedTags.toList(),
                draftIgnored,
                autoRemoveField?.text.orEmpty(),
            )
        }.dimensions(
            panel.left + layout.contentInset,
            vertical.actionsY,
            layout.actions.width,
            layout.actions.height,
        ).build().also { addDrawableChild(it) }

        cancelButton = ThemedButtonWidget.builder(Text.literal("Cancel")) {
            client?.setScreen(parent)
        }.dimensions(
            panel.right - layout.contentInset - layout.actions.width,
            vertical.actionsY,
            layout.actions.width,
            layout.actions.height,
        ).build().also { addDrawableChild(it) }
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        context.fill(0, 0, width, height, theme.overlayBackground)
        val panel = panelRect()
        val vertical = verticalLayout(panel)
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, layout.titleBarHeight, theme)

        drawCentered(context, title.string, width / 2, vertical.titleY, 0xFFFFFFFF.toInt())
        drawText(context, "Username", panel.left + layout.contentInset, vertical.primaryLabelsY, theme.sectionHeader)
        drawText(
            context,
            "Tags (optional)",
            panel.left + layout.contentInset + layout.primaryFieldWidth + layout.columnGap,
            vertical.primaryLabelsY,
            theme.sectionHeader,
        )
        drawText(context, "Reason", panel.left + layout.contentInset, vertical.reasonLabelY, theme.sectionHeader)
        drawText(
            context,
            "Auto Remove (optional)",
            panel.left + layout.contentInset + layout.primaryFieldWidth + layout.columnGap,
            vertical.reasonLabelY,
            theme.sectionHeader,
        )
        ThemeRenderer.drawTextField(context, usernameField, theme)
        ThemeRenderer.drawTextField(context, reasonField, theme)
        ThemeRenderer.drawTextField(context, autoRemoveField, theme)
        drawAutoRemovePlaceholder(context, theme)

        super.render(context, mouseX, mouseY, deltaTicks)
        ThemeRenderer.drawButton(context, tagsButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, ignoreButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, saveButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, cancelButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)

        statusMessage?.let {
            drawCentered(context, it.string, width / 2, panel.bottom + layout.statusBottomGap, statusColor)
        }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            leftMouseDown = true
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: Click): Boolean {
        if (click.button() == 0) {
            leftMouseDown = false
        }
        return super.mouseReleased(click)
    }

    fun setBusy(message: String) {
        statusMessage = Text.literal(message)
        statusColor = ThemeManager.current().hoverAccent
        saveButton?.active = false
        ignoreButton?.active = false
        cancelButton?.active = false
        usernameField?.setEditable(false)
        reasonField?.setEditable(false)
        autoRemoveField?.setEditable(false)
    }

    fun showError(message: String) {
        statusMessage = Text.literal(message)
        statusColor = 0xFFFF7777.toInt()
        saveButton?.active = true
        ignoreButton?.active = true
        cancelButton?.active = true
        usernameField?.setEditable(targetEntry == null)
        reasonField?.setEditable(true)
        autoRemoveField?.setEditable(true)
    }

    fun onTagsSelected(tags: Collection<String>) {
        selectedTags.clear()
        selectedTags.addAll(ThrowerTags.normalize(tags))
        captureDraft()
        tagsButton?.message = tagButtonLabel()
    }

    private fun captureDraft() {
        draftUsername = usernameField?.text.orEmpty()
        draftReason = reasonField?.text.orEmpty()
        draftAutoRemove = autoRemoveField?.text.orEmpty()
    }

    private fun tagButtonLabel(): Text {
        val suffix = when {
            selectedTags.isEmpty() -> "None"
            selectedTags.size == 1 -> selectedTags.first().replaceFirstChar { it.uppercase() }
            else -> "${selectedTags.first().replaceFirstChar { it.uppercase() }} +${selectedTags.size - 1}"
        }
        return Text.literal("Tags: $suffix")
    }

    private fun ignoreButtonLabel(): Text =
        Text.literal("Ignore: ${if (draftIgnored) "✓" else "✗"}")

    private fun drawAutoRemovePlaceholder(context: DrawContext, theme: ThemePalette) {
        val field = autoRemoveField ?: return
        if (field.text.isBlank() && !field.isFocused) {
            drawText(
                context,
                "EG. 1mt 2d / 1 month 2 days",
                ThemeRenderer.textFieldPlaceholderX(field),
                ThemeRenderer.textFieldPlaceholderY(field),
                theme.mutedText,
            )
        }
    }

    private fun panelRect(): PanelRect {
        val halfWidth = layout.panelWidth / 2
        val halfHeight = layout.panelHeight / 2
        return PanelRect(width / 2 - halfWidth, height / 2 - halfHeight, width / 2 + halfWidth, height / 2 + halfHeight)
    }

    private fun verticalLayout(panel: PanelRect): VerticalLayout {
        val spacing = layout.spacing
        val titleY = panel.top + layout.titleTopOffset
        val primaryLabelsY = titleY + spacing.titleToLabels
        val primaryFieldsY = primaryLabelsY + spacing.labelToField
        val reasonLabelY = primaryFieldsY + layout.fieldHeight + spacing.sectionGap
        val reasonFieldY = reasonLabelY + spacing.labelToField
        val actionsY = reasonFieldY + layout.fieldHeight + spacing.fieldToActions
        return VerticalLayout(
            titleY = titleY,
            primaryLabelsY = primaryLabelsY,
            primaryFieldsY = primaryFieldsY,
            reasonLabelY = reasonLabelY,
            reasonFieldY = reasonFieldY,
            actionsY = actionsY,
        )
    }

    private fun drawCentered(context: DrawContext, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredTextWithShadow(context, textRenderer, text, centerX, y, color)
    }

    private fun drawText(context: DrawContext, text: String, x: Int, y: Int, color: Int) {
        ThemeRenderer.drawTextWithShadow(context, textRenderer, text, x, y, color)
    }

    private data class PanelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun width(): Int = right - left
        fun centerX(): Int = (left + right) / 2
    }
}
