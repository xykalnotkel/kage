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

# BouncyCastle (native wireless pairing: SPAKE2 group math + TLS with keying material export)
# everything is invoked directly, but BC uses some internal reflection for providers/oid tables
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.tls.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.jce.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
