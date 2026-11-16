# Firestore documents and Cloud Function results are decoded by field name
# with kotlinx.serialization, so the model classes keep their names.
-keep class com.daat.hitlist.model.** { *; }
-keepclassmembers class com.daat.hitlist.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn org.slf4j.**
