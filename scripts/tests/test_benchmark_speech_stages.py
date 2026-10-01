import unittest
from types import SimpleNamespace

from scripts.benchmark_speech_stages import merge_ranges, nearest_rank, speech_chunks, summary, trim_with_vad, vad_frames


class SpeechBenchmarkHelpersTest(unittest.TestCase):
    def test_nearest_rank_tail_for_five_samples_is_observed_maximum(self):
        values = [40, 10, 50, 20, 30]
        self.assertEqual(30, nearest_rank(values, 0.50))
        self.assertEqual(50, nearest_rank(values, 0.90))
        self.assertEqual(50, nearest_rank(values, 0.95))
        self.assertEqual(10, nearest_rank(values, 0))
        self.assertEqual(50, nearest_rank(values, 1))
        self.assertEqual(values, [40, 10, 50, 20, 30])
        self.assertEqual({"count": 0, "p50": None, "p90": None, "p95": None, "max": None}, summary([]))
        for values, quantile in [([], 0.5), ([1], -0.1), ([1], 1.1), ([float("nan")], 0.5)]:
            with self.assertRaises(ValueError):
                nearest_rank(values, quantile)

    def test_range_merge_joins_adjacency_without_repeating_samples(self):
        self.assertEqual([(0, 12), (20, 25)], merge_ranges([(20, 25), (5, 10), (0, 5), (8, 12), (4, 4)]))

    def test_vad_frames_keep_and_zero_pad_the_partial_tail(self):
        samples = list(range(515))
        frames = list(vad_frames(samples))
        self.assertEqual(2, len(frames))
        self.assertEqual(samples[:512], frames[0])
        self.assertEqual([512, 513, 514], frames[1][:3])
        self.assertEqual([0.0] * 509, frames[1][3:])
        self.assertEqual([], list(vad_frames([])))

    def test_vad_padding_clamps_merges_and_drains_after_flush(self):
        class FakeVad:
            def reset(self):
                self.queue, self.frames, self.flushed = [], [], False

            def accept_waveform(self, frame):
                self.frames.append(frame)
                if len(self.frames) == 1:
                    self.queue.append(SimpleNamespace(start=1, samples=[1] * 3))

            def flush(self):
                self.flushed = True
                self.queue.append(SimpleNamespace(start=5, samples=[1] * 20))

            def empty(self):
                return not self.queue

            @property
            def front(self):
                return self.queue[0]

            def pop(self):
                self.queue.pop(0)

        samples = list(range(10))
        vad = FakeVad()
        speech, ranges = trim_with_vad(samples, vad, pad_samples=2)
        self.assertEqual([(0, 10)], ranges)
        self.assertEqual(samples, speech)
        self.assertTrue(vad.flushed)
        self.assertEqual(512, len(vad.frames[0]))

    def test_chunks_choose_earliest_complete_sentence_and_retain_separators(self):
        first = "The first sentence has enough context for a clear voice. "
        text = first + "A second sentence explains the reason and takes a little longer to finish. " * 3
        chunks = speech_chunks(text)
        self.assertEqual(first, chunks[0])
        self.assertEqual(text, "".join(chunks))
        self.assertTrue(all(chunk.strip() for chunk in chunks))

    def test_chunks_preserve_indic_marks_numbers_and_an_unbroken_word(self):
        text = "क़ल 123456789.987654321 रुपये देना और अभी नहीं जाना। " * 8
        chunks = speech_chunks(text)
        self.assertEqual(text, "".join(chunks))
        self.assertTrue(any("123456789.987654321" in chunk for chunk in chunks))
        word = "தமிழ்" * 50
        self.assertEqual([word], speech_chunks(word))

    def test_chunks_use_utf16_offsets_for_astral_characters(self):
        # Each emoji occupies two Kotlin Char units. A Python code-point limit
        # would choose the later word instead, incorrectly diverging from the app.
        text = "😀😀 aa bb cc dd"
        chunks = speech_chunks(text, max_chars=8, min_sentence_chars=1)
        self.assertEqual("😀😀 aa ", chunks[0])
        self.assertEqual(text, "".join(chunks))
        self.assertTrue(all("\ud83d" not in chunk for chunk in chunks))

    def test_java_sentence_lookahead_does_not_treat_nbsp_as_regex_whitespace(self):
        # Kotlin Regex's default Java \\s is ASCII; word-boundary fallback still
        # recognizes NBSP via Char.isWhitespace/isSpaceChar.
        text = "aaaaa.\u00a0bbbb cccc dddd eeee"
        chunks = speech_chunks(text, max_chars=12, min_sentence_chars=4)
        self.assertEqual("aaaaa.\u00a0bbbb ", chunks[0])
        self.assertEqual(text, "".join(chunks))


if __name__ == "__main__":
    unittest.main()
