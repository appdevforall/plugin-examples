# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Keep plugin main class
-keep class * implements com.itsaky.androidide.plugins.api.IPlugin { *; }

# Keep all extension implementations
-keep class * implements com.itsaky.androidide.plugins.api.extensions.UIExtension { *; }
-keep class * implements com.itsaky.androidide.plugins.api.extensions.EditorTabExtension { *; }
-keep class * implements com.itsaky.androidide.plugins.api.extensions.DocumentationExtension { *; }
-keep class * implements com.itsaky.androidide.plugins.api.extensions.EditorExtension { *; }
-keep class * implements com.itsaky.androidide.plugins.api.extensions.ProjectExtension { *; }

# Keep Fragment classes
-keep class * extends androidx.fragment.app.Fragment { *; }