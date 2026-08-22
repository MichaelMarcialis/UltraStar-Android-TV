package com.example.ultrastarandroidtv.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedOutputStream
import java.io.FileNotFoundException
import java.io.OutputStream

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
) : DocumentTree, DocumentWriter {

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

    override fun createFolder(parentId: String, name: String): String? = runCatching {
        DocumentsContract.createDocument(
            resolver,
            uriFor(parentId),
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        )?.let(DocumentsContract::getDocumentId)
    }.getOrNull()

    /**
     * Creates the file and writes it in one go.
     *
     * The provider picks the final name — it may add a suffix to avoid a clash, or change the
     * extension to match the MIME type — so the id it hands back is the only reliable handle to
     * what was actually created. A caller that assumed [name] survived would be reading a
     * different file than it wrote.
     *
     * Cleans up after itself: a document that was created but could not be filled is deleted
     * rather than left as an empty file that looks like a song.
     */
    override fun writeFile(
        parentId: String,
        name: String,
        mimeType: String,
        bytes: ByteArray,
    ): String? = writeStream(parentId, name, mimeType) { it.write(bytes) }

    /**
     * The same thing for something too big to hold in memory — a music video.
     *
     * Everything above applies unchanged; the only difference is that the bytes arrive through
     * [write] as they are fetched instead of being handed over whole. That matters because this
     * app's heap is capped at 192 MB and a 1080p video runs to 60-70 MB of it.
     */
    override fun writeStream(
        parentId: String,
        name: String,
        mimeType: String,
        write: (OutputStream) -> Unit,
    ): String? {
        val created = runCatching {
            DocumentsContract.createDocument(resolver, uriFor(parentId), mimeType, name)
        }.getOrNull() ?: return null

        val written = runCatching {
            resolver.openOutputStream(created)?.use { out ->
                BufferedOutputStream(out).let { buffered ->
                    write(buffered)
                    buffered.flush()
                }
            } != null
        }.getOrDefault(false)

        if (!written) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            return null
        }
        return DocumentsContract.getDocumentId(created)
    }

    /**
     * Removes a document, or a folder and everything in it.
     *
     * Returns false rather than throwing, because every reason this fails is one the screen has
     * to explain rather than crash on: the card was pulled, the grant is read-only because it was
     * given by a build that only asked for read, or the provider simply refuses.
     */
    override fun delete(documentId: String): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, uriFor(documentId))
    }.getOrDefault(false)
}
