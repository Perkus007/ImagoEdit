package com.perkus.imagoedit.data

import androidx.compose.runtime.mutableStateListOf

private const val MAX_LIGHT_CHECKPOINTS = 200 // лёгкие шаги Undo: без новых файлов изображения, почти бесплатны
private const val MAX_HEAVY_CHECKPOINTS = 25 // тяжёлые шаги Undo (фото, вставка, запекание, слияние): каждый держит свой файл пикселей; только RAM
private const val TILE_UNDO_RAM_SHARE = 24 // RAM тайлов и мягких краёв, что держит только история: 1/24 RAM устройства; сверх — отрезаются старейшие, последний шаг остаётся
private const val MIN_TILE_UNDO_BYTES = 4L * SAFE_PIXELS // ≥ 100 МБ
private const val MAX_TILE_UNDO_BYTES = 12L * SAFE_PIXELS // ≤ 300 МБ

/** Checkpoints are Project snapshots taken before an edit (cheap: everything immutable, files never overwritten); the current state moves to Redo on Undo. Main thread only */
class History(ram: Long) {
    private val maxTileBytes = (ram / TILE_UNDO_RAM_SHARE).coerceIn(MIN_TILE_UNDO_BYTES, MAX_TILE_UNDO_BYTES)

    // Weight travels with the snapshot between Undo and Redo; [bytes] — tiles freed with the step (bytesBeyond)
    private class Checkpoint(val state: Project, val heavy: Boolean, var bytes: Long)

    private val past = mutableStateListOf<Checkpoint>()
    private val future = mutableStateListOf<Checkpoint>()

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    val snapshots: List<Project> get() = (past + future).map { it.state }

    /** Heavy = [after] has an image file [before] lacks. Over a limit → trimmed from the oldest end, never this edit; a light step dropped over its limit merges
    into the previous one. Returns the dropped snapshots: the Redo branch and the evicted */
    fun record(before: Project, after: Project, bytes: Long): List<Project> {
        val dropped = future.mapTo(ArrayList()) { it.state }
        future.clear()
        past.add(Checkpoint(before, heavy = !before.layers.mapNotNull { it.file }.containsAll(after.layers.mapNotNull { it.file }), bytes))
        var heavy = past.count { it.heavy }
        var held = past.sumOf { it.bytes }
        while (past.size > 1 && (heavy > MAX_HEAVY_CHECKPOINTS || held > maxTileBytes)) {
            val oldest = past.removeAt(0)
            dropped.add(oldest.state)
            if (oldest.heavy) heavy--
            held -= oldest.bytes
        }
        while (past.size - heavy > MAX_LIGHT_CHECKPOINTS) {
            val k = past.indexOfFirst { !it.heavy }
            val c = past.removeAt(k)
            if (k > 0) past[k - 1].bytes += c.bytes
            dropped.add(c.state)
        }
        return dropped
    }

    fun peek(back: Boolean) = (if (back) past else future).lastOrNull()?.state

    // Separate from [peek]: history changes only once the target's pixels are loaded
    fun step(back: Boolean, now: Project) {
        val (from, to) = if (back) past to future else future to past
        val c = from.removeAt(from.lastIndex)
        to.add(Checkpoint(now, c.heavy, c.bytes))
    }

    fun clear(): List<Project> = snapshots.also { past.clear(); future.clear() }
}
