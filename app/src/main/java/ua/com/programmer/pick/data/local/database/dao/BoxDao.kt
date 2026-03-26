package ua.com.programmer.pick.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import ua.com.programmer.pick.data.local.database.entity.BoxEntity

@Dao
interface BoxDao {

    @Query("SELECT * FROM boxes WHERE id = :id")
    suspend fun getBoxById(id: String): BoxEntity?

    @Query("SELECT * FROM boxes WHERE barcode = :barcode")
    suspend fun getBoxByBarcode(barcode: String): BoxEntity?

    @Query("SELECT * FROM boxes WHERE is_active = 1")
    fun getAllActiveBoxes(): Flow<List<BoxEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBox(box: BoxEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBoxes(boxes: List<BoxEntity>)

    @Query("DELETE FROM boxes WHERE id IN (:ids)")
    suspend fun deleteBoxesByIds(ids: List<String>)

    @Query("DELETE FROM boxes")
    suspend fun deleteAllBoxes()
}
