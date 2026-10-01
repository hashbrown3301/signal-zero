# Evaluation data attribution and scope

Source/reference sentences in `corpus.json` and `results.json` derive from the
original **FLORES-200**, attributed to Meta AI / the NLLB Team, and are licensed
under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

- [Official publisher and dataset documentation](https://github.com/facebookresearch/flores/blob/main/flores200/README.md)
- [Original archive](https://dl.fbaipublicfiles.com/nllb/flores200_dataset.tar.gz)
- Archive SHA-256: `b8b0b76783024b85797e5cc75064eb83fc5288b41e9654dabc7be6ae944011f6`

This is a deterministic, reformatted `devtest` subset: two examples for each of
90 ordered pairs, 180 cases and 164 distinct aligned sentence IDs. Original
source/reference sentences are unchanged. Generated hypotheses, failure outcomes,
runtime metadata, metrics and measurements have been added. `corpus.json` keeps
article links and IDs; `corpus-provenance.json` keeps selection, archive/member
hashes, tokenizer and license metadata. Retain these companions when sharing.

This data notice is separate from the repository code's MIT license and the
translation model's noncommercial research restrictions. It does not override
the NLLB model license or certify suitability for production use.

`summary.json` contains standard chrF++ reference-similarity scores, not percent
accuracy. `timing-summary.json` includes every attempted translation, including
the generation-limit failure. Runtime success means nonblank output; it does not
mean correct translation. Public human references can themselves contain errors.

No phone, microphone, audio playback, radio performance or human adequacy rating
was measured. Reproduction and metric details are in
[FLORES_EVALUATION.md](../FLORES_EVALUATION.md).
