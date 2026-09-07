package com.appdevforall.contractor.plugin.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.appdevforall.contractor.plugin.data.db.dao.InvoiceDao
import com.appdevforall.contractor.plugin.data.db.dao.ProjectDao
import com.appdevforall.contractor.plugin.data.db.dao.SessionDao
import com.appdevforall.contractor.plugin.data.db.entities.InvoiceEntity
import com.appdevforall.contractor.plugin.data.db.entities.TrackedProjectEntity
import com.appdevforall.contractor.plugin.data.db.entities.WorkSessionEntity
import java.io.File

@Database(
    entities = [
        TrackedProjectEntity::class,
        WorkSessionEntity::class,
        InvoiceEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class ContractorDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun sessionDao(): SessionDao
    abstract fun invoiceDao(): InvoiceDao

    companion object {
        private const val DB_FILE = "contractor.db"

        fun build(androidContext: Context, dataDir: File): ContractorDatabase {
            if (!dataDir.exists()) dataDir.mkdirs()
            val dbFile = File(dataDir, DB_FILE)
            return Room.databaseBuilder(
                androidContext,
                ContractorDatabase::class.java,
                dbFile.absolutePath
            )
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
