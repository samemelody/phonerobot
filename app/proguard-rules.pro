# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep LiteRT model classes
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class **.LiteRt** { *; }

# Keep LiteRT-LM (language model) classes
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.google.ai.edge.litertlm.Engine { *; }
-keep class com.google.ai.edge.litertlm.Conversation { *; }
-keep class com.google.ai.edge.litertlm.Message { *; }
-keep class com.google.ai.edge.litertlm.Contents { *; }
-keep class com.google.ai.edge.litertlm.Backend { *; }
-keep class com.google.ai.edge.litertlm.SamplerConfig { *; }
-keep class com.google.ai.edge.litertlm.EngineConfig { *; }
-keep class com.google.ai.edge.litertlm.ConversationConfig { *; }

# Keep JNI method names
-keepclasswithmembernames class * {
    native <methods>;
}
