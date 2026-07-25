package com.aura.profile

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [UserProfileEntity::class], version = 2, exportSchema = true)
abstract class UserProfileDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao
}
