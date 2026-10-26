package io.github.tensaiapp.bench

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Saved bench runs of one kind, one JSON file each in [dir] (`filesDir/bench/`) named `[prefix]<startedAtMs>.json`,
 * debug builds only. [runs] is newest first, `null` until [refresh] has read the folder. Blocking file IO: call off the
 * main thread. [refresh], [save] and [delete] take turns, so a listing read before a save never lands after it. [warn]
 * gets what [refresh] skipped. Files of the other kinds sit in the same folder: only [prefix]'s are read.
 */
open class RunFileStore<T>(
    private val dir: File,
    private val prefix: String,
    private val encode: (T) -> String,
    private val decode: (String) -> T,
    private val idOf: (T) -> String,
    private val startedAtOf: (T) -> Long,
    private val warn: (String) -> Unit,
) {
    private val _runs = MutableStateFlow<List<T>?>(null)
    val runs: StateFlow<List<T>?> = _runs.asStateFlow()

    /** Reads every run again. A file that does not parse is skipped and logged, never deleted. */
    @Synchronized
    fun refresh() {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(Suffix) }.orEmpty()
        _runs.value = files.mapNotNull { file ->
            runCatching { decode(file.readText()) }
                .onFailure { warn("skipped ${file.name}: ${it.message}") }
                .getOrNull()
        }.sortedByDescending(startedAtOf)
    }

    /** Writes [run] (to a temporary file first, so a crash never leaves half a run) and adds it to [runs], or replaces it. */
    @Synchronized
    fun save(run: T) {
        dir.mkdirs()
        val id = idOf(run)
        val tmp = File(dir, id + Suffix + ".tmp")
        tmp.writeText(encode(run))
        if (!tmp.renameTo(fileOf(id))) {
            tmp.delete()
            error("could not write $id")
        }
        if (_runs.value == null) refresh() else _runs.value = (listOf(run) + _runs.value.orEmpty().filter { idOf(it) != id }).sortedByDescending(startedAtOf)
    }

    @Synchronized
    fun delete(id: String) {
        fileOf(id).delete()
        _runs.value = _runs.value?.filter { idOf(it) != id }
    }

    private fun fileOf(id: String) = File(dir, id + Suffix)

    internal companion object {
        const val TAG = "tensai.bench"
        private const val Suffix = ".json"

        fun benchDir(context: Context) = File(context.applicationContext.filesDir, "bench")
    }
}

/** The saved agent bench runs (TEN-187), `run-<startedAtMs>.json` ([BenchRun.idFor]). */
class BenchRunStore(dir: File, warn: (String) -> Unit = { Log.w(TAG, it) }) :
    RunFileStore<BenchRun>(dir, "run-", BenchRunJson::encode, BenchRunJson::decode, { it.id }, { it.startedAtMs }, warn) {
    companion object {
        @Volatile
        private var instance: BenchRunStore? = null

        /** The app's store, in `filesDir/bench/`. */
        fun of(context: Context): BenchRunStore = instance ?: synchronized(this) {
            instance ?: BenchRunStore(benchDir(context)).also { instance = it }
        }
    }
}

/** The saved decode curve runs (TEN-190), `decode-<startedAtMs>.json` ([DecodeRun.idFor]), next to the agent runs. */
class DecodeRunStore(dir: File, warn: (String) -> Unit = { Log.w(TAG, it) }) :
    RunFileStore<DecodeRun>(dir, DecodeRun.Prefix, DecodeRunJson::encode, DecodeRunJson::decode, { it.id }, { it.startedAtMs }, warn) {
    companion object {
        @Volatile
        private var instance: DecodeRunStore? = null

        /** The app's store, in `filesDir/bench/`. */
        fun of(context: Context): DecodeRunStore = instance ?: synchronized(this) {
            instance ?: DecodeRunStore(benchDir(context)).also { instance = it }
        }
    }
}
