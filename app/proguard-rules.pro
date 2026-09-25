# 离线智能体：默认关闭混淆以便调试。若放开 minify，注意保留序列化数据类与 JNI 类。
-keep class com.offlineagent.llm.LlamaJni { *; }
-keep class com.offlineagent.core.Action { *; }
-keep class com.offlineagent.core.UiSnapshot { *; }
-keepattributes *Annotation*, Signature
