package com.perkus.imagoedit

import android.app.ActivityManager
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Region
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.perkus.imagoedit.data.ContentTransform
import com.perkus.imagoedit.data.Cut
import com.perkus.imagoedit.data.ExportSettings
import com.perkus.imagoedit.data.Feedback
import com.perkus.imagoedit.data.History
import com.perkus.imagoedit.data.Layer
import com.perkus.imagoedit.data.LayerMask
import com.perkus.imagoedit.data.MASK
import com.perkus.imagoedit.data.MAX_OPACITY
import com.perkus.imagoedit.data.SAFE_PIXELS
import com.perkus.imagoedit.data.PATCH
import com.perkus.imagoedit.data.PendingImage
import com.perkus.imagoedit.data.Project
import com.perkus.imagoedit.data.ProjectStore
import com.perkus.imagoedit.data.Selection
import com.perkus.imagoedit.data.Stroke
import com.perkus.imagoedit.data.TextLayout
import com.perkus.imagoedit.data.Tiles
import com.perkus.imagoedit.data.bake
import com.perkus.imagoedit.data.bytesBeyond
import com.perkus.imagoedit.data.cleared
import com.perkus.imagoedit.data.exportImage
import com.perkus.imagoedit.data.feathered
import com.perkus.imagoedit.data.fileH
import com.perkus.imagoedit.data.fileToCanvas
import com.perkus.imagoedit.data.fileW
import com.perkus.imagoedit.data.files
import com.perkus.imagoedit.data.fontsDir
import com.perkus.imagoedit.data.grown
import com.perkus.imagoedit.data.hasImage
import com.perkus.imagoedit.data.importImage
import com.perkus.imagoedit.data.inverted
import com.perkus.imagoedit.data.laid
import com.perkus.imagoedit.data.moved
import com.perkus.imagoedit.data.movedIndex
import com.perkus.imagoedit.data.newLayerId
import com.perkus.imagoedit.data.paintable
import com.perkus.imagoedit.data.persisted
import com.perkus.imagoedit.data.region
import com.perkus.imagoedit.data.room
import com.perkus.imagoedit.data.selected
import com.perkus.imagoedit.data.toSelection
import com.perkus.imagoedit.data.transformed
import com.perkus.imagoedit.data.wandRegion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

private const val THUMB_CACHE_SIZE = 48 // каждая ≤ 384×384×4 Б
private const val PARKED_BYTES = SAFE_PIXELS * 4 // пиксели для Undo/Redo: один bitmap SAFE_PIXELS; что не влезло — с диска
private const val AUTOSAVE_DELAY_MS = 3000L // запись восстановления после паузы в правках
private const val TAG = "AppViewModel"

