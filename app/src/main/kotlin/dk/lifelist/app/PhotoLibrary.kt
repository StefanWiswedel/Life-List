package dk.lifelist.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size

/**
 * The camera roll, read directly, so the app can show *your photographs* rather than every
 * image on the device.
 *
 * Asked for: "I want the image folder that it opens from to be from DCIM folder by default,
 * not all images." The system photo picker cannot be pointed at a folder — that is the one
 * thing it does not expose — so this is the alternative: query MediaStore ourselves, filter to
 * DCIM, and draw the grid.
 *
 * The trade is a permission. The photo picker needs none, because the user hands over specific
 * images and nothing else; reading the store needs `READ_MEDIA_IMAGES`. That is a real cost and
 * it buys three things: only camera photographs rather than every screenshot and meme, newest
 * first without scrolling past today's WhatsApp, and multi-select that looks like multi-select.
 * The system picker stays as the fallback for anyone who says no.
 *
 * Nothing here throws. A phone with an unusual store layout should show fewer photographs, not
 * a crash on the screen a user opened to record a sighting.
 */
object PhotoLibrary {

    /** One image in the roll. `taken` is what the camera recorded, not when the file landed. */
    data class Item(val id: Long, val uri: Uri, val taken: Long)

    /**
     * The permission needed to read the roll, or null on versions old enough not to have one
     * worth asking for separately.
     */
    val PERMISSION: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * The newest photographs from DCIM, newest first.
     *
     * `RELATIVE_PATH` from API 29 and `DATA` below it: the same filter written twice because
     * the column that holds the answer changed. DCIM rather than DCIM/Camera — a phone puts
     * screenshots in Pictures and camera output in DCIM, but which *subfolder* of DCIM varies
     * by manufacturer, and being slightly generous is better than an empty grid.
     *
     * `DATE_TAKEN` first, `DATE_ADDED` as the tiebreak: a photograph copied onto the phone has
     * no date taken, and sorting it to 1970 would bury it below everything.
     */
    fun recent(context: Context, limit: Int = 600): List<Item> = runCatching {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val order = "${MediaStore.Images.Media.DATE_TAKEN} DESC, " +
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val selection: String
        val arguments: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            arguments = arrayOf("DCIM/%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Images.Media.DATA} LIKE ?"
            arguments = arrayOf("%/DCIM/%")
        }

        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(
                        android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arguments,
                    )
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                },
                null,
            )
        } else {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arguments,
                "$order LIMIT $limit",
            )
        }

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenColumn = it.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val addedColumn = it.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            buildList {
                while (it.moveToNext()) {
                    val id = it.getLong(idColumn)
                    val taken = when {
                        takenColumn >= 0 && !it.isNull(takenColumn) -> it.getLong(takenColumn)
                        // DATE_ADDED is seconds since the epoch; DATE_TAKEN is milliseconds.
                        // Mixing the two without noticing puts every imported photograph in
                        // January 1970.
                        addedColumn >= 0 -> it.getLong(addedColumn) * 1000
                        else -> 0L
                    }
                    add(
                        Item(
                            id = id,
                            uri = android.content.ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id,
                            ),
                            taken = taken,
                        )
                    )
                }
            }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    /**
     * A thumbnail for the grid.
     *
     * `loadThumbnail` from API 29 reads the store's own cached thumbnail where there is one,
     * which is the difference between a grid that scrolls and one that decodes twelve
     * full-resolution JPEGs per screen. Below that, a downsampled decode.
     */
    fun thumbnail(context: Context, item: Item, edge: Int = 256): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(item.uri, Size(edge, edge), null)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                item.id,
                MediaStore.Images.Thumbnails.MINI_KIND,
                null,
            )
        }
    }.getOrNull()
}
