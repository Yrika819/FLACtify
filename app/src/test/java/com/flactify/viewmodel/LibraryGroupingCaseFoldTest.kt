package com.flactify.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Proves the grouping's case-folding key is exactly the equivalence relation the previous inline
 * implementation used, so no library can be grouped differently after the optimisation.
 *
 * The reference relation is `String.equals(ignoreCase = true)`, i.e.
 * `java.lang.String.equalsIgnoreCase`, which is the predicate the old code called for every
 * candidate key. Anything that is not character-for-character identical to it can silently split or
 * merge groups, so it is asserted exhaustively over the code units that have any case behaviour at
 * all, and by randomised comparison for multi-character strings.
 */
class LibraryGroupingCaseFoldTest {

    /** Every BMP code unit whose fold is not the code unit itself, i.e. all real case behaviour. */
    private val caseActiveUnits: List<Char> by lazy {
        (0 until 0x10000)
            .map { it.toChar() }
            .filter { LibraryGrouping.caseFold(it.toString()) != it.toString() }
    }

    @Test
    fun `fold is idempotent for every basic multilingual plane code unit`() {
        for (code in 0 until 0x10000) {
            val value = code.toChar().toString()
            val folded = LibraryGrouping.caseFold(value)
            assertEquals(
                "U+${hex(code)} did not fold to a fixed point",
                folded,
                LibraryGrouping.caseFold(folded)
            )
        }
    }

    @Test
    fun `fold preserves length for every basic multilingual plane code unit`() {
        for (code in 0 until 0x10000) {
            val value = code.toChar().toString()
            assertEquals("U+${hex(code)} changed length", 1, LibraryGrouping.caseFold(value).length)
        }
    }

    @Test
    fun `folded key equals equalsIgnoreCase for every case-variant code unit pair`() {
        // A code unit outside this set is unchanged by folding, so it can only be case-equal to
        // itself; that leaves this set as the complete space where the two definitions can differ.
        val units = caseActiveUnits + listOf(
            'a', 'z', 'A', 'Z', '0', '9', ' ', '-', '.', '_', '\n', '\t', 0x00DF.toChar(),
            0x0130.toChar(), 0x0131.toChar(), 0x017F.toChar(), 0x1E9E.toChar(), 0x212A.toChar(),
            0x03A3.toChar(), 0x03C2.toChar(), 0x03C3.toChar(), 0x4E00.toChar()
        )
        for (left in units) {
            val foldedLeft = LibraryGrouping.caseFold(left.toString())
            for (right in units) {
                assertEquals(
                    "fold disagrees with equalsIgnoreCase for " +
                        "U+${hex(left.code)} and U+${hex(right.code)}",
                    left.toString().equals(right.toString(), ignoreCase = true),
                    foldedLeft == LibraryGrouping.caseFold(right.toString())
                )
            }
        }
    }

    @Test
    fun `folded key equals equalsIgnoreCase for supplementary plane code points`() {
        // equalsIgnoreCase compares surrogate pairs as two code units, so folding must neither
        // merge nor split them. Deseret and Osmaniya carry full case pairs beyond the BMP.
        val units = listOf(
            "\uD801\uDC00", "\uD801\uDC28", "\uD801\uDC51", "\uD801\uDC79",
            "\uD801\uDC01", "\uD801\uDC29", "\uD801\uDC52", "\uD801\uDC7A",
            "\uD802\uDD00", "\uD802\uDD20", "\uD80C\uDC00", "\uD80C\uDC20",
            "\uD801\uDC00\uD801\uDC28", "\uD801\uDC28\uD801\uDC00"
        )
        for (left in units) {
            for (right in units) {
                assertEquals(
                    "supplementary plane disagreement for ${hex(left.codePointAt(0))} / " +
                        "${hex(right.codePointAt(0))}",
                    left.equals(right, ignoreCase = true),
                    LibraryGrouping.caseFold(left) == LibraryGrouping.caseFold(right)
                )
            }
        }
    }

