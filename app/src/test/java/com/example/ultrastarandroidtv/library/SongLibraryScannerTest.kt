package com.example.ultrastarandroidtv.library

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongLibraryScannerTest {

    @Test
    fun `finds a song and ties it to its media`() {
        val tree = tree {
            dir("Queen - Bohemian Rhapsody") {
                file("song.txt", songText())
                file("song.mp3")
                file("cover.jpg")
                file("video.avi")
            }
        }

        val summary = SongLibraryScanner(tree).scan()

        assertEquals(1, summary.songs.size)
        val found = summary.songs.single()
        assertEquals("Bohemian Rhapsody", found.song.metadata.title)
        assertEquals("Queen - Bohemian Rhapsody", found.folderName)
        assertEquals(tree.idOf("song.mp3"), found.audioId)
        assertEquals(tree.idOf("cover.jpg"), found.coverId)
        assertEquals(tree.idOf("video.avi"), found.videoId)
        assertNull(found.backgroundId)
        assertTrue(found.isPlayable)
    }

    @Test
    fun `matches media regardless of case`() {
        // These files were written on Windows, where Song.MP3 and song.mp3 are the same thing.
        val tree = tree {
            dir("A Song") {
                file("song.txt", songText(mp3 = "Song.MP3"))
                file("song.mp3")
            }
        }

        assertEquals(tree.idOf("song.mp3"), SongLibraryScanner(tree).scan().songs.single().audioId)
    }

    @Test
    fun `takes the file name out of a header that carries a path`() {
        // Paths written on someone else's machine describe someone else's layout. The file
        // that matters is the one sitting next to the song.
        val tree = tree {
            dir("A Song") {
                file("song.txt", songText(mp3 = "C:\\Karaoke\\Songs\\A Song\\song.mp3"))
                file("song.mp3")
            }
        }

        assertEquals(tree.idOf("song.mp3"), SongLibraryScanner(tree).scan().songs.single().audioId)
    }

    @Test
    fun `reports a song whose audio is missing as unplayable rather than hiding it`() {
        val tree = tree {
            dir("Broken") {
                file("song.txt", songText(mp3 = "missing.mp3"))
            }
        }

        val found = SongLibraryScanner(tree).scan().songs.single()

        assertNull(found.audioId)
        assertTrue(!found.isPlayable)
    }

    @Test
    fun `finds songs nested under sorting folders`() {
        val tree = tree {
            dir("Party Classics") {
                dir("ABBA - Waterloo") {
                    file("song.txt", songText(title = "Waterloo"))
                    file("song.mp3")
                }
            }
            dir("Rock") {
                dir("AC-DC - TNT") {
                    file("song.txt", songText(title = "TNT"))
                    file("song.mp3")
                }
            }
        }

        val titles = SongLibraryScanner(tree).scan().songs.map { it.song.metadata.title }

        assertEquals(setOf("Waterloo", "TNT"), titles.toSet())
    }

    @Test
    fun `stops descending past the depth limit`() {
        val tree = tree {
            dir("a") { dir("b") { dir("c") { dir("d") {
                file("song.txt", songText())
                file("song.mp3")
            } } } }
        }

        assertEquals(1, SongLibraryScanner(tree, maxDepth = 4).scan().songs.size)
        assertEquals(0, SongLibraryScanner(tree, maxDepth = 3).scan().songs.size)
    }

    @Test
    fun `treats each text file in a folder as its own song`() {
        // A duet arrangement sitting next to the original is a normal thing to find.
        val tree = tree {
            dir("Elton John - Don't Go Breaking My Heart") {
                file("song.txt", songText(title = "Don't Go Breaking My Heart"))
                file("song [DUET].txt", songText(title = "Don't Go Breaking My Heart (Duet)"))
                file("song.mp3")
            }
        }

        assertEquals(2, SongLibraryScanner(tree).scan().songs.size)
    }

    @Test
    fun `ignores text files that were never songs`() {
        val tree = tree {
            dir("A Song") {
                file("song.txt", songText())
                file("song.mp3")
                file("readme.txt", "Downloaded from a karaoke forum. Enjoy!")
                file("licence.txt", "All rights reserved.")
            }
        }

        val summary = SongLibraryScanner(tree).scan()

        assertEquals(1, summary.songs.size)
        assertEquals("a readme is not a broken song", 0, summary.failures.size)
        assertEquals(1, summary.candidates)
    }

    @Test
    fun `one broken song does not stop the scan`() {
        // A library assembled by strangers over twenty years always has a few of these.
        val tree = tree {
            dir("Good") {
                file("song.txt", songText(title = "Good"))
                file("song.mp3")
            }
            dir("Bad") {
                file("song.txt", "#TITLE:Bad\n#ARTIST:Someone\n: not a note line at all\n")
                file("song.mp3")
            }
            dir("Also Good") {
                file("song.txt", songText(title = "Also Good"))
                file("song.mp3")
            }
        }

        val summary = SongLibraryScanner(tree).scan()

        assertEquals(setOf("Good", "Also Good"), summary.songs.map { it.song.metadata.title }.toSet())
        assertEquals(1, summary.failures.size)
        assertEquals("Bad", summary.failures.single().folderName)
        assertTrue(summary.failures.single().reason.isNotBlank())
    }

    @Test
    fun `a folder it cannot read does not stop the scan`() {
        val tree = tree {
            dir("Fine") {
                file("song.txt", songText(title = "Fine"))
                file("song.mp3")
            }
            dir("Unreadable") { unreadable() }
        }

        assertEquals(1, SongLibraryScanner(tree).scan().songs.size)
    }

    @Test
    fun `skips the folders a memory card is always cluttered with`() {
        val tree = tree {
            dir("Android") { dir("data") { file("song.txt", songText(title = "Nope")) } }
            dir("LOST.DIR") { file("song.txt", songText(title = "Nope")) }
            dir(".thumbnails") { file("song.txt", songText(title = "Nope")) }
            dir("Real Song") {
                file("song.txt", songText(title = "Yes"))
                file("song.mp3")
            }
        }

        val summary = SongLibraryScanner(tree).scan()

        assertEquals(listOf("Yes"), summary.songs.map { it.song.metadata.title })
    }

    @Test
    fun `hands songs over as it finds them`() {
        // The browse list fills in while the scan is still running, so this is not just a
        // convenience — on a big library it is the difference between a UI and a freeze.
        val tree = tree {
            repeat(5) { index ->
                dir("Song $index") {
                    file("song.txt", songText(title = "Song $index"))
                    file("song.mp3")
                }
            }
        }

        val streamed = mutableListOf<String>()
        val summary = SongLibraryScanner(tree).scan { streamed += it.song.metadata.title }

        assertEquals(5, streamed.size)
        assertEquals(summary.songs.map { it.song.metadata.title }, streamed)
    }

    @Test
    fun `reads a library where the files are not all the same encoding`() {
        val tree = tree {
            dir("Utf8") {
                file("song.txt", songText(artist = "Sigur Rós"), Charsets.UTF_8)
                file("song.mp3")
            }
            dir("Windows") {
                file("song.txt", songText(artist = "Blümchen"), Charset.forName("windows-1252"))
                file("song.mp3")
            }
        }

        val artists = SongLibraryScanner(tree).scan().songs.map { it.song.metadata.artist }

        assertEquals(setOf("Sigur Rós", "Blümchen"), artists.toSet())
    }

    @Test
    fun `counts what it walked`() {
        val tree = tree {
            dir("Rock") {
                dir("A Song") {
                    file("song.txt", songText())
                    file("song.mp3")
                }
            }
        }

        val summary = SongLibraryScanner(tree).scan()

        // The granted folder, "Rock", and the song folder.
        assertEquals(3, summary.foldersVisited)
        assertEquals(1, summary.candidates)
    }

    @Test
    fun `an empty library is not an error`() {
        val summary = SongLibraryScanner(tree { }).scan()

        assertEquals(0, summary.songs.size)
        assertEquals(0, summary.failures.size)
        assertEquals(1, summary.foldersVisited)
    }
}

