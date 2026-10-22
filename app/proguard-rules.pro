# Release builds are not minified (see app/build.gradle.kts), so no rules are needed today.
# If minification is turned on, keep the kotlinx.serialization serializers for the model classes:
#-keepattributes *Annotation*, InnerClasses
#-keepclassmembers class com.lull.app.model.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
#-keep,includedescriptorclasses class com.lull.app.model.**$$serializer { *; }
