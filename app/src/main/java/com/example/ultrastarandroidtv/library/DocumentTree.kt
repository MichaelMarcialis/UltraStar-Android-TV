package com.example.ultrastarandroidtv.library

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
