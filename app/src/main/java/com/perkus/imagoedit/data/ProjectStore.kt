package com.perkus.imagoedit.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.graphics.createBitmap
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.roundToInt

private const val THUMB_SIDE = 384 // длинная сторона, px
private const val META = "project.json"
private const val THUMB = "thumb.webp"
private const val RECOVERY = "recovery.json" // несохранённое состояние, в staging рядом со своими файлами

private fun Layer.toJson() = JSONObject().put("file", file).put("visible", visible).put("x", x).put("y", y).put("w", w).put("h", h).put("opacity", opacity).put("orient", orient).put("mask", mask)
    .put("patch", patch).put("id", id)

private fun JSONObject.toLayer() = Layer(optString("file").ifEmpty { null }, optBoolean("visible", true), optInt("x"), optInt("y"), optInt("w"), optInt("h"), optInt("opacity", MAX_OPACITY), optInt("orient"),
    optString("mask").ifEmpty { null }, optString("patch").ifEmpty { null }, optLong("id", newLayerId()))

private fun Project.toJson() = JSONObject().put("name", name).put("w", width).put("h", height).put("updated", updated).put("layers", JSONArray(layers.map { it.toJson() }))

private fun JSONObject.toProject(id: Long): Project {
    val layers = optJSONArray("layers")?.let { a -> List(a.length()) { a.getJSONObject(it).toLayer() } }.orEmpty().ifEmpty { listOf(Layer()) }
    return Project(id, getString("name"), getInt("w"), getInt("h"), optLong("updated", id), layers)
}

