package ch.deletescape.lawnchair.backup

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.AsyncTask
import android.os.Environment
import android.util.Log
import com.android.launcher3.LauncherFiles
import com.android.launcher3.Utilities
import org.json.JSONArray
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.nio.charset.StandardCharsets

class LawnchairBackup(val context: Context, val uri: Uri) {

    val meta by lazy { readMeta() }

    private fun readMeta(): Meta? {
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            val inStream = FileInputStream(pfd.fileDescriptor)
            val zipIs = ZipInputStream(inStream)
            var entry: ZipEntry?
            var meta: Meta? = null
            try {
                while (true) {
                    entry = zipIs.nextEntry
                    if (entry == null) break
                    if (entry.name != Meta.FILE_NAME) continue
                    meta = Meta.fromString(String(zipIs.readBytes(), StandardCharsets.UTF_8))
                    break
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Unable to read meta for $uri", t)
            } finally {
                zipIs.close()
                inStream.close()
                pfd.close()
                return meta
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to read meta for $uri", t)
            return null
        }
    }

    fun restore(contents: Int): Boolean {
        try {
            val contextWrapper = ContextWrapper(context)
            val dbFile = contextWrapper.getDatabasePath(LauncherFiles.LAUNCHER_DB)
            val dir = contextWrapper.cacheDir.parent
            val settingsFile = File(dir, "shared_prefs/" + LauncherFiles.SHARED_PREFERENCES_KEY + ".xml")

            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            val inStream = FileInputStream(pfd.fileDescriptor)
            val zipIs = ZipInputStream(inStream)
            val data = ByteArray(BUFFER)
            var entry: ZipEntry?
            var success = false
            try {
                while (true) {
                    entry = zipIs.nextEntry
                    if (entry == null) break
                    Log.d(TAG, "Found entry ${entry.name}")
                    val file = if (entry.name == dbFile.name) {
                        if (contents and INCLUDE_HOMESCREEN == 0) continue
                        dbFile
                    } else if (entry.name == settingsFile.name) {
                        if (contents and INCLUDE_SETTINGS == 0) continue
                        settingsFile
                    } else {
                        continue
                    }
                    val out = FileOutputStream(file)
                    Log.d(TAG, "Restoring ${entry.name} to ${file.absolutePath}")
                    var count: Int
                    while (true) {
                        count = zipIs.read(data, 0, BUFFER)
                        if (count == -1) break
                        out.write(data, 0, count)
                    }
                    out.close()
                }
                success = true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to restore $uri", t)
            } finally {
                zipIs.close()
                inStream.close()
                pfd.close()
                return success
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to restore $uri", t)
            return false
        }
    }

    class MetaLoader(val backup: LawnchairBackup) {

        var callback: Callback? = null
        var meta: Meta? = null

        fun loadMeta() {
            LoadMetaTask().execute()
        }

        @SuppressLint("StaticFieldLeak")
        inner class LoadMetaTask : AsyncTask<Void, Void, Meta?>() {

            override fun doInBackground(vararg params: Void?) = backup.meta

            override fun onPostExecute(result: Meta?) {
                meta = result
                callback?.onMetaLoaded()
            }
        }

        interface Callback {

            fun onMetaLoaded()
        }
    }

    data class Meta(val name: String, val contents: Int, val timestamp: String) {

        override fun toString(): String {
            val arr = JSONArray()
            arr.put(VERSION)
            arr.put(name)
            arr.put(contents)
            arr.put(timestamp)
            return arr.toString()
        }

        companion object {

            const val VERSION = 1

            const val FILE_NAME = "meta"

            @Suppress("unused")
            private const val VERSION_INDEX = 0
            private const val NAME_INDEX = 1
            private const val CONTENTS_INDEX = 2
            private const val TIMESTAMP_INDEX = 3

            fun fromString(string: String): Meta {
                val arr = JSONArray(string)
                return Meta(
                        name = arr.getString(NAME_INDEX),
                        contents = arr.getInt(CONTENTS_INDEX),
                        timestamp = arr.getString(TIMESTAMP_INDEX)
                )
            }
        }
    }

    companion object {
        const val TAG = "LawnchairBackup"

        const val INCLUDE_HOMESCREEN = 1 shl 0
        const val INCLUDE_SETTINGS = 1 shl 1
        const val INCLUDE_WALLPAPER = 1 shl 2

        const val BUFFER = 2018

        const val EXTENSION = "lawnchairbackup"
        const val MIME_TYPE = "application/vnd.lawnchair.backup"
        val EXTRA_MIME_TYPES = arrayOf(MIME_TYPE, "application/x-zip", "application/octet-stream")

        fun getFolder(): File {
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Lawnchair/backup")
            if (!folder.exists()) {
                folder.mkdirs()
            }
            return folder
        }

        fun create(context: Context, name: String, location: Uri, contents: Int): Boolean {
            val contextWrapper = ContextWrapper(context)
            val files: MutableList<File> = ArrayList()
            if (contents or INCLUDE_HOMESCREEN != 0) {
                files.add(contextWrapper.getDatabasePath(LauncherFiles.LAUNCHER_DB))
            }
            if (contents or INCLUDE_SETTINGS != 0) {
                val dir = contextWrapper.cacheDir.parent
                files.add(File(dir, "shared_prefs/" + LauncherFiles.SHARED_PREFERENCES_KEY + ".xml"))
            }

            Utilities.getLawnchairPrefs(context).blockingEdit { restoreSuccess = true }
            val pfd = context.contentResolver.openFileDescriptor(location, "w")
            val outStream = FileOutputStream(pfd.fileDescriptor)
            val out = ZipOutputStream(BufferedOutputStream(outStream))
            val data = ByteArray(BUFFER)
            var success = false
            try {
                val metaEntry = ZipEntry(Meta.FILE_NAME)
                out.putNextEntry(metaEntry)
                out.write(getMeta(name, contents).toString().toByteArray())
                files.forEach { file ->
                    val input = BufferedInputStream(FileInputStream(file), BUFFER)
                    val entry = ZipEntry(file.name)
                    out.putNextEntry(entry)
                    var count: Int
                    while (true) {
                        count = input.read(data, 0, BUFFER)
                        if (count == -1) break
                        out.write(data, 0, count)
                    }
                    input.close()
                }
                success = true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to create backup", t)
            } finally {
                out.close()
                outStream.close()
                pfd.close()
                Utilities.getLawnchairPrefs(context).blockingEdit { restoreSuccess = false }
                return success
            }
        }

        private fun getMeta(name: String, contents: Int) = Meta(
                name = name,
                contents = contents,
                timestamp = getTimestamp()
        )

        private fun getTimestamp(): String {
            val simpleDateFormat = SimpleDateFormat("dd-MM-yyyy hh:mm:ss", Locale.US)
            return simpleDateFormat.format(Date())
        }
    }
}