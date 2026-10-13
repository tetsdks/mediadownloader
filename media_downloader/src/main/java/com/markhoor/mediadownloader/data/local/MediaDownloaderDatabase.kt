package com.markhoor.mediadownloader.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.data.network.HttpClientFactory
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The module's own database, apart from the host's.
 *
 * **A schema change starts the table again: the rows an older version wrote are dropped.** The
 * library is published to apps the module's author does not ship, so a migration written here has
 * to be right for every version of every one of them, for ever, and a migration that is wrong
 * crashes those apps on launch with nothing the host can do. Starting again cannot do that. What
 * it costs is the downloads list on the upgrade that changes the schema - rows only, since the
 * files that were finished are already published to the gallery and are not the module's to lose;
 * a download still running is forgotten, and the scratch files it left are swept up at the next
 * start (`restoreUnfinished`). The version is only ever raised, so an app that installs an older
 * build over a newer one is started again too rather than refusing to open.
 *
 * Nothing here is cached state: the rows are what the user asked for, so a schema change should
 * still be rare and worth the list.
 */
@Database(entities = [DownloadEntity::class], version = 2, exportSchema = false)
@TypeConverters(DownloadConverters::class)
internal abstract class MediaDownloaderDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    companion object {
        fun create(context: Context): MediaDownloaderDatabase =
            Room.databaseBuilder(context, MediaDownloaderDatabase::class.java, Download.DATABASE_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}

/** Enums are stored by name; headers as a JSON object. */
internal class DownloadConverters {

    private val headersSerializer = MapSerializer(String.serializer(), String.serializer())

    @TypeConverter
    fun headersToText(headers: Map<String, String>): String =
        HttpClientFactory.json.encodeToString(headersSerializer, headers)

    @TypeConverter
    fun textToHeaders(text: String): Map<String, String> =
        runCatching { HttpClientFactory.json.decodeFromString(headersSerializer, text) }.getOrDefault(emptyMap())
}
