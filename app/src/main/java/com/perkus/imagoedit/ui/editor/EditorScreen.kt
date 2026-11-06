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
import com.perkus.imagoedit.data.EnumOption
import com.perkus.imagoedit.data.Feedback
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.Project
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
import com.perkus.imagoedit.ui.common.rememberImagePicker
import com.perkus.imagoedit.ui.menu.CanvasSizeDialog
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
    val preview = remember { OpacityPreview() }

    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val tool = remember { EnumOption(settings, "tool", Tool.entries) }
    val options = remember { ToolOptions(settings) }
    var popup by remember { mutableStateOf<OpenPopup?>(null) }
    // The press that closes a popover only closes it: the canvas gets it for zoom, not for the selection. A new canvas size (crop, its Undo/Redo) — a new tool
    val select = remember(project.width, project.height) { SelectTool(options.selection, project.width, project.height, { popup == null && layerMenu == null }, vm::select, vm::wand) }
    val brush = remember { BrushTool(options, { popup == null && layerMenu == null }, vm::stroke) }

    val target = project.active // in history: layer edits set it in their own step

    val adding = pending != null

    // Keyed by pending: image appeared/disappeared (pick, paste, confirm, cancel) → state is recreated, so adding needs no separate move resets.
    // After Activity recreation the tool is rebuilt from pending
    var move by remember(pending) {
        mutableStateOf(pending?.let { MoveState(it.index, it.layer.id, project.width, project.height, it.start, it.layer.opacity) })
    }
    var crop by remember { mutableStateOf<CropState?>(null) }
    var text by remember { mutableStateOf<TextState?>(null) }
    var transform by remember { mutableStateOf<TransformState?>(null) }
    var fill by remember { mutableStateOf<FillTool?>(null) }
    val pickImage = rememberImagePicker(vm::addImage)
    val scope = rememberCoroutineScope()
    val placeholder = stringResource(R.string.text)

    val layersScroll = rememberLazyListState(initialFirstVisibleItemIndex = target)

    // Follows the active layer however it changes (tap, new layer, Undo/Redo). Runs after recomposition, once a new layer is in the list
    LaunchedEffect(target) { layersScroll.reveal(target) }

    val added = remember(pending) { pending?.bitmap?.asImageBitmap() }

    // Visible layers bottom to top with their masks and patches, empty ones too: one may be taking a stroke. The added image at its future place, a cut's source with its rest mask (or gone),
    // the text being added at its future place. CanvasView finds the moved one by Layer.id and draws it with the tool's transform, the text by the tool itself
    val drawn = remember(layers, project.layers, added, pending, text) {
        val stack = layers.zip(project.layers).toMutableList()
        if (pending != null && added != null) {
            pending.cut?.rest?.first?.let { rest -> stack[pending.index - 1] = stack[pending.index - 1].let { (b, l) -> b.takeIf { rest.file != null } to rest.copy(visible = l.visible) } }
            stack.add(pending.index, added to pending.layer)
        }
        text?.let { stack.add(it.layer, null to Layer(id = it.id)) }
        stack.mapNotNull { (b, l) -> if (l.visible) Drawn(l.paintable(project.width, project.height), b, vm.mask(l), vm.patch(l)) else null }
    }

    val levels = rememberLevels(layers + listOfNotNull(added))
    val picture = rememberCanvasPicture(project.width, project.height, drawn, levels, preview, brush)

    val targetLayer = project.layers.getOrNull(target)
    val targetBitmap = layers.getOrNull(target)
    val targetMask = targetLayer?.let(vm::mask)
    val targetPatch = targetLayer?.let(vm::patch)
    SideEffect { brush.aim(if (tool.value == Tool.Selection) Layer(w = project.width, h = project.height, id = 0) else targetLayer?.paintable(project.width, project.height), project.selection, tool.value) }

    // The selected layer's visible part (its pixels, mask and patch) is precomputed off the main thread: usually ready by the time Move or "Select pixels" is tapped
    val box by produceState<ContentBox?>(null, targetBitmap?.asAndroidBitmap(), targetMask, targetPatch) {
        value = null
        val l = targetLayer?.takeIf { it.hasImage } ?: return@produceState
        value = withContext(Dispatchers.Default) { contentBox(targetBitmap?.asAndroidBitmap(), l.fileW, l.fileH, targetMask, targetPatch) }
    }

    // When adding, move resets itself via the remember key change
    fun cancelMove() { if (adding) vm.cancelPending() else move = null }

    /** The tool on the whole target layer once its visible part is scanned. Reads box at the call: it may come after a selection cut was found unneeded */
    fun moveLayer() {
        val l = project.layers[target]
        if (!l.hasImage) return
        val b = box ?: return
        move = MoveState(target, l.id, project.width, project.height, ContentTransform.initial(b.oriented(l.orient), l.x, l.y, l.w, l.h), l.opacity)
    }

    /** Over the active layer, once the fonts are read: their folder may have changed since the last time. The state is the current one, not this composition's */
    fun openText() {
        scope.launch {
            val fonts = withContext(Dispatchers.IO) { loadFonts(context) }
            val p = vm.editing ?: return@launch
            if (text == null && move == null && crop == null && transform == null) text = TextState(p.active + 1, newLayerId(), p.width, p.height, options.text, fonts, placeholder) { popup != null }
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

    /** On the active layer, shown if hidden (as Move): what the tool paints is seen over it */
    fun openFill() {
        val l = targetLayer ?: return
        if (!l.visible) vm.toggleVisible(target)
        fill = FillTool(l, options.fill, project.width, project.height, project.selection)
    }

    fun requestExit() { if (vm.dirty || vm.baking > 0) dialog = EditorDialog.Exit else vm.closeEditor(false) } // a stroke still baking is a change too

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
        SelectAction.Pixels -> box?.empty == false // the layer has pixels, hidden or not; no image or until the scan is done — off
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

    fun tools3Action(a: Tools3Action) {
        when (a) {
            Tools3Action.Frame -> { popup = null; crop = CropState(project.width, project.height, project.selection?.region?.bounds) }
            Tools3Action.SizeCanvas -> { popup = null; dialog = EditorDialog.SizeCanvas }
            Tools3Action.Rotation -> { popup = null; dialog = EditorDialog.RotateCanvas }
            Tools3Action.Text -> { popup = null; openText() }
            Tools3Action.FillStroke -> { popup = null; openFill() }
            else -> Unit
        }
    }

    // Exit is ignored during import or baking: the operation would write its result into an already closed project
    BackHandler { if (!busy) requestExit() }
    BackHandler(move != null) { if (!busy) cancelMove() } // in the tool Back = Cancel (registered later → takes priority)
    BackHandler(crop != null) { if (!busy) crop = null }
    BackHandler(text != null) { if (!busy) closeText() }
    BackHandler(transform != null) { if (!busy) closeTransform() }
    BackHandler(fill != null) { if (fill?.applying == false) fill = null }
    BackHandler(layerMenu != null) { layerMenu = null }
    BackHandler(popup != null) { popup = null }

    Box(Modifier.fillMaxSize()) {
        // The canvas is ALWAYS fullscreen with panels on top: entering the tool doesn't resize it → zoom and position are kept
        val frame: FrameState<*>? = move ?: crop ?: text ?: transform
        val hidden = frame != null || fill != null // a tool with its own panel: the canvas takes only zoom and that tool's gestures
        val selecting = tool.value == Tool.Selection && !hidden
        CanvasView(picture, frame, select.takeIf { selecting },
            brush.takeIf { (tool.value == Tool.Drawing || tool.value == Tool.Eraser || tool.value == Tool.Stamp) && !hidden || selecting && options.selection.style.value == SelectStyle.Brush },
            project.selection, targetLayer?.id ?: 0L, fill, Modifier.fillMaxSize())
        // An unfinished polygon disappears with its style or tool
        if (selecting && options.selection.style.value == SelectStyle.Polygon) DisposableEffect(Unit) { onDispose(select::dropPolygon) }
        transform?.let { s -> LaunchedEffect(s) { s.preview() } }
        val m = move
        // With the tool open, panels stay in composition and slide off-screen: the layer list isn't rebuilt. clipToBounds hides them in the screen side frame
        val slide = animateFloatAsState(if (hidden) 1f else 0f, PANEL_ANIM, label = "panels")
        Column(Modifier.fillMaxSize().clipToBounds()) {
            Bar(Modifier.panel(slide, hidden, dy = -1)) {
                IconBtn(AppIcons.Back) { requestExit() }
                IconBtn(AppIcons.AddImage, onClick = pickImage)
                ActionMenuBtn(ToolPopup.Edit, AppIcons.CutSquare, EditAction.entries, popup?.kind, ::canEdit, ::edit) { popup = it }
                ActionMenuBtn(ToolPopup.Selection, AppIcons.DottedLine, SelectAction.entries, popup?.kind, ::canSelect, ::selectAction) { popup = it }
                Spacer(Modifier.weight(1f))
                ActionMenuBtn(ToolPopup.Tools3, AppIcons.Menu, Tools3Action.entries, popup?.kind, enabled = { true }, onAction = ::tools3Action) { popup = it }
                IconBtn(AppIcons.Move) {
                    if (targetLayer?.hasImage == true && (project.selection != null || box != null)) {
                        if (!project.layers[target].visible) vm.toggleVisible(target)
                        // With a selection only its part moves: cut out right away, the tool opens on it (see vm.moveSelection)
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
            // The tool stays open (under the indicator) until the ViewModel replaces the layer. An unchanged cut is undone, a pasted image is still added
            if (m.changed || (pending != null && pending.cut == null)) {
                vm.applyTransform(m.layer, m.t, m.opacity) { move = null }
            } else cancelMove()
        }
        // Likewise open under the indicator until the ViewModel crops the canvas
        crop?.let { c ->
            FramePanel(c, Modifier.align(Alignment.BottomCenter), onCancel = { crop = null }) {
                if (c.changed) vm.cropCanvas(c.rect) { crop = null } else crop = null
            }
        }
        // Likewise open under the indicator until the ViewModel adds the layer; a blank text adds nothing
        text?.let { s ->
            TextOverlay(s, picture, popup?.kind, { popup = it }, ::closeText) {
                if (s.empty) closeText() else vm.addText(s.layer, s.content::layout, s.color.toArgb(), s.t, s.opacity, ::closeText)
            }
        }
        // Likewise open under the indicator until the ViewModel replaces the selection; nothing changed — no step
        transform?.let { s ->
            TransformOverlay(s, ::closeTransform) {
                options.transform.edge = s.edge
                if (s.changed) s.edge.let { e -> vm.transformSelection(e.grow, e.blur, e.smooth, s.src, s.t, ::closeTransform) } else closeTransform()
            }
        }
        // Open under the indicator until the ViewModel bakes it in as a brush stroke; nothing to paint — closes
        fill?.let { s ->
            FillOverlay(s, picture, popup?.kind, { popup = it }, { fill = null }) {
                val st = s.stroke()
                if (st == null) fill = null else { s.applying = true; vm.stroke(st) { fill = null } }
            }
        }
        // Popovers' press-outside layer: permanent, not inside a popover (see dismissOnPress); no-op while none is open
        Box(Modifier.fillMaxSize().dismissOnPress { layerMenu = null; popup = null })
        layerMenu?.let { anchor ->
            LayerMenu(anchor, project.layers[target].opacity, project.layers[target].id, project.layers.size > 1 || project.layers[target].hasImage, preview, { vm.setOpacity(target, it) },
                { vm.deleteLayer(target) }, { layerMenu = null; dialog = EditorDialog.Merge }) { layerMenu = null }
        }
        ToolPopover(popup) { popup = null }

        if (busy || fill?.applying == true) BusyOverlay()
    }

    dialog?.let { d ->
        val close = { dialog = null }
        when (d) {
            EditorDialog.Exit -> ExitDialog(vm.saved, close, vm::closeEditor)
            EditorDialog.AddLayer -> AddLayerDialog(vm.canCopy(target) && vm.selection != null, busy, close, { vm.layerFromSelection(target) }) { vm.addLayer(target, it) }
            EditorDialog.Merge -> MergeDialog(project.layers, target, busy, close, vm::mergeLayers)
            // Open under the indicator until the ViewModel turns the canvas or resizes it
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
    // A negative offset moves the layer from the edge (bottom with reverseLayout) to the panel center
    if (!visible) scrollToItem(i, (cell - info.viewportSize.height) / 2)
}

/** The offset is read in the draw phase → animation without recomposition.
pointerInput makes the panel a touch target: among overlapping siblings only the top one gets the touch → the panel's empty background doesn't pass touches to the canvas.
While the panel is hidden ([hidden]) or moving, touches are consumed in the Initial pass, before buttons and the layer list: they don't fire */
private fun Modifier.panel(progress: State<Float>, hidden: Boolean, dx: Int = 0, dy: Int = 0) = graphicsLayer {
    translationX = dx * size.width * progress.value
    translationY = dy * size.height * progress.value
}.pointerInput(hidden) {
    awaitPointerEventScope {
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (hidden || progress.value != 0f) e.changes.forEach { it.consume() } // hidden: on the tool-opening frame the animation hasn't moved progress yet
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

/** A choice is active only with at least two layers to merge. All and visible close the dialog once merged; with the previous one it stays: the merged layer may go on merging down */
@Composable
private fun MergeDialog(layers: List<Layer>, target: Int, busy: Boolean, onDismiss: () -> Unit, onMerge: (indices: List<Int>, onDone: () -> Unit) -> Unit) = AppDialog(stringResource(R.string.merging_layers), onDismiss, width = null, busy = busy) {
    val w = Modifier.fillMaxWidth()
    listOf(
        Triple(R.string.merge_all_layers, layers.indices.toList(), onDismiss),
        Triple(R.string.merge_visible_layers, layers.indices.filter { layers[it].visible }, onDismiss),
        Triple(R.string.merge_with_previous, listOf(target - 1, target).filter { it >= 0 }, {})
    ).forEach { (label, indices, onDone) -> DialogButton(stringResource(label), BtnGray, w, indices.size >= 2) { onMerge(indices, onDone) } }
}

/** Each choice: an angle clockwise after a horizontal flip or not, as in AppViewModel.turnCanvas (a vertical flip = horizontal + 180°) */
@Composable
private fun RotateCanvasDialog(busy: Boolean, onDismiss: () -> Unit, onTurn: (angle: Int, flip: Boolean) -> Unit) = AppDialog(stringResource(R.string.rotate_canvas), onDismiss, width = null, busy = busy) {
    val w = Modifier.fillMaxWidth()
    listOf(
        Triple(R.string.rotate_right_90, 90, false), Triple(R.string.rotate_left_90, 270, false), Triple(R.string.rotate_180, 180, false),
        Triple(R.string.flip_horizontal, 0, true), Triple(R.string.flip_vertical, 180, true)
    ).forEach { (label, angle, flip) -> DialogButton(stringResource(label), BtnGray, w) { onTurn(angle, flip) } }
}
