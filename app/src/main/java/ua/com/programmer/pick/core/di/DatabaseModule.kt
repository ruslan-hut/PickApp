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
import ua.com.programmer.pick.data.local.database.dao.DocumentDao
import ua.com.programmer.pick.data.local.database.dao.DocumentLineDao
import ua.com.programmer.pick.data.local.database.dao.OutgoingOperationDao
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
                        insertDemoUser(db)
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // Ensure demo user exists (in case onCreate wasn't called)
                        val cursor = db.query("SELECT COUNT(*) FROM users WHERE login = 'demo'")
                        cursor.moveToFirst()
                        val count = cursor.getInt(0)
                        cursor.close()
                        if (count == 0) {
                            insertDemoUser(db)
                        }
                    }

                    private fun insertDemoUser(db: SupportSQLiteDatabase) {
                        val demoUser = createDemoUser()
                        db.execSQL(
                            """
                            INSERT OR REPLACE INTO users (id, login, name, password_hash, role, is_active, last_login_at, last_updated)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf(
                                demoUser.id,
                                demoUser.login,
                                demoUser.name,
                                demoUser.passwordHash,
                                demoUser.role,
                                if (demoUser.isActive) 1 else 0,
                                demoUser.lastLoginAt,
                                demoUser.lastUpdated
                            )
                        )
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

    @Provides
    @Singleton
    fun provideDocumentDao(database: AppDatabase): DocumentDao = database.documentDao()

    @Provides
    @Singleton
    fun provideDocumentLineDao(database: AppDatabase): DocumentLineDao = database.documentLineDao()

    @Provides
    @Singleton
    fun provideOutgoingOperationDao(database: AppDatabase): OutgoingOperationDao = database.outgoingOperationDao()
}