private var stagingSwept = false // file-level: a recreated ViewModel's predecessor may still be saving — a sweep would take its files
private fun <T> List<T>.moved(from: Int, to: Int) = toMutableList().apply { add(to, removeAt(from)) }
private fun <T> List<T>.putAt(i: Int, item: T) = toMutableList().apply { set(i, item) }
private fun <T> List<T>.inserted(i: Int, item: T) = toMutableList().apply { add(i, item) }
private fun <T> List<T>.removed(i: Int) = toMutableList().apply { removeAt(i) }
private fun <T> List<T>.merged(idx: List<Int>, item: T) = filterIndexed { i, _ -> i !in idx }.inserted(idx.first(), item)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProjectStore()
    private val writer = Executors.newSingleThreadExecutor() // single thread: writes in order, not cancelled with the ViewModel
    private val io = writer.asCoroutineDispatcher() // reads share it: never overtake an unfinished save

    override fun onCleared() {
        keepClip(null) // the next ViewModel of this process won't sweep staging
        writer.shutdown()
    }

    var hasAccess by mutableStateOf(store.hasAccess())
        private set

    var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    var editing by mutableStateOf<Project?>(null)
        private set

    // Layer pixels, bottom to top: layers[i] ↔ editing.layers[i]; null — no file
    var layers by mutableStateOf<List<ImageBitmap?>>(emptyList())
        private set

    /** Masks and patches any state references (current, history, a pending cut). Never evicted: history has no other copy */
    private val tiles = HashMap<String, Tiles>()

    var pending by mutableStateOf<PendingImage?>(null)
        private set

    /** Last Cut/Copy: its file is a staging copy no layer references → Paste works in any project */
    private var clip by mutableStateOf<Pair<Layer, Bitmap>?>(null)

    /** The previous process's unsaved session: the recovery dialog stays until answered */
    var recovered by mutableStateOf<Project?>(null)
        private set

    @Volatile private var unsaved: Pair<Project, List<Tiles>>? = null // for the recovery file, see autosave
    private var autosaving: Job? = null
    private val doomed = HashSet<String>() // discarded after the next recovery write (see release)

    private var reimport = {} // the user's yes to TooLarge

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null) // also written from the write queue
        private set

    /** Released strokes with their onDone, the baking one first */
    private val strokes = ArrayDeque<Pair<Stroke, () -> Unit>>()

    /** Strokes not yet in history: no other off-main work starts; Undo/Redo and closing wait (whenBaked) */
    val baking get() = strokes.size
    private val afterBake = ArrayList<() -> Unit>()

    /** Whole data classes compared: reverting everything = no changes; session-only state doesn't count */
    val dirty get() = editing?.persisted?.let { e -> projects.none { it == e } } ?: false

    val saved get() = editing?.let { e -> projects.any { it.id == e.id } } ?: false

    private val history = History(ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem)
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo

    /** Pixels of layers absent now but needed by nearby history steps, by Layer.file; byte-limited, the rest is read from disk. Not recycled */
    private val parked = object : LruCache<String, Bitmap>(PARKED_BYTES) { override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount }

    private val thumbs = LruCache<Pair<Long, Long>, ImageBitmap>(THUMB_CACHE_SIZE)
    private val Project.thumbKey get() = id to updated

    /** Sole entry into the write queue: an uncaught exception there would kill the process. Failures are shown */
    private fun write(op: ProjectStore.() -> Unit) = writer.execute { runCatching { store.op() }.onFailure { error = it; Log.e(TAG, "write failed", it) } }

    private fun commit(list: List<Project>, op: ProjectStore.() -> Unit) {
        projects = list
        write(op)
    }

    private fun uniqueName(base: String) = (sequenceOf(base) + generateSequence(1) { it + 1 }.map { "${base}_$it" })
        .first { n -> projects.none { it.name == n } }

    private fun <T> runBusy(dispatcher: CoroutineDispatcher, tag: String, work: () -> T, onOk: (T) -> Unit) {
        if (busy || baking > 0) return // one at a time
        busy = true
        viewModelScope.launch {
            withContext(dispatcher) { runCatching(work) }
                .onSuccess(onOk)
                .onFailure { error = it; Log.w(TAG, "$tag failed", it) }
            busy = false
        }
    }

    /** Once the queued strokes are in history: Undo must take the last one, a save must include it */
    private fun whenBaked(action: () -> Unit) {
        if (baking == 0) action() else afterBake += action
    }

    fun mask(l: Layer): LayerMask? = l.mask?.let { tiles[it] }

    fun patch(l: Layer): Tiles? = l.patch?.let { tiles[it] }

    private fun tilesOf(p: Project) = p.layers.flatMap { listOfNotNull(mask(it), patch(it)) }.distinct()

    /** The first call with access in a process also sweeps orphaned staging, sparing the previous session's unsaved state; creates the user's fonts folder */
    fun refreshAccess() {
        hasAccess = store.hasAccess()
        if (!hasAccess) return
        write { fontsDir.mkdirs() }
        if (!stagingSwept) {
            stagingSwept = true
            viewModelScope.launch {
                withContext(io) { runCatching { store.recover() } }
                    .onSuccess { recovered = it }
                    .onFailure { Log.w(TAG, "recover failed", it) }
            }
        }
        viewModelScope.launch {
            withContext(io) { runCatching { store.load() } }
                .onSuccess { projects = it }
                .onFailure { Log.w(TAG, "load failed", it) }
        }
    }

    fun rename(id: Long, name: String) {
        val old = projects.firstOrNull { it.id == id } ?: return
        val p = old.copy(name = name.trim().ifEmpty { old.name }) // updated untouched: list order kept
        commit(projects.map { if (it.id == id) p else it }) { save(p) }
    }

    fun delete(ids: Set<Long>) = commit(projects.filterNot { it.id in ids }) { remove(ids) }

    fun cachedThumb(p: Project): ImageBitmap? = thumbs[p.thumbKey]
    suspend fun thumb(p: Project): ImageBitmap? = cachedThumb(p) ?: withContext(io) { store.loadThumb(p.id)?.asImageBitmap() }?.also { thumbs.put(p.thumbKey, it) }

    fun export(ctx: Context, p: Project, s: ExportSettings, onDone: (File) -> Unit) {
        runBusy(Dispatchers.IO, "export", { store.exportImage(ctx.applicationContext, p, s) }) { f ->
            Feedback.done()
            onDone(f)
        }
    }

    fun dismissError() { error = null }

    /** The user's answer to TooLarge: [reduce] → the same import, reduced */
    fun resolveTooLarge(reduce: Boolean) {
        error = null
        if (reduce) reimport()
    }

    fun open(p: Project) = runBusy(io, "open", { store.loadLayers(p).let { it to store.loadTiles(p, it) } }) { (bitmaps, ts) ->
        ts.forEach { tiles[it.id] = it }
        show(p, bitmaps.map { it?.asImageBitmap() })
    }

    /** Enters the list only after "Exit and save" */
    fun create(width: Int, height: Int, img: Pair<Layer, Bitmap>? = null) {
        val p = Project(System.currentTimeMillis(), uniqueName(LocalDateTime.now().format(DateTimeFormatter.ofPattern("ddMMyyyyHHmmss"))), width, height, layers = listOf(img?.first ?: Layer()))
        show(p, listOf(img?.second?.asImageBitmap()))
    }

    /** A project opened before the dialog appeared is closed as in openExternal */
    fun resolveRecovery(restore: Boolean) = whenBaked {
        val r = recovered ?: return@whenBaked
        recovered = null
        if (restore) {
            if (editing != null) closeEditor(save = dirty)
            open(r)
        } else {
            // The recovery file stops referencing r's files before they're discarded
            autosave()
            flushAutosave()
            write { discardStaged(r.layers.files()) }
        }
    }

    /** On the write queue (io): its staging file can't collide with an earlier sweep or save. Over the limits → TooLarge */
    private fun <T> runImport(resolver: ContentResolver, uri: Uri, map: (Pair<Layer, Bitmap>) -> T, reduce: Boolean = false, onOk: (T) -> Unit) {
        reimport = { runImport(resolver, uri, map, reduce = true, onOk) }
        runBusy(io, "import", { map(store.importImage(resolver, uri, reduce)) }, onOk)
    }

    fun importImage(resolver: ContentResolver, uri: Uri) = runImport(resolver, uri, { it }) { create(it.first.w, it.first.h, it) }

    /** refreshAccess() after closing and before import: the first staging sweep must be queued earlier, or onResume would delete the new project's file */
    fun openExternal(resolver: ContentResolver, uri: Uri) = whenBaked {
        if (busy) return@whenBaked
        if (editing != null) closeEditor(save = dirty)
        refreshAccess()
        importImage(resolver, uri)
    }

    fun closeEditor(save: Boolean, asCopy: Boolean = false) = whenBaked {
        cancelPending()
        autosaving?.cancel()
        autosaving = null
        val imgs = layers
        editing?.let { old ->
            // History and released staging files go after save() moved the project's own; the recovery goes first and only after a successful save
            val session = (history.clear() + old).flatMapTo(HashSet()) { it.layers.files() }.apply { addAll(doomed) }
            doomed.clear()
            if (save) {
                val now = System.currentTimeMillis()
                val e = (if (asCopy) old.copy(id = now, name = uniqueName("${old.name}_copy")) else old).copy(updated = now).persisted
                val ts = tilesOf(e)
                commit(listOf(e) + projects.filterNot { it.id == e.id }) { save(e, imgs.map { it?.asAndroidBitmap() }, ts, old.id); saveRecovery(null); discardStaged(session) }
            } else write { saveRecovery(null); discardStaged(session) }
        }
        editing = null
        layers = emptyList()
        parked.evictAll()
        tiles.clear()
    }

    fun addImage(resolver: ContentResolver, uri: Uri) {
        val p = editing ?: return
        runImport(resolver, uri, { (l, bmp) ->
            // Integer offset: pixels land exactly on canvas px
            PendingImage(l.copy(x = (p.width - l.w) / 2, y = (p.height - l.h) / 2), bmp, p.layers.size)
        }) { pending = it }
    }

    /** A cut's rest mask goes too; its source is untouched */
    fun cancelPending() {
        val pend = pending ?: return
        pending = null
        release(listOfNotNull(pend.layer.file))
    }

    /** [t] only rearranging pixels on the canvas (ContentTransform.placed) keeps file, mask, patch: a light step; else baked (ProjectStore.bakeLayer).
    With an image [pending] it becomes a new layer at [i] (a cut leaves its source the rest). The selection moves with the pixels; a soft one becomes hard over its reach */
    fun applyTransform(i: Int, t: ContentTransform, opacity: Int, onDone: () -> Unit) {
        val p = editing ?: return
        val pend = pending
        val cur = pend?.layer ?: p.layers.getOrNull(i)?.takeIf { it.hasImage } ?: return
        val src = pend?.bitmap ?: layers.getOrNull(i)?.asAndroidBitmap()
        val moved = t.placed(cur, p.width, p.height)?.copy(opacity = opacity)
        val area = if (pend == null) split(i)?.first else pend.cut?.area
        if (moved != null && area == null) {
            if (pend != null) putLayer(i, moved, src?.asImageBitmap(), insert = true) else editLayer(i) { moved }
            pending = null; onDone(); return
        }
        val mask = mask(cur)
        val patch = patch(cur)
        // Off the main thread: a complex selection takes a while
        runBusy(Dispatchers.IO, "transform", {
            val r = moved?.let { Triple(it, src, emptyList<Tiles>()) } ?: store.bakeLayer(p, cur, src, mask, patch, t)
            // Baking leaves soft edges just past the carried outline: the selection takes them, so a next Move takes the whole layer
            r to area?.let { t.carry(it, cur) }?.apply {
                if (moved == null) op(store.visibleRegion(r.first, r.second, r.third.firstOrNull { it.id == r.first.mask }, r.third.firstOrNull { it.id == r.first.patch }), Region.Op.UNION)
            }?.toSelection()
        }) { (r, sel) ->
            r.third.forEach { tiles[it.id] = it }
            putLayer(i, r.first.copy(opacity = opacity), r.second?.asImageBitmap(), insert = pend != null, pend?.cut?.rest, if (area != null) sel else selection)
            pending = null
            if (moved == null) release(listOfNotNull(cur.file))
            onDone()
        }
    }

    /** A new active layer at [i]: [layout] (built on the worker: one thread's) in [color] rendered by [t]. All off the canvas → none. [onDone] — once in or unneeded */
    fun addText(i: Int, layout: () -> TextLayout, color: Int, t: ContentTransform, opacity: Int, onDone: () -> Unit) {
        val p = editing ?: return
        runBusy(Dispatchers.IO, "text", { layout().render(t, color, p.width, p.height)?.let { (bmp, r) -> store.writeLayer(bmp, r) } }) { r ->
            r?.let { (l, bmp) -> putLayer(i, l.copy(opacity = opacity), bmp.asImageBitmap(), insert = true) }
            onDone()
        }
    }

    /** The canvas becomes [w]×[h], a step: the layer [kept] gives keeps its file, others are baked by [t] (ProjectStore.bakeLayer). The selection goes by [sel].
    Old files stay referenced by history: no release */
    private fun reframe(w: Int, h: Int, tag: String, kept: (Layer) -> Layer?, t: (Layer) -> ContentTransform, sel: (Selection) -> Selection?, onDone: () -> Unit) {
        val p = editing ?: return
        val q = p.copy(width = w, height = h)
        val imgs = layers
        val ms = p.layers.map { mask(it) }
        val ps = p.layers.map { patch(it) }
        runBusy(Dispatchers.IO, tag, {
            p.layers.mapIndexed { i, l ->
                kept(l)?.let { Triple(it, imgs[i], emptyList<Tiles>()) }
                    ?: store.bakeLayer(q, l, imgs[i]?.asAndroidBitmap(), ms[i], ps[i], t(l)).let { (b, bmp, ts) -> Triple(b.copy(visible = l.visible, opacity = l.opacity, id = l.id), bmp?.asImageBitmap(), ts) }
            } to p.selection?.let(sel)
        }) { (out, s) ->
            out.forEach { o -> o.third.forEach { tiles[it.id] = it } }
            edit(q.copy(layers = out.map { it.first }, selection = s), out.map { it.second })
            onDone()
        }
    }

    /** The canvas becomes [r]: a layer within it only moves, one it cuts is baked into its part, one outside loses its image, one without a file is only shifted.
    The selection moves along, clipped */
    fun cropCanvas(r: Rect, onDone: () -> Unit) {
        fun Layer.shifted() = copy(x = x - r.left, y = y - r.top)
        reframe(r.width(), r.height(), "crop", { l -> l.shifted().let { s -> if (l.file == null) s else s.inPlace.placed(l, r.width(), r.height()) } }, { it.shifted().inPlace },
            { it.moved(-r.left, -r.top, Rect(0, 0, r.width(), r.height())) }, onDone)
    }

    /** Every layer scaled with the canvas, each axis by its factor; the selection dropped. Same size — no step */
    fun resizeCanvas(w: Int, h: Int, onDone: () -> Unit) {
        val p = editing ?: return
        if (w == p.width && h == p.height) return onDone()
        val kx = w / p.width.toFloat()
        val ky = h / p.height.toFloat()
        reframe(w, h, "resize", { l -> l.takeUnless { it.hasImage } }, { l -> ContentTransform((l.x + l.w / 2f) * kx, (l.y + l.h / 2f) * ky, l.w * kx, l.h * ky) }, { null }, onDone)
    }

    /** Turned by [angle]° clockwise after a horizontal [flip] (as Layer.orient), all layers and the selection, a step. No baking: layers only lie anew (Layer.laid) */
    fun turnCanvas(angle: Int, flip: Boolean, onDone: () -> Unit) {
        val p = editing ?: return
        val side = angle % 180 != 0
        val w = if (side) p.height else p.width
        val h = if (side) p.width else p.height
        val src = Rect(0, 0, p.width, p.height)
        val t = ContentTransform(w / 2f, h / 2f, p.width.toFloat(), p.height.toFloat(), angle.toFloat(), flipH = flip)
        val m = t.carrier(Layer(w = p.width, h = p.height)) // old canvas px → new
        runBusy(Dispatchers.Default, "turn", { p.selection?.transformed(t, src, w, h) }) { s ->
            edit(p.copy(width = w, height = h, layers = p.layers.map { l -> if (l.hasImage) l.laid(l.fileToCanvas().apply { postConcat(m) }) else l }, selection = s), layers)
            onDone()
        }
    }

    /** A light step if it changes */
    fun setActive(i: Int) {
        val p = editing ?: return
        if (i != p.active && i in p.layers.indices) edit(p.copy(active = i), layers)
    }

    fun toggleVisible(i: Int) = editLayer(i) { it.copy(visible = !it.visible) }

    fun setOpacity(i: Int, percent: Int) = editLayer(i) { it.copy(opacity = percent.coerceIn(0, MAX_OPACITY)) }

    /** The only layer is cleared instead. The active one at or above [i] goes one down */
    fun deleteLayer(i: Int) {
        val p = editing ?: return
        if (i !in p.layers.indices) return
        if (p.layers.size > 1) edit(p.copy(layers = p.layers.removed(i), active = if (p.active < i) p.active else (p.active - 1).coerceAtLeast(0)), layers.removed(i)) else clearLayer(i)
    }

    fun clearLayer(i: Int) {
        val p = editing ?: return
        val old = p.layers.getOrNull(i)?.takeIf { it.hasImage } ?: return
        edit(p.copy(layers = p.layers.putAt(i, Layer(visible = old.visible, opacity = old.opacity, id = old.id))), layers.putAt(i, null))
    }

    fun moveLayer(from: Int, to: Int) {
        val p = editing ?: return
        if (from == to || from !in p.layers.indices || to !in p.layers.indices) return
        edit(p.copy(layers = p.layers.moved(from, to), active = movedIndex(p.active, from, to)), layers.moved(from, to))
    }

    /** Shares file, mask and bitmap with the original (immutable) at no cost; its own Layer.id. Becomes active */
    fun addLayer(above: Int, duplicate: Boolean) {
        val p = editing ?: return
        val src = p.layers.getOrNull(above) ?: return
        val layer = if (duplicate) src.copy(visible = true, id = newLayerId()) else Layer()
        edit(p.copy(layers = p.layers.inserted(above + 1, layer), active = above + 1), layers.inserted(above + 1, if (duplicate) layers[above] else null))
    }

    /** [idx] ascending, ≥ 2; the result at idx.first() becomes active. Old files stay referenced by history: no release. [onDone] — once it's in */
    fun mergeLayers(idx: List<Int>, onDone: () -> Unit) {
        val p = editing ?: return
        val bitmaps = layers.map { it?.asAndroidBitmap() }
        val ms = p.layers.map { mask(it) }
        val ps = p.layers.map { patch(it) }
        runBusy(Dispatchers.IO, "merge", { store.mergeLayers(p, idx, bitmaps, ms, ps) }) { (layer, img) ->
            edit(p.copy(layers = p.layers.merged(idx, layer), active = idx.first()), layers.merged(idx, img?.asImageBitmap()))
            onDone()
        }
    }

    /** A layer with an image or strokes, hidden or not */
    fun canCopy(i: Int) = editing?.layers?.getOrNull(i)?.hasImage == true
    val canCopyMerged get() = editing?.layers?.any { it.visible && it.hasImage } == true
    val canPaste get() = clip != null

    val selection get() = editing?.selection

    /** A step if it changes */
    fun select(s: Selection?) {
        val p = editing ?: return
        if (s != p.selection) edit(p.copy(selection = s), layers)
    }

    /** Off the main thread */
    fun invertSelection() {
        val p = editing ?: return
        val s = p.selection ?: return
        runBusy(Dispatchers.IO, "invert", { s.inverted(Rect(0, 0, p.width, p.height)) }) { select(it) }
    }

    /** Grown by [grow], smoothed by [smooth], blurred by [blur], then placed by [t] as the frame its grown bounds [src] get; a step; nothing left — none.
    Off the main thread */
    fun transformSelection(grow: Int, blur: Int, smooth: Int, src: Rect, t: ContentTransform, onDone: () -> Unit) {
        val p = editing ?: return
        // A soft edge that smoothing or blur replaces isn't grown
        val s = p.selection?.let { if (blur > 0 || smooth > 0) Selection(it.region, it.outline, drawn = it.drawn) else it } ?: return
        runBusy(Dispatchers.Default, "transform selection", { s.grown(grow)?.feathered(blur, smooth)?.transformed(t, src, p.width, p.height) }) { select(it); onDone() }
    }

    /** [work] on layer [i] (pixels, mask, patch) off the main thread */
    private fun <T> withLayer(i: Int, tag: String, work: ProjectStore.(Layer, Bitmap?, LayerMask?, Tiles?) -> T, onOk: (T) -> Unit) {
        val p = editing ?: return
        val l = p.layers.getOrNull(i) ?: return
        val img = layers.getOrNull(i)?.asAndroidBitmap()
        val m = mask(l)
        val pt = patch(l)
        runBusy(Dispatchers.IO, tag, { store.work(l, img, m, pt) }, onOk)
    }

    /** Layer [i]'s pixels with any opacity as a selection, once its strokes are baked; off the main thread */
    fun layerPixels(i: Int, onOk: (Selection?) -> Unit) = whenBaked { withLayer(i, "select pixels", { l, img, m, pt -> visibleRegion(l, img, m, pt).toSelection() }, onOk) }

    fun selectPixels(i: Int) = layerPixels(i, ::select)

    /** Magic wand at canvas px [x], [y] on the active layer, joined by [combine]; off the main thread */
    fun wand(x: Int, y: Int, tolerance: Int, contiguous: Boolean, combine: Region.Op) {
        val p = editing ?: return
        val base = p.selection?.region
        withLayer(p.active, "wand", { l, img, m, pt ->
            val c = l.paintable(p.width, p.height)
            val r = wandRegion(img, c.fileW, c.fileH, c.fileToCanvas(), m, pt, p.width, p.height, x, y, tolerance, contiguous)
            Region(base ?: Region()).apply { op(r, combine) }.toSelection()
        }) { select(it) }
    }

    /** Layer [i]'s rect split by the selection: the part it reaches and the part it doesn't select fully; null — no selection or image */
    private fun split(i: Int): Pair<Region, Region>? {
        val p = editing ?: return null
        val s = p.selection ?: return null
        val l = p.layers.getOrNull(i)?.takeIf { it.hasImage } ?: return null
        return l.region().apply { op(s.reach, Region.Op.INTERSECT) } to l.region().apply { op(s.core, Region.Op.DIFFERENCE) }
    }

    /** Off the main thread, by the selection (null — whole layer): [keep] — the selected pixels as a new file (cropLayer), nothing → Layer() to null;
    [erase] — also the layer without them (cleared) */
    private fun crop(i: Int, keep: Boolean, erase: Boolean = false, onOk: (Pair<Pair<Layer, Bitmap?>?, Pair<Layer, List<Tiles>>?>) -> Unit) {
        val s = selection
        withLayer(i, "selection", { l, img, m, pt ->
            (if (keep) cropLayer(l, img, m, pt, s) else null) to s?.takeIf { erase }?.let { cleared(l, m, pt, it, stagedName(MASK), stagedName(PATCH)) }
        }, onOk)
    }

    /** Keeps the layer's visibility and opacity */
    private fun putPixels(i: Int, r: Pair<Layer, Bitmap?>) {
        val l = editing?.layers?.getOrNull(i) ?: return
        putLayer(i, r.first.copy(opacity = l.opacity), r.second?.asImageBitmap(), insert = false)
    }

    /** Its new mask or patch over the same pixels, or no image */
    private fun putRest(i: Int, rest: Pair<Layer, List<Tiles>>) {
        val l = editing?.layers?.getOrNull(i) ?: return
        rest.second.forEach { tiles[it.id] = it }
        putLayer(i, rest.first.copy(opacity = l.opacity), layers[i].takeIf { rest.first.file != null }, insert = false)
    }

    /** With a selection, or a mask or patch — the part off the main thread; else the image as it lies, without opacity. Not in history.
    The file is copied on the write queue: a paste reads it after the copy */
    fun copy(i: Int) {
        val p = editing ?: return
        val l = p.layers.getOrNull(i) ?: return
        val area = split(i)?.first ?: if (l.mask != null || l.patch != null) l.region() else null
        if (area != null) {
            if (!area.isEmpty) crop(i, keep = true) { (c, _) -> c?.let { (cl, b) -> b?.let { keepClip(cl to it) } } }
            return
        }
        val src = l.file ?: return
        val img = layers[i]?.asAndroidBitmap() ?: return
        val dst = store.stagedName(File(src).extension)
        write { copyLayer(p, src, dst) }
        keepClip(Layer(dst, x = l.x, y = l.y, w = l.w, h = l.h, orient = l.orient) to img)
    }

    fun cut(i: Int) {
        val (inside, _) = split(i) ?: return run { copy(i); clearLayer(i) }
        if (!inside.isEmpty) crop(i, keep = true, erase = true) { (c, rest) -> c?.let { (cl, b) -> b?.let { keepClip(cl to it) } }; rest?.let { putRest(i, it) } }
    }

    /** Only the selected part goes; the layer stays */
    fun clear(i: Int) {
        val (inside, _) = split(i) ?: return clearLayer(i)
        if (!inside.isEmpty) crop(i, keep = false, erase = true) { (_, rest) -> rest?.let { putRest(i, it) } }
    }

    /** Move on a selection: its part of layer [i] is cut out right away and becomes a pending image right above (the rest gets a new mask or patch).
    Nothing outside the selection → no cut, [onWhole] moves the layer itself. Strokes with no tiles inside → nothing to move */
    fun moveSelection(i: Int, onWhole: () -> Unit) {
        val (inside, outside) = split(i) ?: return
        if (inside.isEmpty) return
        if (outside.isEmpty) return onWhole()
        val s = selection ?: return
        withLayer(i, "selection", { l, img, m, pt ->
            if (isClear(l, img, m, pt, outside)) null
            else cropLayer(l, img, m, pt, s).let { (piece, bmp) ->
                bmp?.let { PendingImage(piece.copy(opacity = l.opacity), it, i + 1, Cut(cleared(l, m, pt, s, stagedName(MASK), stagedName(PATCH)), inside)) } ?: Unit
            }
        }) {
            when (it) {
                null -> onWhole()
                is PendingImage -> {
                    it.cut?.rest?.second?.forEach { t -> tiles[t.id] = t }
                    pending = it
                }
            }
        }
    }

    /** Only the selected part stays: a file layer is cropped to it, one without a file has the rest cleared */
    fun extract(i: Int) {
        val (_, outside) = split(i) ?: return
        if (outside.isEmpty) return
        val s = selection ?: return
        if (editing?.layers?.getOrNull(i)?.file != null) return crop(i, keep = true) { (piece, _) -> piece?.let { putPixels(i, it) } }
        withLayer(i, "selection", { l, _, m, pt -> s.inverted(l.region().bounds)?.let { cleared(l, m, pt, it, stagedName(MASK), stagedName(PATCH)) } }) { rest -> rest?.let { putRest(i, it) } }
    }

    /** Right above [i], active: the selected pixels in place. Nothing outside the selection → a duplicate */
    fun layerFromSelection(i: Int) {
        val (inside, outside) = split(i) ?: return
        if (inside.isEmpty) return
        if (outside.isEmpty) return addLayer(i, duplicate = true)
        val s = selection ?: return
        withLayer(i, "selection", { l, img, m, pt -> if (isClear(l, img, m, pt, outside)) null else cropLayer(l, img, m, pt, s) }) { r ->
            if (r == null) addLayer(i, duplicate = true)
            else editing?.layers?.getOrNull(i)?.let { src -> putLayer(i + 1, r.first.copy(opacity = src.opacity), r.second?.asImageBitmap(), insert = true) }
        }
    }

    /** Visible layers as on screen, opacity and masks baked in; with a selection — its part. Not in history */
    fun copyMerged(onDone: () -> Unit) {
        val p = editing ?: return
        val bitmaps = layers.map { it?.asAndroidBitmap() }
        val ms = p.layers.map { mask(it) }
        val ps = p.layers.map { patch(it) }
        runBusy(Dispatchers.IO, "copy merged", { store.mergeLayers(p, p.layers.indices.filter { p.layers[it].visible }, bitmaps, ms, ps, p.selection) }) { (layer, img) ->
            if (img != null) keepClip(layer to img)
            onDone()
        }
    }

    /** A new layer above [above] via the Move tool (history on ✓); its own file copy and Layer.id. In place if it fits the canvas, else centered */
    fun paste(above: Int) {
        val p = editing ?: return
        val (c, img) = clip ?: return
        val src = c.file ?: return
        val l = if (c.x + c.w <= p.width && c.y + c.h <= p.height) c else c.copy(x = (p.width - c.w) / 2, y = (p.height - c.h) / 2)
        val dst = store.stagedName(File(src).extension)
        runBusy(io, "paste", { store.copyLayer(p, src, dst); PendingImage(l.copy(file = dst, id = newLayerId()), img, above + 1) }) { pending = it }
    }

    /** Only the last one is kept */
    private fun keepClip(c: Pair<Layer, Bitmap>?) {
        val old = clip?.first?.file
        clip = c
        if (old != null) write { discardStaged(listOf(old)) }
    }

    /** Baked off the main thread in order; a history step (the Selection brush's joins the selection). [onDone] (main thread) — once shown or dropped */
    fun stroke(s: Stroke, onDone: () -> Unit) {
        strokes.addLast(s to onDone)
        if (strokes.size == 1) bakeNext()
    }

    /** The layer is found by id; its pixels changed meanwhile → dropped. A selection stroke joins the selection as it is at its turn */
    private fun bakeNext() {
        val s = strokes.first().first
        val e = editing ?: return baked()
        if (s.select != null) return bakeOff({ s.selected(e.width, e.height, e.selection) }) { r -> (editing?.selection === e.selection).also { if (it) select(r) } }
        val i = e.layers.indexOfFirst { it.id == s.layer }
        val l = e.layers.getOrNull(i)?.takeIf { it.file == s.file } ?: return baked()
        val base = layers[i]?.asAndroidBitmap()
        val m = mask(l)
        val p = patch(l)
        val at = l.paintable(e.width, e.height)
        // A figure past the layer: the layer grows to hold it first
        bakeOff({
            val g = s.room(at, e.width, e.height)?.let { store.grownLayer(at, base, m, p, it) }
            g to s.bake(g?.first ?: at, g?.second ?: base, m.takeIf { g == null }, p.takeIf { g == null }, store.stagedName(MASK), store.stagedName(PATCH))
        }) { (g, r) -> putStroke(s, l, g, r) }
    }

    /** [put] false — what it was done over changed meanwhile: the stroke goes again */
    private fun <T> bakeOff(work: () -> T, put: (T) -> Boolean) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching(work) }
            r.onFailure { error = it; Log.w(TAG, "stroke failed", it) }
            if (r.isSuccess && !put(r.getOrThrow())) bakeNext() else baked()
        }
    }

    /** [grown] — the layer grown to hold a figure (new file, pixels). false — mask or patch changed meanwhile: bake again */
    private fun putStroke(s: Stroke, was: Layer, grown: Pair<Layer, Bitmap>?, r: Pair<LayerMask?, Tiles?>?): Boolean {
        val e = editing
        val at = e?.layers?.indexOfFirst { it.id == s.layer } ?: -1
        val l = e?.layers?.getOrNull(at)?.takeIf { it.file == s.file }
        val again = l != null && (l.mask != was.mask || l.patch != was.patch)
        if (e == null || l == null || again || r == null) {
            grown?.first?.file?.let { release(listOf(it)) } // no state takes the new file
            return !again
        }
        val (m, p) = r
        m?.let { tiles[it.id] = it }
        p?.let { tiles[it.id] = it }
        val g = grown?.first?.copy(visible = l.visible, opacity = l.opacity, id = l.id) ?: l.paintable(e.width, e.height)
        edit(e.copy(layers = e.layers.putAt(at, g.copy(mask = m?.id, patch = p?.id))), grown?.let { layers.putAt(at, it.second.asImageBitmap()) } ?: layers)
        return true
    }

    private fun baked() {
        strokes.removeFirst().second()
        if (strokes.isNotEmpty()) bakeNext() else afterBake.toList().also { afterBake.clear() }.forEach { it() }
    }

    fun undo() = whenBaked { travel(back = true) }

    fun redo() = whenBaked { travel(back = false) }

    /** The tool must be closed. History masks are in RAM: only pixels may need reading */
    private fun travel(back: Boolean) {
        val now = editing ?: return
        val target = history.peek(back) ?: return
        if (busy || pending != null) return
        val have = HashMap<String, Bitmap>()
        now.layers.zip(layers).forEach { (l, b) -> l.file?.let { k -> if (b != null) have[k] = b.asAndroidBitmap() } }
        val need = target.layers.mapNotNull { it.file }
        need.forEach { f -> if (f !in have) parked[f]?.let { have[f] = it } }
        val go = { bitmaps: List<Bitmap?> -> history.step(back, now); show(target, bitmaps.map { it?.asImageBitmap() }) }
        if (need.all { it in have }) go(store.loadLayers(target, have)) else runBusy(Dispatchers.IO, "history", { store.loadLayers(target, have) }, go)
    }

    /** Single entry for history edits (open/create/Undo/Redo go to show); once per completed edit. Its new rasters must be in [tiles] by then: a step weighs them */
    private fun edit(p: Project, bitmaps: List<ImageBitmap?>) {
        val before = editing ?: return
        val edge = before.selection?.feather?.alpha?.takeIf { it !== p.selection?.feather?.alpha }?.allocationByteCount ?: 0
        val dropped = history.record(before, p, tilesOf(before).bytesBeyond(tilesOf(p)) + edge)
        show(p, bitmaps)
        release(dropped.flatMap { it.layers.files() })
    }

    private fun editLayer(i: Int, change: (Layer) -> Layer) {
        val p = editing ?: return
        val old = p.layers.getOrNull(i) ?: return
        val next = change(old)
        if (next != old) edit(p.copy(layers = p.layers.putAt(i, next)), layers)
    }

    /** Uses current state. [insert] — [placed] becomes a new active layer at [i], else replaces it (keeping visibility and id). [rest] — the layer below gets it (a cut).
    [sel] — the selection afterwards */
    private fun putLayer(i: Int, placed: Layer, img: ImageBitmap?, insert: Boolean, rest: Pair<Layer, List<Tiles>>? = null, sel: Selection? = selection) {
        val e = editing ?: return
        var ls = e.layers
        var bs = layers
        if (rest != null) {
            ls = ls.putAt(i - 1, rest.first.copy(visible = ls[i - 1].visible, opacity = ls[i - 1].opacity, id = ls[i - 1].id))
            if (rest.first.file == null) bs = bs.putAt(i - 1, null)
        }
        if (insert) edit(e.copy(layers = ls.inserted(i, placed), selection = sel, active = i), bs.inserted(i, img))
        else edit(e.copy(layers = ls.putAt(i, placed.copy(visible = ls[i].visible, id = ls[i].id)), selection = sel), bs.putAt(i, img))
    }

    /** Every displayed state passes here → autosaved */
    private fun show(p: Project, bitmaps: List<ImageBitmap?>) {
        val used = p.layers.mapNotNullTo(HashSet()) { it.file }
        editing?.layers?.zip(layers)?.forEach { (l, b) -> l.file?.let { k -> if (k !in used && b != null) parked.put(k, b.asAndroidBitmap()) } }
        used.forEach { parked.remove(it) }
        editing = p
        layers = bitmaps
        autosave()
    }

    /** Recovery file = the open session's unsaved state, written AUTOSAVE_DELAY_MS after the last change. While the dialog is pending it belongs to the interrupted session */
    private fun autosave() {
        if (recovered != null) return
        unsaved = editing?.takeIf { dirty }?.persisted?.let { it to tilesOf(it) } // session-only changes don't rewrite it
        autosaving?.cancel()
        autosaving = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            flushAutosave()
        }
    }

    /** The pending recovery write goes into the queue now (leaving the screen): the LATEST state, then the files released meanwhile are discarded */
    fun flushAutosave() {
        val job = autosaving ?: return
        job.cancel()
        autosaving = null
        val gone = doomed.toList().also { doomed.clear() }
        write { val u = unsaved; saveRecovery(u?.first, u?.second.orEmpty()); discardStaged(gone) }
    }

    /** Files are immutable and live while any state references them. A staged one no state needs goes after the next recovery write (at once while the dialog is pending).
    Project (non-staging) files are left to save()'s pruning */
    private fun release(names: Collection<String>) {
        val used = (history.snapshots + listOfNotNull(editing)).flatMapTo(HashSet()) { it.layers.files() }
        pending?.cut?.rest?.first?.let { used += listOfNotNull(it.mask, it.patch) }
        tiles.keys.retainAll(used)
        val free = names.filter { it !in used }
        free.forEach { parked.remove(it) }
        if (free.isEmpty()) return
        if (recovered != null) write { discardStaged(free) } else doomed += free
    }
}
