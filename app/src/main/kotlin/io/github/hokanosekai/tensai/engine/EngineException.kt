package io.github.hokanosekai.tensai.engine

/**
 * Base class for exceptions surfaced by [InferenceEngine] implementations.
 *
 * Engines must never let a raw native/JNI exception escape to callers —
 * everything crossing the engine boundary is wrapped into one of the
 * subclasses below, with the original exception preserved as [cause].
 */
open class EngineException(message: String?, cause: Throwable? = null) : Exception(message, cause)

/** A model failed to load (bad path, corrupt/unsupported file, native load aborted, …). */
class ModelLoadException(message: String?, cause: Throwable? = null) : EngineException(message, cause)

/** A decode (completion) request failed after the model was successfully loaded. */
class InferenceException(message: String?, cause: Throwable? = null) : EngineException(message, cause)

/**
 * Best-effort out-of-memory signal.
 *
 * Only JVM-heap [OutOfMemoryError]s raised while bridging to the native
 * layer (e.g. oversized JNI string/array marshalling) are detected here.
 * Native (mmap/allocator) OOM on the native side typically kills the
 * process outright and cannot be caught from the JVM.
 */
class OutOfMemoryException(message: String?, cause: Throwable? = null) : EngineException(message, cause)
