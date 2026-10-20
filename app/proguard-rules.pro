# GitPocket R8 规则
# 保留 Compose 运行时所需的元数据（AGP 与 Compose 已内置大部分规则，这里只补项目自有部分）

# 数据模型仅在本应用内使用，R8 可正常裁剪；保留行号便于崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room 生成的实现类通过反射加载
-keep class app.gitpocket.core.database.**_Impl { *; }
-dontwarn app.gitpocket.core.database.**
