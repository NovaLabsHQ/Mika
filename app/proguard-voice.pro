# sherpa-onnx(com.k2fsa.sherpa.onnx):原生代码经 JNI 按名字读配置类的字段、构造结果类,类名、字段、方法都不能改名或删掉。
# 现在各构建类型都没打开 isMinifyEnabled,这些规则先备着。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
