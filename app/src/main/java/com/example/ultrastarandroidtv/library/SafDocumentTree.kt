package com.example.ultrastarandroidtv.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException

/** A song text file this big is not a song text file. Guards against reading a stray blob. */
private const val MAX_TEXT_BYTES = 4 * 1024 * 1024

/**
 * A [DocumentTree] over a folder the user granted through the Storage Access Framework.
 *
 * Deliberately queries the content resolver directly rather than going through `DocumentFile`.
 * `DocumentFile` looks tidier but asks the provider a fresh question for every attribute of
 * every file — name, then type, then the next one — so listing a folder of twenty files costs
 * dozens of round trips through a content provider on another process. Here one query per
 * folder returns everything the scan needs. On a library of thousands of song folders sitting
 * on a card behind a USB adapter, that difference is the whole scan time.
 */
class SafDocumentTree(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : DocumentTree {

    override val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override fun list(directoryId: String): List<TreeEntry> {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        val entries = mutableListOf<TreeEntry>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                entries += TreeEntry(
                    id = id,
                    name = name,
                    isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        }
        return entries
    }

    override fun readBytes(fileId: String): ByteArray {
        val uri = uriFor(fileId)
        val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException(fileId)
        return stream.use { input ->
            val buffer = ByteArray(MAX_TEXT_BYTES)
            var filled = 0
            while (filled < buffer.size) {
                val read = input.read(buffer, filled, buffer.size - filled)
                if (read <= 0) break
                filled += read
            }
            buffer.copyOf(filled)
        }
    }

    /** The playable URI for a document id — what goes to ExoPlayer or an image loader. */
    fun uriFor(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    /**
     * Removes a document, or a folder and everything in it.
     *
     * Returns false rather than throwing, because every reason this fails is one the screen has
     * to explain rather than crash on: the card was pulled, the grant is read-only because it was
     * given by a build that only asked for read, or the provider simply refuses.
     */
    fun delete(documentId: String): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, uriFor(documentId))
    }.getOrDefault(false)
}
