package ua.com.programmer.pick.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ua.com.programmer.pick.data.local.database.AppDatabase
import ua.com.programmer.pick.data.local.database.dao.ClientDao
import ua.com.programmer.pick.data.local.database.dao.ProductDao
import ua.com.programmer.pick.data.local.database.dao.SyncStateDao
import ua.com.programmer.pick.data.local.database.dao.UserDao
import ua.com.programmer.pick.data.local.database.dao.WarehouseDao
import ua.com.programmer.pick.data.local.database.entity.UserEntity
import java.security.MessageDigest
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Volatile
    private var INSTANCE: AppDatabase? = null

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(
                context,
                AppDatabase::class.java,
                AppDatabase.DATABASE_NAME
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Insert demo user on database creation
                        CoroutineScope(Dispatchers.IO).launch {
                            INSTANCE?.userDao()?.insertUser(createDemoUser())
                        }
                    }
                })
                .build()
            INSTANCE = instance
            instance
        }
    }

    private fun createDemoUser(): UserEntity {
        // Hash "demo" password with "demo" login as salt
        val password = "demo"
        val login = "demo"
        val input = password + login
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        val passwordHash = bytes.joinToString("") { "%02x".format(it) }

        return UserEntity(
            id = "demo_user_001",
            login = "demo",
            name = "Demo User",
            passwordHash = passwordHash,
            role = "ADMINISTRATOR",
            isActive = true,
            lastLoginAt = null,
            lastUpdated = System.currentTimeMillis()
        )
    }

    @Provides
    @Singleton
    fun provideUserDao(database: AppDatabase): UserDao = database.userDao()

    @Provides
    @Singleton
    fun provideSyncStateDao(database: AppDatabase): SyncStateDao = database.syncStateDao()

    @Provides
    @Singleton
    fun provideProductDao(database: AppDatabase): ProductDao = database.productDao()

    @Provides
    @Singleton
    fun provideClientDao(database: AppDatabase): ClientDao = database.clientDao()

    @Provides
    @Singleton
    fun provideWarehouseDao(database: AppDatabase): WarehouseDao = database.warehouseDao()
}
