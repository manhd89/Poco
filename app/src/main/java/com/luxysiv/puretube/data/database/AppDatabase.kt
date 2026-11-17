package com.luxysiv.puretube.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        VideoEntity::class,
        PlaylistHistoryEntity::class,
        SavedPlaylistEntity::class,
        SubscriptionEntity::class,
        SubscriptionVideoEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao

    abstract fun playlistHistoryDao(): PlaylistHistoryDao

    abstract fun savedPlaylistDao(): SavedPlaylistDao

    abstract fun subscriptionDao(): SubscriptionDao

    abstract fun subscriptionVideoDao(): SubscriptionVideoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 -> v2: thêm thời điểm lưu Yêu thích / Xem sau để sort đúng.
         * Giữ nguyên dữ liệu người dùng (không dùng destructive migration nữa).
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE saved_videos ADD COLUMN favoriteAddedAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE saved_videos ADD COLUMN watchLaterAddedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v2 -> v3: lưu avatar kênh để thẻ video trong Lịch sử/Yêu thích hiện đúng. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE saved_videos ADD COLUMN channelAvatarUrl TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v3 -> v4: thêm lịch sử playlist để mở lại danh sách và resume đúng
         * video đang xem dở. Bảng mới nên dữ liệu video hiện có giữ nguyên.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playlist_history (
                        playlistId TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        channelTitle TEXT NOT NULL,
                        channelId TEXT NOT NULL,
                        thumbnailUrl TEXT NOT NULL,
                        videoCount INTEGER NOT NULL,
                        lastVideoId TEXT NOT NULL,
                        lastVideoTitle TEXT NOT NULL,
                        lastPositionSeconds INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v4 -> v5: thêm bảng playlist đã lưu để tách khỏi video trong Xem sau.
         * Bảng mới nên lịch sử và video hiện có giữ nguyên.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS saved_playlists (
                        playlistId TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        channelTitle TEXT NOT NULL,
                        channelId TEXT NOT NULL,
                        thumbnailUrl TEXT NOT NULL,
                        videoCount INTEGER NOT NULL,
                        firstVideoId TEXT NOT NULL,
                        firstVideoTitle TEXT NOT NULL,
                        savedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v5 -> v6: thêm Đăng ký cục bộ.
         *
         * `subscriptions` là danh sách kênh người dùng theo dõi (nguồn sự thật);
         * `subscription_videos` là bộ đệm feed mới nhất của từng kênh, tách
         * riêng để mở lại tab Đăng ký không phải gọi mạng và vẫn xem được khi
         * ngoại tuyến. Hai bảng mới nên dữ liệu thư viện hiện có giữ nguyên.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subscriptions (
                        channelId TEXT NOT NULL PRIMARY KEY,
                        channelTitle TEXT NOT NULL,
                        avatarUrl TEXT NOT NULL,
                        subscriberCount INTEGER NOT NULL,
                        addedAt INTEGER NOT NULL,
                        lastFetchedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subscription_videos (
                        videoId TEXT NOT NULL PRIMARY KEY,
                        channelId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        thumbnailUrl TEXT NOT NULL,
                        publishedAt INTEGER NOT NULL,
                        viewCount TEXT NOT NULL,
                        duration TEXT NOT NULL,
                        fetchedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v6 -> v7: lưu continuation token của feed kênh đã đăng ký.
         *
         * Tab Đăng ký cuộn vô hạn bằng token này; cột mặc định rỗng nên kênh cũ
         * vẫn dùng được và sẽ nhận token ở lần tải feed kế tiếp.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE subscriptions ADD COLUMN nextPageToken TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "puretube_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
