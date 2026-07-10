-keepattributes *Annotation*
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}
-keep class com.radar.app.** { *; }