/** fsync, then an atomic swap: a failure at any moment leaves the previous version intact */
private fun File.writeAtomic(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    FileOutputStream(tmp).use { it.write(text.toByteArray()); it.fd.sync() }
    Files.move(tmp.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

private fun Project.tileIds() = layers.flatMapTo(HashSet()) { listOfNotNull(it.mask, it.patch) }

/** Projects, staging, user fonts */
@Suppress("DEPRECATION") // standard with MANAGE_EXTERNAL_STORAGE
val appDir: File get() = File(Environment.getExternalStorageDirectory(), ".imagoedit")

/** No index: project folders can be copied, moved, deleted by hand */
class ProjectStore {
    private val root = appDir

    private fun dirOf(id: Long) = File(root, id.toString())

    private fun layersDir(id: Long) = File(dirOf(id), "layers")

    // New files land here; non-numeric name → load() skips it; same root → moving into a project is a rename
    private val staging = File(root, ".staging")

    private val recovery = File(staging, RECOVERY)
    private var recorded: Project? = null // write queue only

    fun hasAccess() = Environment.isExternalStorageManager()

    /** id from the folder name. Folders without a valid project.json are skipped, untouched. I/O errors propagate: a failed read mustn't look like "no projects" */
    fun load(): List<Project> = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { d ->
        val id = d.name.toLongOrNull() ?: return@mapNotNull null
        val meta = File(d, META).takeIf { it.exists() } ?: return@mapNotNull null
        try { JSONObject(meta.readText()).toProject(id) } catch (_: JSONException) { null }
    }.sortedByDescending { it.updated }

    /** images == null → the thumbnail stays. A failure at any step leaves the previous description intact. [origin] — supplies layers outside staging
    (the source of a copy, else p); [tiles] — p's masks and patches, unwritten ones first */
    fun save(p: Project, images: List<Bitmap?>? = null, tiles: List<Tiles> = emptyList(), origin: Long = p.id) {
        val dir = dirOf(p.id).apply { mkdirs() }
        writeTiles(tiles, origin)
        placeLayers(p, origin) // project.json never references a missing file
        File(dir, META).writeAtomic(p.toJson().toString(2))
        pruneLayers(p) // old files go only once the new description is on disk
        // Thumbnail last: its failure mustn't drop the project
        images?.let { writeThumb(p, it, tiles) }
    }

    fun remove(ids: Set<Long>) = ids.forEach { dirOf(it).deleteRecursively() }

    /** Display only → HARDWARE (no getPixels/compress) */
    fun loadThumb(id: Long): Bitmap? = File(dirOf(id), THUMB).takeIf { it.exists() }
        ?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE }) }

    private fun stagedFile(name: String) = File(staging.apply { mkdirs() }, name)

    /** Extension is cosmetic (format by content). A changed layer is always written under a NEW name */
    fun newStaged(mime: String?) = stagedFile(stagedName(MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"))

    /** No I/O: the name may be handed out before the write queue creates the file */
    fun stagedName(ext: String) = "${UUID.randomUUID()}.$ext"

    /** [have] and duplicates share bitmaps by Layer.file */
    fun loadLayers(p: Project, have: Map<String, Bitmap> = emptyMap()): List<Bitmap?> {
        val loaded = HashMap(have)
        return p.layers.map { l -> l.file?.let { k -> loaded.getOrPut(k) { decodeLayer(p, l) } } }
    }

    private fun layerFile(p: Project, name: String) = File(staging, name).takeIf { it.exists() } ?: File(layersDir(p.id), name)

    internal fun decodeLayer(p: Project, l: Layer): Bitmap = layerFile(p, l.file!!).decodeImage()

    fun readMask(p: Project, l: Layer): LayerMask? = l.mask?.let { layerFile(p, it).readTiles(it, l.fileW, l.fileH, ::blankMaskTile) }

    /** Tiles of [base]'s kind */
    fun readPatch(p: Project, l: Layer, base: Bitmap?): Tiles? = l.patch?.let { layerFile(p, it).readTiles(it, l.fileW, l.fileH) { patchTile(base) } }

    /** Each once; [images] — their pixels */
    fun loadTiles(p: Project, images: List<Bitmap?>): List<Tiles> = p.layers.filter { it.mask != null }.distinctBy { it.mask }.mapNotNull { readMask(p, it) } +
        p.layers.withIndex().filter { it.value.patch != null }.distinctBy { it.value.patch }.mapNotNull { (i, l) -> readPatch(p, l, images[i]) }

    /** Writes those not on disk yet: a file is needed only by a saved state */
    private fun writeTiles(tiles: List<Tiles>, origin: Long) = tiles.forEach { t ->
        if (!File(staging, t.id).exists() && !File(layersDir(origin), t.id).exists()) t.write(stagedFile(t.id))
    }

    /** Byte-for-byte (format, EXIF and profile kept) into staging under [dst] */
    fun copyLayer(p: Project, name: String, dst: String) {
        val f = stagedFile(dst)
        f.deleteOnFailure { Files.copy(layerFile(p, name).toPath(), f.toPath()) }
    }

    /** [src] rendered by [t] into its canvas rect, its [mask] the same way. With a [patch] all is flattened into one file; a patch alone → a new one over the canvas.
    Returns the layer, its pixels and new rasters */
    fun bakeLayer(p: Project, src: Layer, mem: Bitmap?, mask: LayerMask?, patch: Tiles?, t: ContentTransform): Triple<Layer, Bitmap?, List<Tiles>> {
        if (mem == null) return patch?.let { bakePatch(p, src, it, t) } ?: Triple(Layer(), null, emptyList())
        val rect = t.layerRect(p.width, p.height, src.w, src.h) ?: return Triple(Layer(), null, emptyList())
        if (patch == null) {
            val (l, bmp) = writeLayer(t.render(mem, rect, src.orient), rect)
            val m = mask?.transformed(stagedName(MASK), t, rect, src.orient)
            return Triple(l.copy(mask = m?.id), bmp, listOfNotNull(m))
        }
        val flat = mem.blankLike(mem.width, mem.height)
        val (l, bmp) = try {
            Canvas(flat).drawMasked(mem, mem.width, mem.height, mask, patch, 1f, 255, false, MaskPaints())
            writeLayer(t.render(flat, rect, src.orient), rect)
        } finally { flat.recycle() }
        return Triple(l, bmp, emptyList())
    }

    /** [src] without pixels: its patch part rendered by [t] back into tiles over the canvas; [t]'s visible part is fractions of that part */
    private fun bakePatch(p: Project, src: Layer, patch: Tiles, t: ContentTransform): Triple<Layer, Bitmap?, List<Tiles>> {
        val cr = patch.bounds()
        val part = createBitmap(cr.width(), cr.height())
        try {
            Canvas(part).run {
                translate(-cr.left.toFloat(), -cr.top.toFloat())
                drawTiles(patch.levels[0], patch.cols(0), 1f, tilePaint(), RectF())
            }
            val oc = RectF(cr).also { orientMatrix(src.orient, src.fileW, src.fileH).mapRect(it) } // layer px
            val b = t.box
            val tp = t.copy(box = ContentBox((b.left * src.w - oc.left) / oc.width(), (b.top * src.h - oc.top) / oc.height(), (b.right * src.w - oc.left) / oc.width(), (b.bottom * src.h - oc.top) / oc.height()))
            val rect = tp.layerRect(p.width, p.height, oc.width().roundToInt(), oc.height().roundToInt()) ?: return Triple(Layer(), null, emptyList())
            val out = tp.render(part, rect, src.orient)
            val tiles = try { out.tiledAt(stagedName(PATCH), p.width, p.height, rect.left, rect.top) } finally { out.recycle() }
            return Triple(if (tiles == null) Layer() else Layer(w = p.width, h = p.height, patch = tiles.id), null, listOfNotNull(tiles))
        } finally { part.recycle() }
    }

    /** [l] flattened into a new upright file over canvas rect [r] (Stroke.room), opacity not baked. Returns the layer and its pixels */
    fun grownLayer(l: Layer, mem: Bitmap?, mask: LayerMask?, patch: Tiles?, r: Rect): Pair<Layer, Bitmap> {
        val out = mem?.blankLike(r.width(), r.height()) ?: createBitmap(r.width(), r.height())
        Canvas(out).run {
            translate(-r.left.toFloat(), -r.top.toFloat())
            concat(l.fileToCanvas())
            drawMasked(mem, l.fileW, l.fileH, mask, patch, 1f, 255, false, MaskPaints())
        }
        return writeLayer(out, r)
    }

    private fun Layer.patchArea(patch: Tiles) = Rect().also { toCanvas(patch.bounds()).round(it) }

    /** [l]'s px that [sel] selects (null — all), mask and patch applied, opacity not baked, cropped. Copied as stored: the orientation goes to the new layer */
    fun cropLayer(l: Layer, mem: Bitmap?, mask: LayerMask?, patch: Tiles?, sel: Selection?): Pair<Layer, Bitmap?> {
        val r = l.region().apply { if (sel != null) op(sel.reach, Region.Op.INTERSECT) }
        if (mem == null) patch?.let { r.op(l.patchArea(it), Region.Op.INTERSECT) }
        if (r.isEmpty) return Layer() to null
        val rect = r.bounds
        val s = Rect().also { l.toFile(rect).round(it) }
        val m = Matrix().apply { setTranslate(s.left.toFloat(), s.top.toFloat()); postConcat(l.fileToCanvas()) } // part px → canvas px
        val bmp = mem?.blankLike(s.width(), s.height()) ?: createBitmap(s.width(), s.height())
        Canvas(bmp).apply {
            save()
            translate(-s.left.toFloat(), -s.top.toFloat())
            drawMasked(mem, l.fileW, l.fileH, mask, patch, 1f, 255, false, MaskPaints())
            restore()
            concat(Matrix().also { m.invert(it) })
            save()
            clipOutPath(r.boundaryPath) // whole-px edges: no AA
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            restore()
            sel?.feather?.draw(this, keepPaint())
        }
        return writeLayer(bmp, rect, m.toOrient())
    }

    /** No px with any opacity in [area], mask and patch applied; stops at the first */
    fun isClear(l: Layer, mem: Bitmap?, mask: LayerMask?, patch: Tiles?, area: Region): Boolean {
        val r = l.region().apply { op(area, Region.Op.INTERSECT) }
        if (mem == null) patch?.let { r.op(l.patchArea(it), Region.Op.INTERSECT) }
        if (r.isEmpty) return true
        val toFile = l.canvasToFile()
        val row = IntArray(l.fileW)
        val tmp = IntArray(TILE)
        val iter = RegionIterator(r)
        val c = Rect()
        val f = RectF()
        while (iter.next(c)) {
            f.set(c)
            toFile.mapRect(f)
            f.round(c)
            for (y in c.top until c.bottom) {
                readLine(mem, mask, patch, row, c.left, y, true, c.width(), tmp)
                for (x in 0 until c.width()) if ((row[x] ushr 24) != 0) return false
            }
        }
        return true
    }

    /** Px with any opacity as a canvas region; no alpha channel → the whole layer */
    fun visibleRegion(l: Layer, mem: Bitmap?, mask: LayerMask?, patch: Tiles?): Region =
        if (mem != null && !mem.hasAlpha() && mask == null && patch == null) l.region() else visibleRegion(mem, l.fileW, l.fileH, l.fileToCanvas(), mask, patch)

    /** [idx] bottom to top; opacity, masks and patches baked in (100%, no mask/patch); hidden layers merge too. Color space of the lowest with pixels.
    [area] — only what it selects, cropped */
    fun mergeLayers(p: Project, idx: List<Int>, mem: List<Bitmap?>, masks: List<LayerMask?>, patches: List<Tiles?>, area: Selection? = null): Pair<Layer, Bitmap?> {
        val parts = idx.filter { p.layers[it].hasImage }
        val r = Region()
        for (i in parts) r.op(p.layers[i].region(), Region.Op.UNION)
        r.op(0, 0, p.width, p.height, Region.Op.INTERSECT) // a patch may reach past a cropped canvas
        if (area != null) r.op(area.reach, Region.Op.INTERSECT)
        if (r.isEmpty) return Layer() to null
        val rect = r.bounds
        val out = parts.firstNotNullOfOrNull { mem[it] }?.blankLike(rect.width(), rect.height()) ?: createBitmap(rect.width(), rect.height())
        val canvas = Canvas(out).apply {
            translate(-rect.left.toFloat(), -rect.top.toFloat())
            if (area != null) clipPath(r.boundaryPath) // whole-px edges: no AA
        }
        val paints = MaskPaints() // 1:1 on integer coordinates
        for (i in parts) canvas.drawLayer(p.layers[i], mem[i], masks[i], patches[i], paints)
        area?.feather?.draw(canvas, keepPaint())
        return writeLayer(out, rect)
    }

    /** [out] becomes the layer's pixels as is (its PNG decodes exactly to them); [orient] — how it lies in [rect]. Visible, 100% */
    internal fun writeLayer(out: Bitmap, rect: Rect, orient: Int = 0): Pair<Layer, Bitmap> {
        val dst = newStaged("image/png")
        dst.deleteOnFailure { out.writePng(dst) }
        return Layer(dst.name, x = rect.left, y = rect.top, w = rect.width(), h = rect.height(), orient = orient) to out
    }

    fun discardStaged(names: Collection<String>) = names.forEach { File(staging, it).delete() }

    /** Once per process, before the first staging write: the previous process's unsaved session or null; staging files it doesn't reference are deleted.
    A read error propagates before the sweep */
    fun recover(): Project? {
        val p = recovery.takeIf { it.exists() }?.let { f -> try { JSONObject(f.readText()).run { toProject(getLong("id")) } } catch (_: JSONException) { null } }
        val keep = p?.let { it.layers.files() + RECOVERY }.orEmpty()
        staging.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        recorded = p
        return p
    }

    /** null — nothing unsaved; unchanged → not rewritten. [tiles]: unwritten ones first; those only the previous state referenced leave staging */
    fun saveRecovery(p: Project?, tiles: List<Tiles> = emptyList()) {
        if (p == recorded) return
        if (p == null) recovery.delete() else {
            writeTiles(tiles, p.id)
            stagedFile(RECOVERY).writeAtomic(p.toJson().put("id", p.id).toString())
        }
        recorded?.let { old -> discardStaged(old.tileIds() - p?.tileIds().orEmpty()) }
        recorded = p
    }

    private fun placeLayers(p: Project, origin: Long) {
        val dir = layersDir(p.id).apply { mkdirs() }
        p.layers.files().forEach { name ->
            val staged = File(staging, name)
            val dst = File(dir, name).toPath()
            when {
                staged.exists() -> Files.move(staged.toPath(), dst, StandardCopyOption.REPLACE_EXISTING)
                origin != p.id -> Files.copy(File(layersDir(origin), name).toPath(), dst, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun pruneLayers(p: Project) {
        val used = p.layers.files().toHashSet()
        layersDir(p.id).listFiles()?.forEach { if (it.name !in used) it.delete() }
    }

    private fun writeThumb(p: Project, images: List<Bitmap?>, tiles: List<Tiles>) {
        val k = minOf(1f, THUMB_SIDE.toFloat() / maxOf(p.width, p.height))
        val tw = (p.width * k).roundToInt().coerceAtLeast(1)
        val th = (p.height * k).roundToInt().coerceAtLeast(1)
        // Scale from the rounded size: a full-canvas layer covers it with no translucent edge
        val kx = tw / p.width.toFloat()
        val ky = th / p.height.toFloat()
        val thumb = createBitmap(tw, th)
        val canvas = Canvas(thumb)
        val paints = MaskPaints()
        for ((i, l) in p.layers.withIndex()) {
            if (!l.visible || !l.hasImage) continue
            val src = images[i]
            val turned = (l.orient and 1) == 1
            val dw = if (turned) l.h * ky else l.w * kx
            val dh = if (turned) l.w * kx else l.h * ky
            // Halve to < 2× the target: one bilinear pass causes moiré. src is the editor's: untouched
            val bmp = src?.halveWhile({ it.width > dw * 2 }, { it.height > dh * 2 })
            canvas.drawLayer(l, bmp, tiles.firstOrNull { it.id == l.mask }, tiles.firstOrNull { it.id == l.patch }, paints, filter = true, kx = kx, ky = ky)
            if (bmp !== src) bmp?.recycle()
        }
        FileOutputStream(File(dirOf(p.id), THUMB)).use { thumb.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }
        thumb.recycle()
    }
}
