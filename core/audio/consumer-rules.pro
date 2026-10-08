# ============================================================
#  قواعد R8/ProGuard الموروثة من :core:audio
#  تُطبَّق تلقائياً على أي تطبيق مضيف يستهلك هذه الوحدة، فلا تُنسى
#  عند تغيير التطبيق المضيف أو إعادة تسميته.
# ============================================================

# مكتبات JNI: تصل لفئاتها وحقولها بالاسم — ممنوع التغيير أو الحذف.
# محرّك Vocalizer الأصلي (libNuanceVocalizer.so) يستدعي GetFieldID/
# SetLongField بأسماء حقول مكتوبة في سجلّه الداخلي (.rodata) مثل
# mNativeEnginePtr؛ إعادة التسمية تجعل fid == null وتُسقط العملية
# بـJNI DETECTED ERROR في createNativeEngine.
-keep class com.nuance.android.vocalizer.** { *; }
-keep class com.acapelagroup.android.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }

# كل دالة native يجب أن تحتفظ باسمها لأن النظام يطابقها بالتوقيع
# عند تسجيل JNI (RegisterNatives أو الافتراضي عبر اسم الصنف).
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# الحقول المُعلَّمة @Keep في هذا المشروع أو لدى المكتبات المضمّنة.
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}
