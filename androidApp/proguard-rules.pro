# Keep rules for what R8 can't see. Libraries ship their own consumer rules; add a rule here only for
# something that breaks in a release build, with a note on why.

# ONNX Runtime's native code resolves ai.onnxruntime.* classes, methods and fields by name through
# JNI FindClass/GetMethodID. The AAR's consumer rules keep only the types with native methods, so R8
# still renames the rest (OrtException, OrtUtil, OrtSession$Result, …). When OrtSession.run then looks
# one up it gets null and ART aborts the process (SIGABRT, "JNI DETECTED ERROR: java_class == null")
# mid-embedding. Keep the whole package, members included.
-keep class ai.onnxruntime.** { *; }
