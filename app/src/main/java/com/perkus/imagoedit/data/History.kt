package com.perkus.imagoedit.data

import androidx.compose.runtime.mutableStateListOf

private const val MAX_LIGHT_CHECKPOINTS = 200 // максимум шагов Undo лёгких правок (видимость, непрозрачность, порядок, удаление, очистка, дубликат, пустой слой, перенос без обрезки, выделение, выбор слоя, штрих кисти, ластика или штампа, стирание по выделению): новых файлов изображения не приносят, снимки делят слои → почти бесплатны
private const val MAX_HEAVY_CHECKPOINTS = 25 // максимум шагов Undo тяжёлых правок (фото, вставка, запекание, слияние): каждая оставляет в истории свой файл пикселей (диск + запас RAM). Шаги — только RAM: на диск не пишутся, при закрытии проекта сбрасываются
private const val TILE_UNDO_RAM_SHARE = 24 // память тайлов (Tiles) масок и патчей и мягких краёв выделения, которую держит только история: 1/24 RAM устройства (3 ГБ → ~115 МБ, 4 ГБ → ~155, 6 ГБ → ~230, 8 ГБ+ → 300). Сверх неё шаги отрезаются с самого старого конца, последний остаётся всегда
private const val MIN_TILE_UNDO_BYTES = 4L * SAFE_PIXELS // но не меньше 100 МБ
private const val MAX_TILE_UNDO_BYTES = 12L * SAFE_PIXELS // и не больше 300 МБ

/** Checkpoints are Project snapshots taken before an edit; the current state is the editor's Project, moved to Redo on Undo.
Snapshots are cheap: Project and Layer are immutable, a layer pixel file is never overwritten (an edit writes a new one), a mask edit makes new tiles only where it changed.
Call from the main thread. [ram] — the device's total RAM, bytes */
class History(ram: Long) {
    private val maxTileBytes = (ram / TILE_UNDO_RAM_SHARE).coerceIn(MIN_TILE_UNDO_BYTES, MAX_TILE_UNDO_BYTES)

    // Weight travels with the snapshot between Undo and Redo: they don't change the step count of either weight. [bytes] — of the tiles the state before the edit has and the one after doesn't (bytesBeyond): freed with the step
    private class Checkpoint(val state: Project, val heavy: Boolean, var bytes: Long)

    private val past = mutableStateListOf<Checkpoint>()
    private val future = mutableStateListOf<Checkpoint>()

    // Read from composition → Compose state
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    val snapshots: List<Project> get() = (past + future).map { it.state }

    /** An edit is heavy if [after] has an image file absent from [before]; [bytes] — of the tiles [before] has and [after] doesn't.
    Heavy over limit, or tiles over their memory → history is trimmed from the oldest end, never this edit: the last one can always be undone.
    Older light steps go with it: a file is freed only when no snapshot references it.
    Light over limit → the oldest light step is dropped, heavy ones untouched; that step merges into the previous one (one Undo reverts both edits).
    Returns snapshots dropped from history: the Redo branch and those evicted by limits */
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
            if (k > 0) past[k - 1].bytes += c.bytes // the merged step holds at most what both did
            dropped.add(c.state)
        }
        return dropped
    }

    fun peek(back: Boolean) = (if (back) past else future).lastOrNull()?.state

    // Separate from [peek]: history changes only after the target's pixels are loaded
    fun step(back: Boolean, now: Project) {
        val (from, to) = if (back) past to future else future to past
        val c = from.removeAt(from.lastIndex)
        to.add(Checkpoint(now, c.heavy, c.bytes))
    }

    fun clear(): List<Project> = snapshots.also { past.clear(); future.clear() }
}
