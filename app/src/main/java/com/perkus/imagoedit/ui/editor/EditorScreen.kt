package com.perkus.imagoedit.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.perkus.imagoedit.AppViewModel
import com.perkus.imagoedit.R
import com.perkus.imagoedit.data.AppSettings
import com.perkus.imagoedit.data.ContentBox
import com.perkus.imagoedit.data.ContentTransform
import com.perkus.imagoedit.data.Correction
import com.perkus.imagoedit.data.Effect
import com.perkus.imagoedit.data.EnumOption
import com.perkus.imagoedit.data.Feedback
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.LayerOverlay
import com.perkus.imagoedit.data.Project
import com.perkus.imagoedit.data.Stroke
import com.perkus.imagoedit.data.allSelected
import com.perkus.imagoedit.data.contentBox
import com.perkus.imagoedit.data.fileH
import com.perkus.imagoedit.data.fileW
import com.perkus.imagoedit.data.hasImage
import com.perkus.imagoedit.data.loadFonts
import com.perkus.imagoedit.data.newLayerId
import com.perkus.imagoedit.data.oriented
import com.perkus.imagoedit.data.paintable
import com.perkus.imagoedit.ui.common.AppDialog
import com.perkus.imagoedit.ui.common.AppIcons
import com.perkus.imagoedit.ui.common.Bar
import com.perkus.imagoedit.ui.common.BusyOverlay
import com.perkus.imagoedit.ui.common.DialogButton
import com.perkus.imagoedit.ui.common.IconBtn
import com.perkus.imagoedit.ui.common.SideBar
import com.perkus.imagoedit.ui.common.dismissOnPress
import com.perkus.imagoedit.ui.menu.CanvasSizeDialog
import com.perkus.imagoedit.ui.menu.rememberImagePicker
import com.perkus.imagoedit.ui.theme.BtnGray
import com.perkus.imagoedit.ui.theme.BtnGreen
import com.perkus.imagoedit.ui.theme.BtnRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditorScreen(vm: AppViewModel, project: Project) {
    val layers = vm.layers
    val busy = vm.busy
    val pending = vm.pending

    var dialog by remember { mutableStateOf<EditorDialog?>(null) }

    var layerMenu by remember { mutableStateOf<Rect?>(null) }
    val preview = remember { LayerPreview() }

    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val tool = remember { EnumOption(settings, "tool", Tool.entries) }
    val options = remember { ToolOptions(settings) }
    var popup by remember { mutableStateOf<OpenPopup?>(null) }
    // A press that closes a popover only closes it. A new canvas size — a new tool
    val select = remember(project.width, project.height) { SelectTool(options.selection, project.width, project.height, { popup == null && layerMenu == null }, vm::select, vm::wand) }
    val brush = remember { BrushTool(options, { popup == null && layerMenu == null }, vm::stroke) }

    val target = project.active // in history

    val adding = pending != null

    // Keyed by pending: recreated when an image appears or goes; rebuilt from pending after Activity recreation
    var move by remember(pending) {
        mutableStateOf(pending?.let { MoveState(it.index, it.layer.id, project.width, project.height, it.start, it.layer.opacity) })
    }
    var crop by remember { mutableStateOf<CropState?>(null) }
    var text by remember { mutableStateOf<TextState?>(null) }
    var transform by remember { mutableStateOf<TransformState?>(null) }
    var fill by remember { mutableStateOf<FillTool?>(null) }
    var transition by remember { mutableStateOf<TransitionTool?>(null) }
    var applying by remember { mutableStateOf(false) } // Fill/Stroke or Transition being baked
    var correction by remember { mutableStateOf<CorrectionTool?>(null) } // the carousel's, while open
    var effects by remember { mutableStateOf(false) } // the effects' carousel or an effect open
    var effect by remember { mutableStateOf<LayerOverlay?>(null) } // its panel: an EffectTool or Replace color's CorrectionTool
    val pickImage = rememberImagePicker(vm::addImage)
    val scope = rememberCoroutineScope()
    val placeholder = stringResource(R.string.text)

    val layersScroll = rememberLazyListState(initialFirstVisibleItemIndex = target)

    // Follows the active layer however it changes, after recomposition
    LaunchedEffect(target) { layersScroll.reveal(target) }

    val added = remember(pending) { pending?.bitmap?.asImageBitmap() }

    // Visible layers with masks and patches (empty ones too: one may take a stroke), the added image and text at their future places, a cut's source with its rest
    val drawn = remember(layers, project.layers, added, pending, text) {
        val stack = layers.zip(project.layers).toMutableList()
        if (pending != null && added != null) {
            pending.cut?.rest?.first?.let { rest -> stack[pending.index - 1] = stack[pending.index - 1].let { (b, l) -> b.takeIf { rest.file != null } to rest.copy(visible = l.visible) } }
            stack.add(pending.index, added to pending.layer)
        }
        text?.let { stack.add(it.layer, null to Layer(id = it.id)) }
        stack.mapNotNull { (b, l) ->
            if (l.visible) Drawn(l.paintable(project.width, project.height), b, vm.mask(l), vm.patch(l)) else null
        }
    }

    val levels = rememberLevels(layers + listOfNotNull(added))
    val picture = rememberCanvasPicture(project.width, project.height, drawn, levels, preview, brush)

    val targetLayer = project.layers.getOrNull(target)
    val targetBitmap = layers.getOrNull(target)
    val targetMask = targetLayer?.let(vm::mask)
    val targetPatch = targetLayer?.let(vm::patch)
    SideEffect {
        brush.aim(if (tool.value == Tool.Selection) Layer(w = project.width, h = project.height, id = 0) else targetLayer?.paintable(project.width, project.height), project.selection, tool.value,
            targetBitmap?.asAndroidBitmap(), targetMask, targetPatch, project.width, project.height)
    }

    // Precomputed off the main thread: usually ready when Move or "Select pixels" is tapped
    val box by produceState<ContentBox?>(null, targetBitmap?.asAndroidBitmap(), targetMask, targetPatch) {
        value = null
        val l = targetLayer?.takeIf { it.hasImage } ?: return@produceState
        value = withContext(Dispatchers.Default) { contentBox(targetBitmap?.asAndroidBitmap(), l.fileW, l.fileH, targetMask, targetPatch) }
    }

    fun cancelMove() { if (adding) vm.cancelPending() else move = null }

    /** Reads box at the call: it may come after a selection cut was found unneeded */
    fun moveLayer() {
        val l = project.layers[target]
        if (!l.hasImage) return
        val b = box ?: return
        move = MoveState(target, l.id, project.width, project.height, ContentTransform.initial(b.oriented(l.orient), l.x, l.y, l.w, l.h), l.opacity)
    }

    /** Once the fonts are read (their folder may have changed); uses the current state */
    fun openText() {
        scope.launch {
            val fonts = withContext(Dispatchers.IO) { loadFonts(context) }
            val p = vm.editing ?: return@launch
            if (text == null && move == null && crop == null && transform == null && fill == null && transition == null) text = TextState(p.active + 1, newLayerId(), p.width, p.height, options.text, fonts, placeholder) { popup != null }
        }
    }

    fun closeText() {
        text?.save()
        text = null
    }

    fun closeTransform() {
        transform?.save()
        transform = null
    }

    /** Shown if hidden, as Move. Without a selection the stroke follows the layer's pixels: scanned first; an empty layer has none (0×0: not scannable) */
    fun openFill() {
        val l = targetLayer ?: return
        if (!l.visible) vm.toggleVisible(target)
        val sel = project.selection
        if (sel != null || !l.hasImage) fill = FillTool(l, options.fill, project.width, project.height, sel)
        else vm.layerPixels(target) { fill = FillTool(l, options.fill, project.width, project.height, null, it) }
    }

    /** Shown if hidden, as Move */
    fun openTransition() {
        val l = targetLayer ?: return
        if (!l.visible) vm.toggleVisible(target)
        transition = TransitionTool(l, options.transition, project.width, project.height, project.selection) { popup != null }
    }

    /** The carousel, its corrections at none */
    fun openCorrections() {
        val l = targetLayer ?: return
        correction = CorrectionTool(l, project.selection, targetBitmap?.asAndroidBitmap(), targetMask, targetPatch, options.correction)
    }

    /** A tile's panel; shows the layer if hidden, as Move */
    fun openCorrection(c: Correction) {
        if (targetLayer?.visible == false) vm.toggleVisible(target)
        correction?.open = c
    }

    /** Baked as a brush stroke, the tool open under the indicator until then; nothing to paint — closes */
    fun bake(st: Stroke?, close: () -> Unit) {
        if (st == null) return close()
        applying = true
        vm.stroke(st) { applying = false; close() }
    }

    /** The corrections baked as one brush stroke: the editor's panels are back */
    fun closeCorrections(s: CorrectionTool) = bake(s.stroke()) { correction = null }

    /** A tap outside the panel closes it, outside the carousel — closeCorrections */
    fun tapCorrections(s: CorrectionTool) { if (s.open == null) closeCorrections(s) else s.open = null }

    /** Back closes the panel, in the carousel drops the corrections */
    fun backCorrections(s: CorrectionTool) { if (s.open == null) correction = null else s.open = null }

    /** A tile's panel for the layer as it is now (an effect baked before shows); shows the layer if hidden, as Move */
    fun openEffect(e: Effect) {
        val l = targetLayer ?: return
        if (!l.visible) vm.toggleVisible(target)
        val bmp = targetBitmap?.asAndroidBitmap()
        effect = if (e == Effect.ReplaceColor) CorrectionTool(l, project.selection, bmp, targetMask, targetPatch, options.correction, replaces = true)
        else EffectTool(e, l, options.effect, project.selection, bmp, targetMask, targetPatch, project.width, project.height)
    }

    fun requestExit() { if (vm.dirty || vm.baking > 0) dialog = EditorDialog.Exit else vm.closeEditor(false) } // a baking stroke is a change too

    fun canEdit(a: EditAction) = when (a) {
        EditAction.Cut, EditAction.Copy, EditAction.Clear -> vm.canCopy(target)
        EditAction.CopyMerged -> vm.canCopyMerged
        EditAction.Paste -> vm.canPaste
        EditAction.Extract -> vm.canCopy(target) && vm.selection != null
    }

    fun edit(a: EditAction) {
        if (a != EditAction.CopyMerged) popup = null
        when (a) {
            EditAction.Cut -> vm.cut(target)
            EditAction.Copy -> vm.copy(target)
            EditAction.CopyMerged -> vm.copyMerged { popup = null }
            EditAction.Paste -> vm.paste(target)
            EditAction.Clear -> vm.clear(target)
            EditAction.Extract -> vm.extract(target)
        }
    }

    fun canSelect(a: SelectAction) = when (a) {
        SelectAction.All -> true
        SelectAction.Deselect, SelectAction.Invert, SelectAction.Transform -> vm.selection != null
        SelectAction.Pixels -> box?.empty == false // off without an image or until scanned
    }

    fun selectAction(a: SelectAction) {
        popup = null
        when (a) {
            SelectAction.All -> vm.select(allSelected(project.width, project.height))
            SelectAction.Deselect -> vm.select(null)
            SelectAction.Invert -> vm.invertSelection()
            SelectAction.Pixels -> vm.selectPixels(target)
            SelectAction.Transform -> project.selection?.let { transform = TransformState(project.width, project.height, it, options.transform) }
        }
    }

    /** Transition — only on a layer with an image */
    fun canTools3(a: Tools3Action) = a != Tools3Action.Transition || vm.canCopy(target)

    fun tools3Action(a: Tools3Action) {
        when (a) {
            Tools3Action.Frame -> { popup = null; crop = CropState(project.width, project.height, project.selection?.region?.bounds) }
            Tools3Action.SizeCanvas -> { popup = null; dialog = EditorDialog.SizeCanvas }
            Tools3Action.Rotation -> { popup = null; dialog = EditorDialog.RotateCanvas }
            Tools3Action.Text -> { popup = null; openText() }
            Tools3Action.FillStroke -> { popup = null; openFill() }
            Tools3Action.Transition -> { popup = null; openTransition() }
        }
    }

    // Exit is ignored while busy: the result would land in a closed project
    BackHandler { if (!busy) requestExit() }
    BackHandler(move != null) { if (!busy) cancelMove() } // in the tool Back = Cancel (later → priority)
    BackHandler(crop != null) { if (!busy) crop = null }
    BackHandler(text != null) { if (!busy) closeText() }
    BackHandler(transform != null) { if (!busy) closeTransform() }
    BackHandler(fill != null) { if (!applying) fill = null }
    BackHandler(transition != null) { if (!applying) transition = null }
    BackHandler(correction != null) { if (!applying) correction?.let(::backCorrections) }
    BackHandler(effects) { if (!applying) { if (effect != null) effect = null else effects = false } } // the panel: Cancel
    // A blend mode picked in the menu becomes one history step; committed before closing: the canvas never shows the old one in between
    fun closeLayerMenu() {
        project.layers.getOrNull(target)?.let { l -> preview.picked(l)?.let { vm.setBlend(target, it) } }
        layerMenu = null
    }
    BackHandler(layerMenu != null) { closeLayerMenu() }
    BackHandler(popup != null) { popup = null }

    Box(Modifier.fillMaxSize()) {
        // The canvas is always fullscreen under the panels: zoom and position survive tools
        val frame: CanvasTool<*>? = move ?: crop ?: text ?: transform ?: transition
        val hidden = frame != null || fill != null || correction != null || effects // a tool with its own panel
        val selecting = tool.value == Tool.Selection && !hidden
        // A tap with the effects' carousel shown closes it; an effect's panel has its own Cancel. None while a popover is open: a press closing it only closes it
        val closeEffects = { effects = false }
        val tap = (correction?.let { s -> { tapCorrections(s) } } ?: closeEffects.takeIf { effects && effect == null }).takeIf { popup == null }
        CanvasView(picture, frame, select.takeIf { selecting },
            brush.takeIf { tool.value != Tool.Selection && !hidden || selecting && options.selection.style.value == SelectStyle.Brush },
            project.selection, targetLayer?.id ?: 0L, fill, correction ?: effect, tap, Modifier.fillMaxSize())
        // An unfinished polygon disappears with its style or tool
        if (selecting && options.selection.style.value == SelectStyle.Polygon) DisposableEffect(Unit) { onDispose(select::dropPolygon) }
        transform?.let { s -> LaunchedEffect(s) { s.preview() } }
        val m = move
        // Panels stay composed and slide away: the layer list isn't rebuilt
        val slide = animateFloatAsState(if (hidden) 1f else 0f, PANEL_ANIM, label = "panels")
        Column(Modifier.fillMaxSize().clipToBounds()) {
            Bar(Modifier.panel(slide, hidden, dy = -1)) {
                IconBtn(AppIcons.Back) { requestExit() }
                IconBtn(AppIcons.AddImage, onClick = pickImage)
                ActionMenuBtn(ToolPopup.Edit, AppIcons.CutSquare, EditAction.entries, popup?.kind, ::canEdit, ::edit) { popup = it }
                ActionMenuBtn(ToolPopup.Selection, AppIcons.DottedLine, SelectAction.entries, popup?.kind, ::canSelect, ::selectAction) { popup = it }
                Spacer(Modifier.weight(1f))
                IconBtn(AppIcons.SlidersHorizontal) { openCorrections() }
                IconBtn(AppIcons.SquareSparkles) { effects = true }
                ActionMenuBtn(ToolPopup.Tools3, AppIcons.Menu, Tools3Action.entries, popup?.kind, ::canTools3, ::tools3Action) { popup = it }
                IconBtn(AppIcons.Move) {
                    if (targetLayer?.hasImage == true && (project.selection != null || box != null)) {
                        if (!project.layers[target].visible) vm.toggleVisible(target)
                        // With a selection only its part moves: cut out first (vm.moveSelection)
                        if (project.selection != null) vm.moveSelection(target, ::moveLayer) else moveLayer()
                    }
                }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                SideBar(Modifier.panel(slide, hidden, dx = -1)) {
                    ToolsPanel(tool, options, picture, select, popup?.kind, { popup = it }, Modifier.weight(1f))
                    Bar(arrangement = Arrangement.Center) {
                        IconBtn(AppIcons.StepBack, vm.canUndo) { Feedback.tap(); vm.undo() }
                        IconBtn(AppIcons.StepForward, vm.canRedo) { Feedback.tap(); vm.redo() }
                    }
                }
                Spacer(Modifier.weight(1f))
                LayersPanel(
                    project, layers, vm::mask, vm::patch, levels, target, layersScroll, menuOpen = layerMenu != null,
                    onSelect = vm::setActive,
                    onToggleVisible = vm::toggleVisible,
                    onMove = vm::moveLayer,
                    onAdd = { dialog = EditorDialog.AddLayer },
                    onMenu = { layerMenu = it },
                    modifier = Modifier.panel(slide, hidden, dx = 1)
                )
            }
        }
        if (m != null) MoveOverlay(m, onCancel = { cancelMove() }) {
            // Open under the indicator until the ViewModel applies it; an unchanged cut is undone
            if (m.changed || (pending != null && pending.cut == null)) {
                vm.applyTransform(m.layer, m.t, m.opacity) { move = null }
            } else cancelMove()
        }
        // Likewise until the canvas is cropped
        crop?.let { c ->
            FramePanel(c, Modifier.align(Alignment.BottomCenter), onCancel = { crop = null }) {
                if (c.changed) vm.cropCanvas(c.rect) { crop = null } else crop = null
            }
        }
        // Likewise until the layer is added; a blank text adds nothing
        text?.let { s ->
            TextOverlay(s, picture, popup?.kind, { popup = it }, ::closeText) {
                if (s.empty) closeText() else vm.addText(s.layer, s.content::layout, s.color.toArgb(), s.t, s.opacity, ::closeText)
            }
        }
        // Likewise until the selection is replaced; nothing changed — no step
        transform?.let { s ->
            TransformOverlay(s, ::closeTransform) {
                options.transform.edge = s.edge
                if (s.changed) s.edge.let { e -> vm.transformSelection(e.grow, e.blur, e.smooth, s.src, s.t, ::closeTransform) } else closeTransform()
            }
        }
        // Likewise until baked as a brush stroke
        fill?.let { s -> FillOverlay(s, picture, popup?.kind, { popup = it }, { fill = null }) { bake(s.stroke()) { fill = null } } }
        transition?.let { s -> TransitionOverlay(s, { popup = it }, { transition = null }) { bake(s.stroke()) { transition = null } } }
        // A tile's panel takes the carousel's place, closed by a tap on the canvas: zoom stays available; a tap with the carousel shown bakes, open under the
        // indicator until then
        TileCarousel(correction.let { it != null && it.open == null }, Correction.entries, Modifier.align(Alignment.BottomCenter), ::openCorrection)
        correction?.let { CorrectionOverlay(it, picture, popup?.kind) { p -> popup = p } }
        // One effect at a time: ✓ bakes it (open under the indicator until then), ✓ and Cancel bring the carousel back
        TileCarousel(effects && effect == null, Effect.entries, Modifier.align(Alignment.BottomCenter), ::openEffect)
        when (val s = effect) {
            is EffectTool -> EffectOverlay(s, picture, popup?.kind, { popup = it }, { effect = null }) { bake(s.stroke()) { effect = null } }
            is CorrectionTool -> ReplaceOverlay(s, options.correction, picture, popup?.kind, { popup = it }, { effect = null }) { bake(s.stroke()) { effect = null } }
        }
        // Popovers' press-outside layer, permanent (see dismissOnPress)
        Box(Modifier.fillMaxSize().dismissOnPress { closeLayerMenu(); popup = null })
        layerMenu?.let { anchor ->
            LayerMenu(anchor, project.layers[target], project.layers.size > 1 || project.layers[target].hasImage, preview, { vm.setOpacity(target, it) },
                { vm.deleteLayer(target) }, { layerMenu = null; dialog = EditorDialog.Merge }, ::closeLayerMenu)
        }
        ToolPopover(popup) { popup = null }

        if (busy || applying) BusyOverlay()
    }

    dialog?.let { d ->
        val close = { dialog = null }
        when (d) {
            EditorDialog.Exit -> ExitDialog(vm.saved, close, vm::closeEditor)
            EditorDialog.AddLayer -> AddLayerDialog(vm.canCopy(target) && vm.selection != null, busy, close, { vm.layerFromSelection(target) }) { vm.addLayer(target, it) }
            EditorDialog.Merge -> MergeDialog(project.layers, target, busy, close, vm::mergeLayers)
            // Open under the indicator until the canvas is turned or resized
            EditorDialog.RotateCanvas -> RotateCanvasDialog(busy, close) { angle, flip -> vm.turnCanvas(angle, flip, close) }
            EditorDialog.SizeCanvas -> CanvasSizeDialog(R.string.size_canvas, project.width, project.height, close, busy) { w, h -> vm.resizeCanvas(w, h, close) }
        }
    }
}

