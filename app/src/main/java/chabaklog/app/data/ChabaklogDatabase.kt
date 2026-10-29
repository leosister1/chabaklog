package chabaklog.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun fromKind(kind: PlaceKind): String = kind.name
    @TypeConverter fun toKind(value: String): PlaceKind = PlaceKind.valueOf(value)
}

/** 로컬 DB. 네트워크 없이 전 기능이 동작하도록 기록·장소를 모두 기기에 저장한다. */
@Database(entities = [SessionEntity::class, PlaceEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class ChabaklogDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun places(): PlaceDao

    companion object {
        fun create(context: Context): ChabaklogDatabase =
            Room.databaseBuilder(context, ChabaklogDatabase::class.java, "chabaklog.db")
                // 개발 중(제출 전)이라 스키마가 바뀌면 지우고 새로 만든다. 제출본부터는 마이그레이션을 쓴다.
                .fallbackToDestructiveMigration()
                .build()
    }
}
