# JNI contract with llama-kt (third_party/llama.kt/llama-kt/src/main/cpp/tensai_jni.cpp).
# llama-kt ships no consumer rules, so they live here.

# Native entry points are bound by name (Java_com_tensai_llamakt_LlamaEngine_*): the class and
# its external methods must keep their names.
-keep class com.tensai.llamakt.LlamaEngine {
    native <methods>;
}

# Callbacks the C++ side resolves with GetMethodID(GetObjectClass(cb), name, signature):
# onToken, onChatParse, onProgress and onStage (tensai_jni.cpp, search GetMethodID). Implementations are
# lambdas/synthetic classes, so R8 would otherwise rename, inline or drop these methods.
-keep interface com.tensai.llamakt.TokenCallback { *; }
-keep interface com.tensai.llamakt.ChatParseCallback { *; }
-keep interface com.tensai.llamakt.LoadProgressCallback { *; }
