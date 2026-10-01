package com.itantra.translation

/**
 * An observable numeric-detail check, not a translation-quality or confidence estimate.
 *
 * Decimal digits are canonicalized across scripts, while signs, punctuation, time components and
 * percent markers remain meaningful. Counts matter: repeating or dropping a quantity is flagged.
 * Ambiguous comma/dot formatting is deliberately not interpreted using an assumed locale.
 *
 * Empty warnings do not establish correctness. This cannot check negation, names, units, numeric
 * roles, spelled-out numbers or whether an unchanged quantity is used in the right sentence.
 */
object CriticalDetailChecker {
    fun check(source: String, target: String): List<String> {
        val sourceNumbers = numbers(source)
        val targetNumbers = numbers(target)
        val missing = difference(sourceNumbers, targetNumbers)
        val added = difference(targetNumbers, sourceNumbers)
        return buildList {
            if (missing.isNotEmpty()) {
                add("Check numeric details: ${describe(missing)} from the source ${if (missing.values.sum() == 1) "is" else "are"} missing or changed in the translation.")
            }
            if (added.isNotEmpty()) {
                add("Check numeric details: ${describe(added)} ${if (added.values.sum() == 1) "was" else "were"} added or changed in the translation.")
            }
        }
    }

    private fun difference(first: Map<String, Int>, second: Map<String, Int>): Map<String, Int> =
        first.mapNotNull { (number, count) ->
            (count - (second[number] ?: 0)).takeIf { it > 0 }?.let { number to it }
        }.toMap()

    private fun describe(numbers: Map<String, Int>): String {
        val shown = numbers.entries.take(MAX_DISPLAY_VALUES).joinToString(", ") { (number, count) ->
            // A corrupt/untrusted input can contain a very long numeric identifier.
            val display = if (number.length <= MAX_DISPLAY_DIGITS) number else number.take(MAX_DISPLAY_DIGITS) + "…"
            if (count == 1) display else "$display ($count occurrences)"
        }
        val remaining = numbers.size - MAX_DISPLAY_VALUES
        return if (remaining > 0) "$shown, and $remaining more values" else shown
    }

    private fun numbers(text: String): Map<String, Int> {
        val normalized = canonicalCharacters(text)
        val result = linkedMapOf<String, Int>()
        var index = 0
        while (index < normalized.length) {
            val start = index
            var numberStart = start
            if ((normalized[start] == '-' || normalized[start] == '+') && unarySign(normalized, start)) {
                numberStart++
            }
            val leadingDecimal = numberStart < normalized.length && normalized[numberStart] == '.' &&
                numberStart + 1 < normalized.length && normalized[numberStart + 1].isAsciiDigit() &&
                (numberStart == 0 || !Character.isLetterOrDigit(normalized.codePointBefore(numberStart)))
            if (numberStart >= normalized.length || (!normalized[numberStart].isAsciiDigit() && !leadingDecimal)) {
                index++
                continue
            }
            var end = if (leadingDecimal) numberStart + 1 else numberStart
            while (end < normalized.length && normalized[end].isAsciiDigit()) end++
            while (end + 1 < normalized.length && normalized[end] in ".,:" && normalized[end + 1].isAsciiDigit()) {
                end++
                while (end < normalized.length && normalized[end].isAsciiDigit()) end++
            }
            var percent = end
            while (percent < normalized.length && isWhitespace(normalized.codePointAt(percent))) {
                percent += Character.charCount(normalized.codePointAt(percent))
            }
            val hasPercent = percent < normalized.length && normalized[percent] == '%'
            val number = normalized.substring(start, end) + if (hasPercent) "%" else ""
            result[number] = (result[number] ?: 0) + 1
            index = if (hasPercent) percent + 1 else end
        }
        return result
    }

    private fun unarySign(text: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = text.codePointBefore(index)
        // A hyphen in 5-10 or AB-12 is a range/identifier separator, not a negative sign.
        return !Character.isLetterOrDigit(previous) && previous != ')'.code && previous != ']'.code && previous != '%'.code
    }

    private fun canonicalCharacters(text: String): String = buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val digit = if (Character.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER.toInt()) {
                Character.digit(codePoint, 10)
            } else -1
            when {
                digit >= 0 -> append(('0'.code + digit).toChar())
                codePoint == 0x2212 || codePoint == 0x2013 || codePoint == 0x2014 ||
                    codePoint == 0xFE63 || codePoint == 0xFF0D -> append('-')
                codePoint == 0xFF0B -> append('+')
                codePoint == 0x066B || codePoint == 0xFF0E -> append('.')
                codePoint == 0x066C || codePoint == 0xFF0C -> append(',')
                codePoint == 0xFF1A -> append(':')
                codePoint == 0x066A || codePoint == 0xFF05 -> append('%')
                else -> appendCodePoint(codePoint)
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
    private fun isWhitespace(codePoint: Int): Boolean = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
    private const val MAX_DISPLAY_VALUES = 8
    private const val MAX_DISPLAY_DIGITS = 40
}
