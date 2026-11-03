package chabaklog.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun fromKind(kind: PlaceKind): String = kind.name
    @TypeConverter fun toKind(value: String): PlaceKind = PlaceKind.valueOf(value)
}

/** 로컬 DB. 네트워크 없이 전 기능이 동작하도록 기록·장소를 모두 기기에 저장한다. */
@Database(entities = [SessionEntity::class, PlaceEntity::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class ChabaklogDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun places(): PlaceDao

    companion object {
        /**
         * 2 → 3: 최종 판단(verdict)과 차박 모드(manual) 추가.
         * 기존 기록은 지금까지처럼 차박으로 보고(CAMPING) 그대로 남긴다.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN verdict TEXT NOT NULL DEFAULT 'CAMPING'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN manual INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 3 → 4: 기록 중 마지막 저장 시각(lastSeenAtMs). 기존 기록은 비워 둔다. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN lastSeenAtMs INTEGER")
            }
        }

        /** 4 → 5: 여정(주행거리·출발 시각·들른 곳) */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN tripDistanceKm REAL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN tripStartedAtMs INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN tripStops TEXT")
            }
        }

        fun create(context: Context): ChabaklogDatabase =
            Room.databaseBuilder(context, ChabaklogDatabase::class.java, "chabaklog.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // 마이그레이션이 없는 더 오래된 개발용 스키마(1)는 지우고 새로 만든다.
                .fallbackToDestructiveMigration()
                .build()
    }
}
