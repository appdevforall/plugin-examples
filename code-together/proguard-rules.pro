-keep class com.appdevforall.pair.plugin.PairPlugin { *; }
-keep class com.itsaky.androidide.plugins.** { *; }
-keep class org.java_websocket.** { *; }
-keep class * implements com.itsaky.androidide.plugins.IPlugin { *; }
-keepclassmembers class * {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
