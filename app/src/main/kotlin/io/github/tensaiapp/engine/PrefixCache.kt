package io.github.tensaiapp.engine

import java.io.File
import java.security.MessageDigest

/**
 * Files holding the KV state of a fixed prompt prefix (TEN-182): the system prompt and the tool
 * definitions, which every conversation of a model and tool set starts with. Computing them is
 * ~1100 tokens of prefill, minutes on a phone CPU, and is the same work each time the model has just
 * been loaded; a file turns it into one read.
 *
 * Only the bookkeeping lives here (names, atomic write, eviction), so it can be tested without a
 * model. What goes in a file, and when it is read, is [LlamaKtEngineImpl]'s.
 *
 * The key hashes the text of the prefix itself next to [identity], so a different tool set, template,
 * system prompt or tool description is a different file by construction: nothing can be restored
 * over a prompt it was not computed for. A stale file (same key, written by an older llama.cpp)
 * fails to load, which the caller treats as a miss and rewrites.
 *
 * Files are big (~110 KB per token on Qwen3-1.7B F16, so ~120 MB for a 1100-token prefix), hence
 * [keep]: the newest two, so the other tool set (or the last model) survives one switch, in a cache
 * directory the OS may also reclaim.
 */
internal class PrefixCache(private val dir: File, private val keep: Int = DefaultKeep) {

    init {
        // A kill mid-save leaves a .tmp that nothing else would ever remove. One engine thread per
        // process, so no save is in flight when this runs.
        dir.listFiles().orEmpty().filter { it.name.endsWith(TempSuffix) }.forEach { it.delete() }
    }

    /** Hex digest naming the file for [prefix] computed under [identity]. */
    fun key(identity: String, prefix: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(identity.toByteArray())
        digest.update(0)
        digest.update(prefix.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }.take(KeyLength)
    }

    fun fileFor(key: String): File = File(dir, "$key$Extension")

    /** Where a file is written before [commit] moves it into place, so a crash never leaves half a state under a real name. */
    fun tempFor(file: File): File = File(dir, file.name + TempSuffix).also { dir.mkdirs() }

    /** Moves [temp] over [file], then drops everything beyond the [keep] newest. False when it could not be moved. */
    fun commit(temp: File, file: File): Boolean {
        if (!temp.renameTo(file)) {
            temp.delete()
            return false
        }
        evict()
        return true
    }

    /** Marks [file] as the freshest, so eviction keeps it. */
    fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    fun discard(file: File) {
        file.delete()
    }

    private fun evict() {
        val all = dir.listFiles().orEmpty()
        all.filter { it.name.endsWith(TempSuffix) }.forEach { it.delete() }
        all.filter { it.name.endsWith(Extension) }
            .sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { it.delete() }
    }

    companion object {
        /**
         * What a prefix state is only valid for, next to the prefix text (see [key]): these shape the KV
         * cells, so a state computed under other values must never be read back. [kvCacheType] is the cache type
         * loaded, not the one asked for (see [VulkanTuning.kvForLoad]), and [tuning] counts only when it forces the
         * layout (every CPU load since TEN-188): a GPU load keeps its files.
         */
        fun identity(
            config: ModelLoadConfig,
            fileLength: Long,
            lastModified: Long,
            backend: String,
            kvCacheType: KvCacheType,
            tuning: VulkanLoadTuning,
        ): String = listOf(
            config.modelId, config.path, fileLength, lastModified,
            config.nCtx, kvCacheType, backend, config.chatTemplate,
        ).plus(listOfNotNull(tuning.kvLayoutKey())).joinToString("|")

        const val DefaultKeep = 2
        private const val KeyLength = 32
        private const val Extension = ".kv"
        private const val TempSuffix = ".tmp"
    }
}
