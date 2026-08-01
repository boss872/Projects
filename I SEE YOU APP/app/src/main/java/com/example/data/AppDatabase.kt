package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Hospital::class,
        IcuInventory::class,
        Booking::class,
        HospitalStaffAccount::class,
        UserAccount::class,
        SearchedLocation::class,
        Payment::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hospitalDao(): HospitalDao
    abstract fun icuInventoryDao(): IcuInventoryDao
    abstract fun bookingDao(): BookingDao
    abstract fun hospitalStaffAccountDao(): HospitalStaffAccountDao
    abstract fun userAccountDao(): UserAccountDao
    abstract fun searchedLocationDao(): SearchedLocationDao
    abstract fun paymentDao(): PaymentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "icu_bed_finder_database"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
