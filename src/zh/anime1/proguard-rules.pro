# opencc4j and its heaven utilities load dictionaries and implementations reflectively.
-keep class com.github.houbb.** { *; }
-dontwarn com.github.houbb.**

# Referenced by opencc4j/heaven code paths this extension never uses:
# JDK-only APIs and the optional jieba segmenter.
-dontwarn sun.misc.**
-dontwarn java.lang.management.**
-dontwarn java.beans.**
-dontwarn java.awt.**
-dontwarn com.huaban.analysis.jieba.**
