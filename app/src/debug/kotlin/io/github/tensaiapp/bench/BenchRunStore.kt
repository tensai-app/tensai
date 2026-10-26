package io.github.tensaiapp.bench

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The saved agent bench runs, one [BenchRunJson] file each in [dir] (`filesDir/bench/`), debug builds only. [runs] is
 * newest first, `null` until [refresh] has read the folder. Blocking file IO: call off the main thread. [refresh],
 * [save] and [delete] take turns, so a listing read before a save never lands after it. [warn] gets what [refresh]
 * skipped.
 */
class BenchRunStore(private val dir: File, private val warn: (String) -> Unit = { Log.w(TAG, it) }) {
    private val _runs = MutableStateFlow<List<BenchRun>?>(null)
    val runs: StateFlow<List<BenchRun>?> = _runs.asStateFlow()

    /** Reads every run again. A file that does not parse is skipped and logged, never deleted. */
    @Synchronized
    fun refresh() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(Suffix) }.orEmpty()
        _runs.value = files.mapNotNull { file ->
            runCatching { BenchRunJson.decode(file.readText()) }
                .onFailure { warn("skipped ${file.name}: ${it.message}") }
                .getOrNull()
        }.sortedByDescending { it.startedAtMs }
    }

    /** Writes [run] (to a temporary file first, so a crash never leaves half a run) and adds it to [runs]. */
    @Synchronized
    fun save(run: BenchRun) {
        dir.mkdirs()
        val tmp = File(dir, run.id + Suffix + ".tmp")
        tmp.writeText(BenchRunJson.encode(run))
        if (!tmp.renameTo(fileOf(run.id))) {
            tmp.delete()
            error("could not write ${run.id}")
        }
        if (_runs.value == null) refresh() else _runs.value = (listOf(run) + _runs.value.orEmpty().filter { it.id != run.id }).sortedByDescending { it.startedAtMs }
    }

    @Synchronized
    fun delete(id: String) {
        fileOf(id).delete()
        _runs.value = _runs.value?.filter { it.id != id }
    }

    private fun fileOf(id: String) = File(dir, id + Suffix)

    companion object {
        private const val TAG = "tensai.bench"
        private const val Suffix = ".json"

        @Volatile
        private var instance: BenchRunStore? = null

        /** The app's store, in `filesDir/bench/`. */
        fun of(context: Context): BenchRunStore = instance ?: synchronized(this) {
            instance ?: BenchRunStore(File(context.applicationContext.filesDir, "bench")).also { instance = it }
        }
    }
}
