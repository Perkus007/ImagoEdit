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

private const val THUMB_SIDE = 384 // длинная сторона миниатюры, px
private const val META = "project.json" // описание проекта внутри его папки
private const val THUMB = "thumb.webp"
private const val RECOVERY = "recovery.json" // несохранённое состояние открытого проекта (автосохранение), лежит в staging рядом со своими файлами

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
    FileOutputStream(tmp).use { it.write(text.toByteArray()); it.fd.sync() } // fsync: data is physically on disk before the swap
    Files.move(tmp.toPath(), toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

private fun Project.tileIds() = layers.flatMapTo(HashSet()) { listOfNotNull(it.mask, it.patch) }

/** The app's folder in the shared storage: projects, staging, the user's fonts */
@Suppress("DEPRECATION") // with MANAGE_EXTERNAL_STORAGE this is the standard way to get the shared storage root
val appDir: File get() = File(Environment.getExternalStorageDirectory(), ".imagoedit")

/** No index: list = root contents, so project folders can be freely copied, moved and deleted by hand */
class ProjectStore {
    private val root = appDir

    private fun dirOf(id: Long) = File(root, id.toString())

    private fun layersDir(id: Long) = File(dirOf(id), "layers")

    // New layers land here first and move into the project on save. Non-numeric name → load() skips it. Same root → moving into a project = rename without copying bytes
    private val staging = File(root, ".staging")

    private val recovery = File(staging, RECOVERY)
    private var recorded: Project? = null // [recovery] content. Write queue only

    fun hasAccess() = Environment.isExternalStorageManager()

    /** id comes from the folder name (not JSON): a folder copied/renamed by hand doesn't break it.
    Folders without a valid project.json are skipped but NOT touched. I/O errors aren't swallowed: a transient read failure must not look like "no projects" */
    fun load(): List<Project> = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { d ->
        val id = d.name.toLongOrNull() ?: return@mapNotNull null
        val meta = File(d, META).takeIf { it.exists() } ?: return@mapNotNull null
        try { JSONObject(meta.readText()).toProject(id) } catch (_: JSONException) { null }
    }.sortedByDescending { it.updated }

    /** images == null → the old thumbnail stays untouched (otherwise re-saving without loaded pixels would wipe the preview).
    Step order guarantees a failure at any step leaves the previous description intact.
    origin — project whose folder supplies layers outside staging: the source project for a copy, p itself for a normal save. [tiles] — p's masks and patches: those not on disk yet are written first */
    fun save(p: Project, images: List<Bitmap?>? = null, tiles: List<Tiles> = emptyList(), origin: Long = p.id) {
        val dir = dirOf(p.id).apply { mkdirs() }
        writeTiles(tiles, origin)
        placeLayers(p, origin) // project.json never references a file that doesn't exist yet
        File(dir, META).writeAtomic(p.toJson().toString(2))
        pruneLayers(p) // old files are deleted only once the new description is on disk
        // Thumbnail after the description: its failure must not drop the project from the list
        images?.let { writeThumb(p, it, tiles) }
    }

    fun remove(ids: Set<Long>) = ids.forEach { dirOf(it).deleteRecursively() }

    /** Display only → HARDWARE (no getPixels/compress) */
    fun loadThumb(id: Long): Bitmap? = File(dirOf(id), THUMB).takeIf { it.exists() }
        ?.let { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE }) }

    private fun stagedFile(name: String) = File(staging.apply { mkdirs() }, name)

    /** Extension from MIME is cosmetic: format is detected by content. The name is unique and equals the layer name in project.json: a changed layer is always written under a NEW name */
    fun newStaged(mime: String?) = stagedFile(stagedName(MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"))

    /** No I/O: the name may be handed out before the write queue creates the file */
    fun stagedName(ext: String) = "${UUID.randomUUID()}.$ext"

    /** [have] and duplicates share bitmaps by Layer.file */
    fun loadLayers(p: Project, have: Map<String, Bitmap> = emptyMap()): List<Bitmap?> {
        val loaded = HashMap(have)
        return p.layers.map { l -> l.file?.let { k -> loaded.getOrPut(k) { decodeLayer(p, l) } } }
    }

    private fun layerFile(p: Project, name: String) = File(staging, name).takeIf { it.exists() } ?: File(layersDir(p.id), name)

    /** [l]'s pixels from its file */
    internal fun decodeLayer(p: Project, l: Layer): Bitmap = layerFile(p, l.file!!).decodeImage()

    fun readMask(p: Project, l: Layer): LayerMask? = l.mask?.let { layerFile(p, it).readTiles(it, l.fileW, l.fileH, ::blankMaskTile) }

    /** [l]'s patch, its tiles of the kind of its pixels [base] (null — none) */
    fun readPatch(p: Project, l: Layer, base: Bitmap?): Tiles? = l.patch?.let { layerFile(p, it).readTiles(it, l.fileW, l.fileH) { patchTile(base) } }

    /** Masks and patches of [p]'s layers, each once; [images] — their pixels (loadLayers) */
    fun loadTiles(p: Project, images: List<Bitmap?>): List<Tiles> = p.layers.filter { it.mask != null }.distinctBy { it.mask }.mapNotNull { readMask(p, it) } +
        p.layers.withIndex().filter { it.value.patch != null }.distinctBy { it.value.patch }.mapNotNull { (i, l) -> readPatch(p, l, images[i]) }

    /** Into staging the ones not on disk yet: masks and patches live in RAM, a file is needed only by a saved state */
    private fun writeTiles(tiles: List<Tiles>, origin: Long) = tiles.forEach { t ->
        if (!File(staging, t.id).exists() && !File(layersDir(origin), t.id).exists()) t.write(stagedFile(t.id))
    }

    /** Byte-for-byte (format, EXIF and profile kept) into staging under [dst] */
    fun copyLayer(p: Project, name: String, dst: String) {
        val f = stagedFile(dst)
        f.deleteOnFailure { Files.copy(layerFile(p, name).toPath(), f.toPath()) }
    }

    /** [src]'s pixels [mem] (null — none) rendered by [t] into its rect on the canvas (see ContentTransform.layerRect); its [mask] goes the same way into a new one.
    With a [patch] the pixels, mask and patch are flattened first: one file, neither mask nor patch. A patch alone goes into a new one over the canvas: tiles only where it lands.
    Returns the layer, its pixels and the new rasters it references */
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

    /** [src] without pixels: the part of its [patch] its tiles cover, rendered by [t], cut back into tiles over the canvas. [t]'s visible part is taken as fractions of that part: the frame stays put */
    private fun bakePatch(p: Project, src: Layer, patch: Tiles, t: ContentTransform): Triple<Layer, Bitmap?, List<Tiles>> {
        val cr = patch.bounds()
        val part = createBitmap(cr.width(), cr.height())
        try {
            Canvas(part).run {
                translate(-cr.left.toFloat(), -cr.top.toFloat())
                drawTiles(patch.levels[0], patch.cols(0), 1f, tilePaint(), RectF())
            }
            val oc = RectF(cr).also { orientMatrix(src.orient, src.fileW, src.fileH).mapRect(it) } // the part as it lies, layer px
            val b = t.box
            val tp = t.copy(box = ContentBox((b.left * src.w - oc.left) / oc.width(), (b.top * src.h - oc.top) / oc.height(), (b.right * src.w - oc.left) / oc.width(), (b.bottom * src.h - oc.top) / oc.height()))
            val rect = tp.layerRect(p.width, p.height, oc.width().roundToInt(), oc.height().roundToInt()) ?: return Triple(Layer(), null, emptyList())
            val out = tp.render(part, rect, src.orient)
            val tiles = try { out.tiledAt(stagedName(PATCH), p.width, p.height, rect.left, rect.top) } finally { out.recycle() }
            return Triple(if (tiles == null) Layer() else Layer(w = p.width, h = p.height, patch = tiles.id), null, listOfNotNull(tiles))
        } finally { part.recycle() }
    }

    /** The canvas rect [patch]'s tiles cover on [l] */
    private fun Layer.patchArea(patch: Tiles) = Rect().also { toCanvas(patch.bounds()).round(it) }

    /** [l]'s pixels [mem] (null — none) that [sel] selects (canvas px; null — all), × its coverage on a soft edge, its [mask] and [patch] applied, opacity not baked, cropped to them.
    The part is copied as stored, not turned: the file's orientation goes to the new layer's orient → memory = one copy of the result */
    fun cropLayer(l: Layer, mem: Bitmap?, mask: LayerMask?, patch: Tiles?, sel: Selection?): Pair<Layer, Bitmap?> {
        val r = l.region().apply { if (sel != null) op(sel.reach, Region.Op.INTERSECT) }
        if (mem == null) patch?.let { r.op(l.patchArea(it), Region.Op.INTERSECT) } // without pixels nothing lies outside the patch's tiles
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
            clipOutPath(r.boundaryPath) // edges on whole px → each pixel fully in or out
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            restore()
            sel?.feather?.draw(this, keepPaint())
        }
        return writeLayer(bmp, rect, m.toOrient())
    }

    /** Whether [l] has no pixel with any opacity in [area] (canvas px), its pixels [mem] (null — none), [mask] and [patch] applied. Stops at the first one */
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

    /** [l]'s pixels [mem] (null — none) with any opacity, its [mask] and [patch] applied, as a canvas region. Pixels alone without an alpha channel → the whole layer */
    fun visibleRegion(l: Layer, mem: Bitmap?, mask: LayerMask?, patch: Tiles?): Region =
        if (mem != null && !mem.hasAlpha() && mask == null && patch == null) l.region() else visibleRegion(mem, l.fileW, l.fileH, l.fileToCanvas(), mask, patch)

    /** [idx] ascending = bottom to top. Opacity, masks and patches are baked into pixels → the new layer has 100%, neither mask nor patch. Hidden layers are merged like visible ones:
    the caller decides which to take. The result is in the color space of the lowest one with pixels. [area] — only the pixels it selects (× its coverage on a soft edge), cropped to them */
    fun mergeLayers(p: Project, idx: List<Int>, mem: List<Bitmap?>, masks: List<LayerMask?>, patches: List<Tiles?>, area: Selection? = null): Pair<Layer, Bitmap?> {
        val parts = idx.filter { p.layers[it].hasImage }
        val r = Region()
        for (i in parts) r.op(p.layers[i].region(), Region.Op.UNION)
        r.op(0, 0, p.width, p.height, Region.Op.INTERSECT) // a patch alone may reach past a cropped canvas
        if (area != null) r.op(area.reach, Region.Op.INTERSECT)
        if (r.isEmpty) return Layer() to null
        val rect = r.bounds
        val out = parts.firstNotNullOfOrNull { mem[it] }?.blankLike(rect.width(), rect.height()) ?: createBitmap(rect.width(), rect.height())
        val canvas = Canvas(out).apply {
            translate(-rect.left.toFloat(), -rect.top.toFloat())
            if (area != null) clipPath(r.boundaryPath) // edges on whole px → each pixel is fully in or out
        }
        val paints = MaskPaints() // pixels land 1:1 on integer coordinates: filtering and anti-aliasing change nothing
        for (i in parts) canvas.drawLayer(p.layers[i], mem[i], masks[i], patches[i], paints)
        area?.feather?.draw(canvas, keepPaint())
        return writeLayer(out, rect)
    }

    /** [out] becomes the layer's pixels as is: its PNG decodes exactly to them. [orient] — how [out] lies in [rect]. The returned layer is visible, 100%: the caller substitutes its own if needed */
    internal fun writeLayer(out: Bitmap, rect: Rect, orient: Int = 0): Pair<Layer, Bitmap> {
        val dst = newStaged("image/png")
        dst.deleteOnFailure { out.writePng(dst) }
        return Layer(dst.name, x = rect.left, y = rect.top, w = rect.width(), h = rect.height(), orient = orient) to out
    }

    fun discardStaged(names: Collection<String>) = names.forEach { File(staging, it).delete() }

    /** Once per process, before its first staging write: the unsaved state of the session the previous process left open, or null.
    Staging files it doesn't reference are that process's leftovers → deleted. A read error propagates before the sweep: a transient failure must not cost the session its files */
    fun recover(): Project? {
        val p = recovery.takeIf { it.exists() }?.let { f -> try { JSONObject(f.readText()).run { toProject(getLong("id")) } } catch (_: JSONException) { null } }
        val keep = p?.let { it.layers.files() + RECOVERY }.orEmpty()
        staging.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        recorded = p
        return p
    }

    /** null — nothing unsaved. An unchanged state isn't rewritten. [tiles] — p's masks and patches: the ones not on disk yet are written first.
    Those only the previous state referenced leave staging: the editor keeps them in RAM, a state that needs one again writes it again */
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
        // Scale from the rounded thumbnail size: a full-canvas layer covers it without a translucent edge
        val kx = tw / p.width.toFloat()
        val ky = th / p.height.toFloat()
        val thumb = createBitmap(tw, th)
        val canvas = Canvas(thumb)
        val paints = MaskPaints()
        for ((i, l) in p.layers.withIndex()) {
            if (!l.visible || !l.hasImage) continue
            val src = images[i]
            // The file's width and height in the thumbnail
            val turned = (l.orient and 1) == 1
            val dw = if (turned) l.h * ky else l.w * kx
            val dh = if (turned) l.w * kx else l.h * ky
            // Halve until < 2× of the target size remains: a single bilinear pass over a large image causes moiré. src belongs to the editor — left untouched
            val bmp = src?.halveWhile({ it.width > dw * 2 }, { it.height > dh * 2 })
            canvas.drawLayer(l, bmp, tiles.firstOrNull { it.id == l.mask }, tiles.firstOrNull { it.id == l.patch }, paints, filter = true, kx = kx, ky = ky)
            if (bmp !== src) bmp?.recycle()
        }
        FileOutputStream(File(dirOf(p.id), THUMB)).use { thumb.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }
        thumb.recycle()
    }
}
