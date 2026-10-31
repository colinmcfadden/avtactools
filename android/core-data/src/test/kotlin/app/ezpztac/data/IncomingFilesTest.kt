package app.ezpztac.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingFilesTest {
    private fun IncomingFiles.names() = files.value.map { it.name }

    @Test
    fun `files wait in the order they arrived, with their bytes`() {
        val incoming = IncomingFiles()
        assertTrue(incoming.offer("a.ths", byteArrayOf(1, 2)))
        assertTrue(incoming.offer("b.lps", byteArrayOf(3)))
        assertEquals(listOf("a.ths", "b.lps"), incoming.names())
        assertEquals(listOf<Byte>(1, 2), (incoming.files.value[0] as IncomingFile.Received).bytes.toList())
    }

    @Test
    fun `each file has an id of its own, never reused after one is dismissed`() {
        val incoming = IncomingFiles()
        incoming.offer("a", byteArrayOf(1))
        val first = incoming.files.value.single().id
        incoming.dismiss(first)
        incoming.offer("a", byteArrayOf(1))
        assertTrue(incoming.files.value.single().id != first)
    }

    @Test
    fun `dismissing one leaves the others, and an unknown id changes nothing`() {
        val incoming = IncomingFiles()
        incoming.offer("a", byteArrayOf(1))
        incoming.offer("b", byteArrayOf(1))
        incoming.dismiss(incoming.files.value[0].id)
        assertEquals(listOf("b"), incoming.names())
        incoming.dismiss(9999)
        assertEquals(listOf("b"), incoming.names())
    }

    @Test
    fun `a file that could not be read is kept with its reason, so the person is told`() {
        val incoming = IncomingFiles()
        assertTrue(incoming.refuse("gone.lps", "That file could not be read."))
        val held = incoming.files.value.single() as IncomingFile.Unreadable
        assertEquals("gone.lps", held.name)
        assertEquals("That file could not be read.", held.reason)
    }

    @Test
    fun `no more than the limit are held, files and refusals alike`() {
        val incoming = IncomingFiles()
        repeat(IncomingFiles.MAX_PENDING) { assertTrue(incoming.offer("f$it", byteArrayOf(1))) }
        assertFalse(incoming.offer("one too many", byteArrayOf(1)))
        assertFalse(incoming.refuse("one too many", "x"))
        assertEquals(IncomingFiles.MAX_PENDING, incoming.files.value.size)
        incoming.dismiss(incoming.files.value.first().id)
        assertTrue(incoming.offer("room again", byteArrayOf(1)))
    }

    @Test
    fun `no more than the byte limit is held, and a refusal costs none`() {
        val incoming = IncomingFiles()
        val half = ByteArray((IncomingFiles.MAX_TOTAL_BYTES / 2).toInt())
        assertTrue(incoming.offer("a", half))
        assertTrue(incoming.offer("b", half))                                  // exactly the limit
        assertFalse(incoming.offer("c", byteArrayOf(1)))
        assertTrue(incoming.refuse("d", "x"))                                  // words weigh nothing
        incoming.clear()
        assertTrue(incoming.offer("e", half))
    }

    @Test
    fun `clearing lets go of everything`() {
        val incoming = IncomingFiles()
        incoming.offer("a", byteArrayOf(1))
        incoming.refuse("b", "x")
        incoming.clear()
        assertTrue(incoming.files.value.isEmpty())
    }
}
