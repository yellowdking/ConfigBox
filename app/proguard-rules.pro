# Keep SnakeYAML & Kotlinx Serialization models
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class com.configbox.app.model.** { *; }
-dontwarn org.yaml.snakeyaml.**
