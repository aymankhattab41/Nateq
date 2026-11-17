# مكتبة JNI صغيرة لتعطيل مدقّق واصفات الملفات (fdsan) داخل عملية
# المحرك :engine_host.
#
# libNuanceVocalizer.so يفتح بعض تدفقات البيانات ثم يغلق واصفاً مملوكاً
# لـ FILE* بـ close() خام، وهو نمط لا يتوافق مع fdsan (مفعّل افتراضياً
# لمن يستهدف API 29+). النداء يُعيد المستوى إلى DISABLED فلا تُجهض
# العملية أثناء تحميل الصوت المدمج.
cmake_minimum_required(VERSION 3.22.1)

project(nateqfdsan CXX)

add_library(nateqfdsan SHARED fdsan_fix.cpp)

find_library(log-lib log)

target_link_libraries(nateqfdsan ${log-lib} dl)
