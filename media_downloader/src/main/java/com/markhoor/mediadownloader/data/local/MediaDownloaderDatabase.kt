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
 * The module's own database, apart from the host's. A schema change needs a migration here:
 * rows are the user's downloads, and wiping them loses track of files mid-download.
 */
@Database(entities = [DownloadEntity::class], version = 1, exportSchema = false)
@TypeConverters(DownloadConverters::class)
internal abstract class MediaDownloaderDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    companion object {
        fun create(context: Context): MediaDownloaderDatabase =
            Room.databaseBuilder(context, MediaDownloaderDatabase::class.java, Download.DATABASE_NAME).build()
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
