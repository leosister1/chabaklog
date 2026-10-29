package chabaklog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Update
    suspend fun update(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC LIMIT 1")
    fun observeLatest(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE endedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun openSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE placeId = :placeId ORDER BY startedAtMs DESC")
    fun observeForPlace(placeId: Long): Flow<List<SessionEntity>>
}

@Dao
interface PlaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(places: List<PlaceEntity>)

    @Insert
    suspend fun insert(place: PlaceEntity): Long

    @Query("SELECT * FROM places ORDER BY name")
    fun observeAll(): Flow<List<PlaceEntity>>

    @Query("SELECT COUNT(*) FROM places")
    suspend fun count(): Int
}
