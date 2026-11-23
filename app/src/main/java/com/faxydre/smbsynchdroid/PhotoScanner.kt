package com.faxydre.smbsynchdroid

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class Photo(
    val uri: Uri,
    val name: String,
    val size: Long,
    /** Secondes depuis l'époque (MediaStore.DATE_ADDED). */
    val dateAdded: Long,
    /** Millisecondes, date de prise de vue (repli sur DATE_ADDED). */
    val takenMs: Long,
)

object PhotoScanner {

    /** Normalise "/DCIM/Camera" ou "DCIM\Camera\" en "DCIM/Camera/" ; vide = toutes les photos. */
    fun normalizeDir(dir: String): String {
        val d = dir.trim().replace('\\', '/').trim('/')
        return if (d.isEmpty()) "" else "$d/"
    }

    /** Photos du dossier source ajoutées depuis [sinceSec], de la plus ancienne à la plus récente. */
    fun scan(context: Context, sourceDir: String, sinceSec: Long): List<Photo> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        val dir = normalizeDir(sourceDir)
        val selection = StringBuilder("${MediaStore.Images.Media.DATE_ADDED} >= ?")
        val args = mutableListOf(sinceSec.toString())
        if (dir.isNotEmpty()) {
            selection.append(" AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
            args += "$dir%"
        }
        val order = "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC"

        val result = mutableListOf<Photo>()
        context.contentResolver.query(collection, projection, selection.toString(), args.toTypedArray(), order)
            ?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val name = c.getString(iName) ?: continue
                    val added = c.getLong(iAdded)
                    val taken = if (c.isNull(iTaken)) 0L else c.getLong(iTaken)
                    result += Photo(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = name,
                        size = if (c.isNull(iSize)) 0L else c.getLong(iSize),
                        dateAdded = added,
                        takenMs = if (taken > 0) taken else added * 1000,
                    )
                }
            }
        return result
    }
}