private enum class EditorDialog { Exit, AddLayer, Merge, RotateCanvas, SizeCanvas }

private suspend fun LazyListState.reveal(i: Int) {
    val info = layoutInfo
    val cell = info.visibleItemsInfo.firstOrNull()?.size ?: 0
    val visible = info.visibleItemsInfo.any { it.index == i && it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
    // A negative offset moves the layer from the edge to the panel center
    if (!visible) scrollToItem(i, (cell - info.viewportSize.height) / 2)
}

/** Offset read in the draw phase. pointerInput makes the panel a touch target (its background doesn't pass touches to the canvas); hidden or moving — touches are
consumed in the Initial pass, so nothing fires */
private fun Modifier.panel(progress: State<Float>, hidden: Boolean, dx: Int = 0, dy: Int = 0) = graphicsLayer {
    translationX = dx * size.width * progress.value
    translationY = dy * size.height * progress.value
}.pointerInput(hidden) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (hidden || progress.value != 0f) e.changes.forEach { it.consume() } // hidden: the first frame's progress is still 0
        }
    }
}

@Composable
private fun ExitDialog(saved: Boolean, onDismiss: () -> Unit, onClose: (save: Boolean, copy: Boolean) -> Unit) = AppDialog(stringResource(R.string.exit_save_title), onDismiss, width = null) {
    val w = Modifier.fillMaxWidth()
    DialogButton(stringResource(R.string.exit_save), BtnGreen, w) { onClose(true, false) }
    if (saved) DialogButton(stringResource(R.string.exit_save_copy), BtnGreen, w) { onClose(true, true) }
    DialogButton(stringResource(R.string.exit_discard), BtnRed, w) { onClose(false, false) }
}

