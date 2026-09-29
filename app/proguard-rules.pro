# bugly
# https://bugly.qq.com/docs/user-guide/instruction-manual-android/
-keep public class com.tencent.bugly.** { *; }
-dontwarn com.tencent.bugly.**

# smbj
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn net.engio.mbassy.**
-dontwarn javax.el.**
-keepclassmembers,allowshrinking,allowobfuscation class com.hierynomus.msdfsc.ReferralCache$ReferralCacheNode {
    static final java.util.concurrent.atomic.AtomicReferenceFieldUpdater ENTRY_UPDATER;
}

-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}

-keep class net.engio.mbassy.dispatch.HandlerInvocation { *; }
-keep class net.engio.mbassy.dispatch.ReflectiveHandlerInvocation { *; }
-keep class net.engio.mbassy.subscription.SubscriptionContext { *; }
-keepclassmembers class * extends net.engio.mbassy.dispatch.HandlerInvocation {
    <init>(net.engio.mbassy.subscription.SubscriptionContext);
}

-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }

# 修复Android5.0 VerifyError
-keepclassmembers class androidx.compose.ui.platform.** { *; }

# Retrofit：API 接口只通过动态代理使用，R8 看不到调用者，
# 会误删接口方法（甚至判定接口无用），导致 release 下 Retrofit.create() 抛 ClassCastException
-keep,allowobfuscation interface remix.myplayer.request.network.GithubApi { *; }
-keep,allowobfuscation interface remix.myplayer.request.network.LastFMApi { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
