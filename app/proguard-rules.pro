# Room Database
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class com.david.photopriv.data.model.** { *; }
-keep class com.david.photopriv.data.database.** { *; }
-dontwarn androidx.room.paging.**

# WorkManager
-keep class androidx.work.impl.WorkDatabase_Impl {
    public <init>();
}
-keep class * extends androidx.work.impl.WorkDatabase {
    public <init>();
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# JavaMail
-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-dontwarn javax.mail.**
-dontwarn com.sun.mail.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**