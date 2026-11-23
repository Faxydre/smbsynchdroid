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
    /** Secondes depuis l'époque (MediaStore.DATE_MODIFIED). */
    val dateModified: Long,
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
    fun scan(context: Context, sourceDir: String, sinceSec: Long): List<Photo> =
        query(
            context, sourceDir,
            "${MediaStore.Images.Media.DATE_ADDED} >= ?", listOf(sinceSec.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} ASC, ${MediaStore.Images.Media._ID} ASC",
        )

    /**
     * Photos ajoutées avant [addedBeforeSec] mais modifiées (retouchées) depuis [modSinceSec],
     * triées par date de modification.
     */
    fun scanModified(context: Context, sourceDir: String, addedBeforeSec: Long, modSinceSec: Long): List<Photo> =
        query(
            context, sourceDir,
            "${MediaStore.Images.Media.DATE_ADDED} < ? AND ${MediaStore.Images.Media.DATE_MODIFIED} >= ?",
            listOf(addedBeforeSec.toString(), modSinceSec.toString()),
            "${MediaStore.Images.Media.DATE_MODIFIED} ASC, ${MediaStore.Images.Media._ID} ASC",
        )

    private fun query(
        context: Context,
        sourceDir: String,
        where: String,
        whereArgs: List<String>,
        order: String,
    ): List<Photo> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        val dir = normalizeDir(sourceDir)
        val selection = StringBuilder(where)
        val args = whereArgs.toMutableList()
        if (dir.isNotEmpty()) {
            selection.append(" AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?")
            args += "$dir%"
        }

        val result = mutableListOf<Photo>()
        context.contentResolver.query(collection, projection, selection.toString(), args.toTypedArray(), order)
            ?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val iModified = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
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
                        dateModified = if (c.isNull(iModified)) 0L else c.getLong(iModified),
                        takenMs = if (taken > 0) taken else added * 1000,
                    )
                }
            }
        return result
    }
}
