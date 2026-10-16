# GameAgent release rules.
#
# The app is deliberately dependency-light, so almost nothing needs keeping. The rules below only
# guard the pieces the platform instantiates by name.

# Components referenced from AndroidManifest.xml.
-keep class com.gameagent.app.GameAgentApplication { *; }
-keep class com.gameagent.app.ui.MainActivity { *; }
-keep class com.gameagent.app.capture.ScreenCaptureService { *; }
-keep class com.gameagent.app.input.GameAgentAccessibilityService { *; }

# Accessibility services are looked up by the platform and their configuration is read via
# reflection on the manifest meta-data, so keep the whole class and its attributes.
-keepattributes *Annotation*

# No reflection is used for the core pipeline or the JSON codec, so obfuscation of those is fine.
# Keeping the model classes readable makes crash reports interpretable.
-keepnames class com.gameagent.core.model.** { *; }

# AndroidX Compose / lifecycle already ship consumer rules; avoid duplicate warnings.
-dontwarn org.jetbrains.annotations.**
