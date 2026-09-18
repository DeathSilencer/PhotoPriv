package com.david.photopriv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.david.photopriv.data.dao.PhotoDao
import com.david.photopriv.data.model.ExtractionSession
import com.david.photopriv.data.model.TrackedPhoto

@Database(
    entities = [ExtractionSession::class, TrackedPhoto::class],
    version = 2,
    exportSchema = false
)
abstract class PhotoPrivDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var INSTANCE: PhotoPrivDatabase? = null

        fun getDatabase(context: Context): PhotoPrivDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PhotoPrivDatabase::class.java,
                    "photopriv_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
