package com.itantra.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalDetailCheckerTest {
    @Test
    fun decimalDigitsAcrossAllProjectScriptsAreEquivalent() {
        val digits = listOf("0123456789", "०१२३४५६७८९", "૦૧૨૩૪૫૬૭૮૯", "০১২৩৪৫৬৭৮৯", "௦௧௨௩௪௫௬௭௮௯", "౦౧౨౩౪౫౬౭౮౯", "೦೧೨೩೪೫೬೭೮೯", "൦൧൨൩൪൫൬൭൮൯", "୦୧୨୩୪୫୬୭୮୯", "٠١٢٣٤٥٦٧٨٩")
        digits.forEach { assertEquals(it, emptyList<String>(), CriticalDetailChecker.check("0123456789", it)) }
    }

    @Test
    fun supplementaryUnicodeDecimalDigitsAreSupportedWithoutNormalizingOtherSymbols() {
        val bold25 = String(Character.toChars(0x1D7D0)) + String(Character.toChars(0x1D7D3))
        assertEquals(emptyList<String>(), CriticalDetailChecker.check("25 bottles", "$bold25 बोतलें"))
        assertFalse(CriticalDetailChecker.check("2 bottles", "² bottles").isEmpty())
    }

    @Test
    fun repeatedQuantitiesAreNotReducedToASet() {
        val missing = CriticalDetailChecker.check("Take 5 now and 5 later", "अब ५ लें")
        assertEquals(1, missing.size)
        assertTrue(missing.single().contains("missing or changed"))
        val added = CriticalDetailChecker.check("Take 5 now", "Take 5 now and 5 later")
        assertEquals(1, added.size)
        assertTrue(added.single().contains("added or changed"))
        assertTrue(CriticalDetailChecker.check("5 5 5", "5").single().contains("2 occurrences"))
    }

    @Test
    fun changedValuesReportBothMissingAndAdded() {
        val warnings = CriticalDetailChecker.check("Bring 25 bottles", "५० बोतलें लाएँ")
        assertEquals(2, warnings.size)
        assertTrue(warnings[0].contains("25"))
        assertTrue(warnings[1].contains("50"))
    }

    @Test
    fun signsRemainMeaningfulAndUnicodeMinusIsCanonicalized() {
        assertEquals(emptyList<String>(), CriticalDetailChecker.check("−5 and ＋2", "-५ and +२"))
        assertFalse(CriticalDetailChecker.check("-5 degrees", "5 degrees").isEmpty())
        assertFalse(CriticalDetailChecker.check("+5", "-5").isEmpty())
    }

    @Test
    fun hyphenRangesAndIdentifiersAreNotReadAsUnaryMinus() {
        assertEquals(emptyList<String>(), CriticalDetailChecker.check("5-10, AB-12", "५–१०, AB१२"))
        assertFalse(CriticalDetailChecker.check("5-10", "5 -10").isEmpty())
    }

    @Test
    fun unaryEnAndEmDashesPreserveNegativeValuesWithoutChangingRanges() {
        listOf("–", "—").forEach { dash ->
            assertFalse(CriticalDetailChecker.check("${dash}5", "5").isEmpty())
            assertEquals(emptyList<String>(), CriticalDetailChecker.check("(${dash}5)", "(-५)"))
            assertEquals(emptyList<String>(), CriticalDetailChecker.check("5${dash}10", "५-१०"))
        }
    }

    @Test
    fun decimalPointsGroupingAndPercentMarkersRemainPartOfTheQuantity() {
        assertEquals(emptyList<String>(), CriticalDetailChecker.check("-2.5 and 1,500 at 5 %", "−٢٫٥ و١٬٥٠٠ عند ٥٪"))
        assertFalse(CriticalDetailChecker.check("2.5", "25").isEmpty())
        assertFalse(CriticalDetailChecker.check("5%", "5").isEmpty())
        assertFalse(CriticalDetailChecker.check("1,500", "1.500").isEmpty())
        assertFalse(CriticalDetailChecker.check(".5", "5").isEmpty())
    }

    @Test
    fun timeComponentsAreComparedTogether() {
        assertEquals(emptyList<String>(), CriticalDetailChecker.check("At 10:30:05", "१०：३०：०५ पर"))
        assertFalse(CriticalDetailChecker.check("At 10:30", "At 10:03").isEmpty())
        assertFalse(CriticalDetailChecker.check("At 10:30", "At 30:10").isEmpty())
    }

    @Test
    fun sentencePunctuationIsNotAddedToNumbers() {
        assertEquals(emptyList<String>(), CriticalDetailChecker.check("Bring 25. Then 50, please.", "५० लाएँ, फिर २५।"))
        assertEquals(emptyList<String>(), CriticalDetailChecker.check("5\u00a0%", "५%"))
    }

    @Test
    fun addedOrOmittedNumericDetailsAreReportedEvenWithAnEmptyOtherSide() {
        assertEquals(1, CriticalDetailChecker.check("Bring water", "Bring 5 bottles").size)
        assertEquals(1, CriticalDetailChecker.check("Bring 5 bottles", "Bring water").size)
    }

    @Test
    fun ordinaryTextProducesNoScoreOrAssuranceAndSemanticBlindSpotsAreExplicit() {
        assertEquals(emptyList<String>(), CriticalDetailChecker.check("My name is Rohan", "My name is Rohn"))
        assertEquals(emptyList<String>(), CriticalDetailChecker.check("25 bottles, not 50", "50 bottles, not 25"))
        assertEquals(emptyList<String>(), CriticalDetailChecker.check("5 mg", "5 g"))
        assertEquals(emptyList<String>(), CriticalDetailChecker.check("five bottles", "fifty bottles"))
    }

    @Test
    fun warningTextIsBoundedForUntrustedNumericInput() {
        val text = (1..100).joinToString(" ") + " " + "7".repeat(1_000)
        val warning = CriticalDetailChecker.check(text, "").single()
        assertTrue(warning.length < 500)
        assertTrue(warning.contains("more values"))
        assertTrue(CriticalDetailChecker.check("7".repeat(1_000), "").single().contains("…"))
    }
}
