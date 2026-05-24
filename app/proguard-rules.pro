# ---------------------------------------------------------------------------
# JNI bridges. C++ resolves Java_<class>_<method> by name and calls Kotlin
# callbacks via GetMethodID, so the involved classes/members must survive R8.
# ---------------------------------------------------------------------------

# Any class that declares native methods (keeps the method names too).
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Our llama.cpp bridge + the streaming callback invoked from C++.
-keep class com.wikillm.android.llm.LlamaContext { *; }
-keep class com.wikillm.android.llm.LlamaContext$Companion { *; }
-keep interface com.wikillm.android.llm.TokenCallback { *; }
-keep class * implements com.wikillm.android.llm.TokenCallback { *; }

# libzim / libkiwix Java bindings are driven entirely by their *_wrapper.so via
# JNI (RegisterNatives + reflective callbacks), so keep them wholesale.
-keep class org.kiwix.** { *; }
-keepclasseswithmembernames class org.kiwix.** {
    native <methods>;
}

# ReLinker loads the .so files (incl. by name).
-keep class com.getkeepsafe.relinker.** { *; }

# ---------------------------------------------------------------------------
# kotlinx.serialization (chat history, HF/Kiwix catalog models).
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep generated serializers and serializer() accessors for our @Serializable types.
-keep,includedescriptorclasses class com.wikillm.android.**$$serializer { *; }
-keepclassmembers class com.wikillm.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.wikillm.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Misc
# ---------------------------------------------------------------------------
# Keep enum values()/valueOf() used reflectively by serialization.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
