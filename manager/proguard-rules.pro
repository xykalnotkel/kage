# The server talks to these classes through reflection / Binder, keep them intact.
-keep class dev.kage.common.** { *; }
-keep class dev.kage.provider.** { *; }
-keep class dev.kage.server.** { *; }

# Parcelables travelling through Binder
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Views inflated from XML
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# The app talks to the server with plain Parcel transactions
-dontwarn org.jetbrains.annotations.**
-keep class androidx.appcompat.** { *; }
