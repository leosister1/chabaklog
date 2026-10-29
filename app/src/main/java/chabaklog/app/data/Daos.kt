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

    /** 아침 요약용: 가장 최근에 "끝난" 세션 (진행 중인 세션은 제외) */
    @Query("SELECT * FROM sessions WHERE endedAtMs IS NOT NULL ORDER BY startedAtMs DESC LIMIT 1")
    fun observeLatestEnded(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE endedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun openSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE placeId = :placeId ORDER BY startedAtMs DESC")
    fun observeForPlace(placeId: Long): Flow<List<SessionEntity>>

    /** 예측용: 끝난 실제 기록만 (시연용 제외) */
    @Query("SELECT * FROM sessions WHERE endedAtMs IS NOT NULL AND isDemo = 0")
    suspend fun realFinished(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE isDemo = 1")
    suspend fun deleteDemo()
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

    @Query("SELECT * FROM places")
    suspend fun all(): List<PlaceEntity>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun byId(id: Long): PlaceEntity?
}