private fun songText(
    title: String = "Bohemian Rhapsody",
    artist: String = "Queen",
    mp3: String = "song.mp3",
): String = """
    #TITLE:$title
    #ARTIST:$artist
    #MP3:$mp3
    #COVER:cover.jpg
    #VIDEO:video.avi
    #BPM:240
    #GAP:1000
    : 0 8 0 Is
    : 8 8 4 this
    E
""".trimIndent()

// ---- a document tree held in memory, so the scanning rules can be tested without a device ----

private class FakeTree : DocumentTree {
    override val rootId = "root"

    private val children = mutableMapOf<String, MutableList<TreeEntry>>("root" to mutableListOf())
    private val contents = mutableMapOf<String, ByteArray>()
    private val unreadableFolders = mutableSetOf<String>()
    private var nextId = 0

    override fun list(directoryId: String): List<TreeEntry> {
        if (directoryId in unreadableFolders) throw SecurityException("permission denied")
        return children[directoryId].orEmpty()
    }

    override fun readBytes(fileId: String): ByteArray =
        contents[fileId] ?: throw NoSuchElementException(fileId)

    fun addDir(parent: String, name: String): String {
        val id = "dir-${nextId++}-$name"
        children.getOrPut(parent) { mutableListOf() }.add(TreeEntry(id, name, isDirectory = true))
        children[id] = mutableListOf()
        return id
    }

    fun addFile(parent: String, name: String, bytes: ByteArray) {
        val id = "file-${nextId++}-$name"
        children.getOrPut(parent) { mutableListOf() }.add(TreeEntry(id, name, isDirectory = false))
        contents[id] = bytes
    }

    fun markUnreadable(id: String) {
        unreadableFolders += id
    }

    /** Id of the single file with this name, for asserting what a song was tied to. */
    fun idOf(name: String): String =
        children.values.flatten().single { it.name == name && !it.isDirectory }.id
}

private class TreeBuilder(private val tree: FakeTree, private val parent: String) {
    fun dir(name: String, build: TreeBuilder.() -> Unit = {}) {
        TreeBuilder(tree, tree.addDir(parent, name)).build()
    }

    fun file(name: String, content: String = "", charset: Charset = Charsets.UTF_8) {
        tree.addFile(parent, name, content.toByteArray(charset))
    }

    fun unreadable() {
        tree.markUnreadable(parent)
    }
}

private fun tree(build: TreeBuilder.() -> Unit): FakeTree {
    val tree = FakeTree()
    TreeBuilder(tree, tree.rootId).build()
    return tree
}
