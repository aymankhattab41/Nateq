// تعطيل مدقّق واصفات الملفات (fdsan) لهذه العملية.
//
// libNuanceVocalizer.so يفتح بعض تدفقات بيانات الصوت ثم يغلق واصفاً
// مملوكاً لـ FILE* بـ close() خام (CVocalizerEngine::vplatform_file_Close
// عبر tts_ve_cache_datastream_Close)؛ وهو نمط لا يتوافق مع fdsan المفعّل
// افتراضياً للتطبيقات التي تستهدف API 29+. النتيجة تحت المستهدف الحديث
// إجهاض العملية بـ fdsan_error/SIGABRT رغم نجاح تحميل الصوت.
//
// تعطيل fdsan يُعيد السلوك القديم (ما قبل أندرويد 10) فيُترك المحرك
// القديم يكمل تحميل الصوت المدمج.
//
// android_fdsan_set_error_level رمز ضعيف في libc أُضيف في API 29؛ نُحلّه
// ديناميكياً حتى لا يفشل التحميل على الأجهزة الأقدم (minSdk 24) حيث
// الرمز غير موجود أصلاً.

#include <jni.h>
#include <dlfcn.h>

namespace {

// ANDROID_FDSAN_ERROR_LEVEL_DISABLED == 0
constexpr int kFdsanErrorLevelDisabled = 0;

void disableFdsan() {
    void* libc = dlopen("libc.so", RTLD_NOW | RTLD_LOCAL);
    if (libc == nullptr) {
        return;
    }
    using SetErrorLevelFn = int (*)(int);
    auto setErrorLevel = reinterpret_cast<SetErrorLevelFn>(
        dlsym(libc, "android_fdsan_set_error_level"));
    if (setErrorLevel != nullptr) {
        setErrorLevel(kFdsanErrorLevelDisabled);
    }
    // لا نُغلق libc (محمّلة دائماً) تجنّباً لإبطال الرمز المُحلَّل.
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_aymankhattab_nateq_core_audio_enginehost_NativeFdsanGuard_disableFdsan(
    JNIEnv* /* env */, jobject /* thiz */) {
    disableFdsan();
}