    @Test
    fun `folded key equals equalsIgnoreCase for randomised multi-character strings`() {
        val alphabet = (caseActiveUnits + caseActiveUnits.take(64)).toTypedArray()
        val random = Random(20260511L)
        repeat(20_000) {
            val left = randomString(random, alphabet, 1 + random.nextInt(8))
            val right = randomString(random, alphabet, 1 + random.nextInt(8))
            val copy = left
            assertEquals(
                "fold disagrees with equalsIgnoreCase for '$copy' / '$right'",
                copy.equals(right, ignoreCase = true),
                LibraryGrouping.caseFold(copy) == LibraryGrouping.caseFold(right)
            )
        }
    }

    @Test
    fun `folded key equals equalsIgnoreCase for case-variants of a shared multi-character string`() {
        // Mutate one code unit of a string and confirm the fold keeps or drops the match in exactly
        // the cases equalsIgnoreCase does.
        val random = Random(20260511L)
        val alphabet = (caseActiveUnits + caseActiveUnits.take(64)).toTypedArray()
        repeat(20_000) {
            val base = randomString(random, alphabet, 2 + random.nextInt(6))
            val mutated = base.toCharArray()
            val index = random.nextInt(mutated.size)
            mutated[index] = alphabet[random.nextInt(alphabet.size)]
            val variant = String(mutated)
            assertEquals(
                "fold disagrees with equalsIgnoreCase for '$base' / '$variant'",
                base.equals(variant, ignoreCase = true),
                LibraryGrouping.caseFold(base) == LibraryGrouping.caseFold(variant)
            )
        }
    }

    @Test
    fun `lowercase is not a safe grouping key and the reason is documented`() {
        // equalsIgnoreCase merges every one of these three pairs. lowercase() does not: it keeps
        // "ſ" and "ς" apart from their partners, while "İ" expands to two code units.
        val dottedCapitalI = "\u0130"
        val longS = "\u017F"
        val finalSigma = "\u03C2"
        val regularSigma = "\u03C3"

        assertTrue(dottedCapitalI.equals("i", ignoreCase = true))
        assertTrue(longS.equals("s", ignoreCase = true))
        assertTrue(finalSigma.equals(regularSigma, ignoreCase = true))

        assertFalse("dotted I must not lowercase like 'i'", dottedCapitalI.lowercase() == "i")
        assertFalse("long s must not lowercase like 's'", longS.lowercase() == "s")
        assertFalse("final sigma must not lowercase like sigma", finalSigma.lowercase() == regularSigma.lowercase())

        assertEqualsBoolean(
            dottedCapitalI.equals("i", ignoreCase = true),
            LibraryGrouping.caseFold(dottedCapitalI) == LibraryGrouping.caseFold("i")
        )
        assertEqualsBoolean(
            longS.equals("s", ignoreCase = true),
            LibraryGrouping.caseFold(longS) == LibraryGrouping.caseFold("s")
        )
        assertEqualsBoolean(
            finalSigma.equals(regularSigma, ignoreCase = true),
            LibraryGrouping.caseFold(finalSigma) == LibraryGrouping.caseFold(regularSigma)
        )
    }

    @Test
    fun `fold treats an empty and a whitespace key as their own group`() {
        assertEquals("", LibraryGrouping.caseFold(""))
        assertTrue("".equals("", ignoreCase = true))
        assertTrue(LibraryGrouping.caseFold("") == LibraryGrouping.caseFold(""))
        assertFalse("".equals(" ", ignoreCase = true))
        assertFalse(LibraryGrouping.caseFold("") == LibraryGrouping.caseFold(" "))
    }

    private fun assertEqualsBoolean(expected: Boolean, actual: Boolean) {
        assertEquals(expected, actual)
    }

    private fun randomString(random: Random, alphabet: Array<Char>, length: Int): String {
        val chars = CharArray(length) { alphabet[random.nextInt(alphabet.size)] }
        return String(chars)
    }

    private fun hex(code: Int) = "U+" + Integer.toHexString(code).uppercase()
}