@Composable
private fun AddLayerDialog(canFromSelection: Boolean, busy: Boolean, onDismiss: () -> Unit, onFromSelection: () -> Unit, onAdd: (duplicate: Boolean) -> Unit) =
    AppDialog(stringResource(R.string.add_layer), onDismiss, width = null, busy = busy) {
        val w = Modifier.fillMaxWidth()
        DialogButton(stringResource(R.string.empty_layer), BtnGray, w) { onAdd(false) }
        DialogButton(stringResource(R.string.duplicate_layer), BtnGray, w) { onAdd(true) }
        DialogButton(stringResource(R.string.layer_from_selection), BtnGray, w, canFromSelection, onFromSelection)
    }

/** Active only with ≥ 2 layers to merge. All and visible close the dialog; with the previous one it stays open */
@Composable
private fun MergeDialog(layers: List<Layer>, target: Int, busy: Boolean, onDismiss: () -> Unit, onMerge: (indices: List<Int>, onDone: () -> Unit) -> Unit) = AppDialog(stringResource(R.string.merging_layers), onDismiss, width = null, busy = busy) {
    val w = Modifier.fillMaxWidth()
    listOf(
        Triple(R.string.merge_all_layers, layers.indices.toList(), onDismiss),
        Triple(R.string.merge_visible_layers, layers.indices.filter { layers[it].visible }, onDismiss),
        Triple(R.string.merge_with_previous, listOf(target - 1, target).filter { it >= 0 }, {})
    ).forEach { (label, indices, onDone) -> DialogButton(stringResource(label), BtnGray, w, indices.size >= 2) { onMerge(indices, onDone) } }
}

/** Each choice: an angle clockwise after an optional horizontal flip (vertical = horizontal + 180°) */
@Composable
private fun RotateCanvasDialog(busy: Boolean, onDismiss: () -> Unit, onTurn: (angle: Int, flip: Boolean) -> Unit) = AppDialog(stringResource(R.string.rotate_canvas), onDismiss, width = null, busy = busy) {
    val w = Modifier.fillMaxWidth()
    listOf(
        Triple(R.string.rotate_right_90, 90, false), Triple(R.string.rotate_left_90, 270, false), Triple(R.string.rotate_180, 180, false),
        Triple(R.string.flip_horizontal, 0, true), Triple(R.string.flip_vertical, 180, true)
    ).forEach { (label, angle, flip) -> DialogButton(stringResource(label), BtnGray, w) { onTurn(angle, flip) } }
}
