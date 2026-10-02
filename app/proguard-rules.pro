# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.jlees.budgey.**$$serializer { *; }
-keepclassmembers class com.jlees.budgey.** { *** Companion; }
-keepclasseswithmembers class com.jlees.budgey.** { kotlinx.serialization.KSerializer serializer(...); }

# On-device AI (LiteRT-LM, used by Vision AI). Its native C++ code looks up Java classes, fields
# and methods by their exact names through JNI. R8 renames or removes them in release builds, so
# the native side can't find them and the app aborts as soon as a model loads or answers.
# Debug builds aren't minified, which is why only release builds crashed.
-keep class com.google.ai.edge.** { *; }
-keep interface com.google.ai.edge.** { *; }
-dontwarn com.google.ai.edge.**

# Gemini Nano (ML Kit GenAI) — same reason; it also talks to Android's AICore service.
-keep class com.google.mlkit.genai.** { *; }
-dontwarn com.google.mlkit.genai.**

# Any class with native methods keeps those methods' names.
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
