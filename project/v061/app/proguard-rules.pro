# Whisper JNI exports are resolved by symbol name from whisper_jni.c.
-keep class com.voicedock.ai.voice.WhisperNative { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Jakarta/JavaMail 1.6 uses META-INF provider/address-map files and reflective class loading.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }

# PDFBox-Android loads fonts, CMaps and parser helpers reflectively; retain classes and resource names.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class org.apache.pdfbox.** { *; }
-keep class org.apache.fontbox.** { *; }

# Keep useful obfuscation metadata in the mapping for actionable release crash traces.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
