package com.example.ultrastarandroidtv.library

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** One child of a folder. [id] is opaque and only means something to the tree that produced it. */
data class TreeEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
)

/**
 * The little of a document tree the library scanner needs: list a folder, read a file.
 *
 * Narrow on purpose. The real implementation talks to Android's Storage Access Framework, which
 * needs a device, a granted folder, and a user standing at a picker — so the scanning rules
 * would be untestable if they were written against it directly. Against this interface they are
 * ordinary logic over a fake tree.
 */
interface DocumentTree {
    /** Document id of the folder the user granted. */
    val rootId: String

    /** Children of [directoryId], in whatever order the provider gives them. */
    fun list(directoryId: String): List<TreeEntry>

    /** Whole contents of [fileId]. Song text files are small; nothing else is read. */
    fun readBytes(fileId: String): ByteArray
}

/**
 * Putting things *into* a document tree.
 *
 * Separate from [DocumentTree] because almost nothing needs it: scanning, playing and browsing
 * are all reads, and the scanner being written against a read-only interface is what lets its
 * rules be tested against an in-memory fake. Only removing a song and downloading one write, and
 * both are rare, deliberate acts.
 */
interface DocumentWriter {

    /**
     * Makes a folder inside [parentId], returning its document id, or null if it could not be
     * made — including when a folder of that name is already there.
     */
    fun createFolder(parentId: String, name: String): String?

    /** Writes a whole file into [parentId]. Returns its document id, or null on any failure. */
    fun writeFile(parentId: String, name: String, mimeType: String, bytes: ByteArray): String?

    /**
     * Writes a file whose contents arrive as they are read, for something too big to hold whole.
     *
     * [write] is handed the open stream and should fill it; throwing from it fails the write, and
     * the half-made document is removed rather than left looking like a file.
     *
     * The default buffers everything and calls [writeFile], which is what an in-memory fake wants
     * and keeps [writeFile] the only method an implementation must provide. The SAF version
     * overrides it and is the one that actually saves the memory.
     */
    fun writeStream(
        parentId: String,
        name: String,
        mimeType: String,
        write: (OutputStream) -> Unit,
    ): String? {
        val buffer = ByteArrayOutputStream()
        return runCatching {
            write(buffer)
            writeFile(parentId, name, mimeType, buffer.toByteArray())
        }.getOrNull()
    }

    /** Removes a document, or a folder and everything in it. False rather than throwing. */
    fun delete(documentId: String): Boolean
}
