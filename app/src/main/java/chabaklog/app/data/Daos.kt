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

    /** 아침 요약용: 가장 최근에 끝난 차박 또는 확인이 필요한 기록 (진행 중·휴식은 제외) */
    @Query("SELECT * FROM sessions WHERE endedAtMs IS NOT NULL AND verdict IN ('CAMPING', 'ASK') ORDER BY startedAtMs DESC LIMIT 1")
    fun observeLatestEnded(): Flow<SessionEntity?>

    /** 아침 요약에서 "어젯밤 차박이었나요?"에 답했을 때 */
    @Query("UPDATE sessions SET verdict = :verdict WHERE id = :id")
    suspend fun setVerdict(id: Long, verdict: String)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE endedAtMs IS NULL ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun openSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE placeId = :placeId ORDER BY startedAtMs DESC")
    fun observeForPlace(placeId: Long): Flow<List<SessionEntity>>

    /** 예측용: 차박으로 확정된 실제 기록만 (시연용·휴식·확인 전 제외) */
    @Query("SELECT * FROM sessions WHERE endedAtMs IS NOT NULL AND isDemo = 0 AND verdict = 'CAMPING'")
    suspend fun realFinished(): List<SessionEntity>

    /** 이 장소에서 차박으로 확정된 다른 기록 수 (끝날 때 "아는 차박지"인지 판단) */
    @Query("SELECT COUNT(*) FROM sessions WHERE placeId = :placeId AND verdict = 'CAMPING' AND id != :exceptId")
    suspend fun campingCountAt(placeId: Long, exceptId: Long): Int

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
