#!/usr/bin/env python3
"""Reproduce the pinned NLLB LM-head optimization and its tiny Android byte patch.

This is developer tooling; phones use the generated patch and never run Python.
The upstream weights and their license are unchanged. Only the final projection
also dynamically quantizes its activations, as the model's other linear layers do.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
from typing import BinaryIO

SOURCE_SIZE = 475_505_771
SOURCE_SHA256 = "dd66608c2a4194e78f95548fa0e64f24302303698c5b09fa8e1f9e16ec00676b"
TARGET_SIZE = 475_506_537
TARGET_SHA256 = "cb63d93b85e2bc45c0e5816a3177dfab9a7ef7361e77696773fd0191898b2da3"


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def optimize(source: Path, target: Path) -> list[dict]:
    import onnx
    from onnx import TensorProto, helper

    if source.resolve() == target.resolve():
        raise ValueError("Keep the upstream decoder unchanged; choose a separate output file")
    if source.stat().st_size != SOURCE_SIZE or sha256(source) != SOURCE_SHA256:
        raise ValueError("This optimization only supports the pinned upstream decoder revision")
    model = onnx.load(str(source))
    changes: list[dict] = []

    def rewrite(graph) -> None:
        for node in graph.node:
            for attribute in node.attribute:
                if attribute.type == onnx.AttributeProto.GRAPH:
                    rewrite(attribute.g)
        producers = {output: node for node in graph.node for output in node.output}
        nodes = []
        removed = set()
        for node in graph.node:
            if node.op_type != "MatMul" or node.name != "/lm_head/MatMul":
                nodes.append(node)
                continue
            dequantize = producers[node.input[1]]
            if dequantize.op_type != "DequantizeLinear" or list(dequantize.input) != [
                "model.shared.weight_transposed_quantized",
                "model.shared.weight_merged_0_scale",
                "model.shared.weight_merged_0_zero_point",
            ]:
                raise ValueError("The pinned model projection has changed")
            weight, weight_scale, weight_zero_point = dequantize.input
            prefix = "itantra_lm_head"
            quantized, scale, zero_point = [prefix + suffix for suffix in (
                "_hidden_u8", "_hidden_scale", "_hidden_zero_point",
            )]
            dot, cast, combined = [prefix + suffix for suffix in (
                "_dot_i32", "_dot_f32", "_scale",
            )]
            nodes.extend([
                helper.make_node("DynamicQuantizeLinear", [node.input[0]], [quantized, scale, zero_point], name=prefix + "_quantize"),
                helper.make_node("MatMulInteger", [quantized, weight, zero_point, weight_zero_point], [dot], name=prefix + "_matmul_integer"),
                helper.make_node("Cast", [dot], [cast], name=prefix + "_cast", to=TensorProto.FLOAT),
                helper.make_node("Mul", [scale, weight_scale], [combined], name=prefix + "_combine_scales"),
                helper.make_node("Mul", [cast, combined], list(node.output), name=prefix + "_rescale"),
            ])
            removed.add(dequantize.name)
            changes.append({"graph": graph.name, "removed_dequantizer": dequantize.name,
                            "hidden": node.input[0], "weight": weight,
                            "scale": weight_scale, "zero_point": weight_zero_point})
        del graph.node[:]
        graph.node.extend(node for node in nodes if node.name not in removed)

    rewrite(model.graph)
    if len(changes) != 2:
        raise ValueError(f"Expected two decoder branches; found {len(changes)}")
    onnx.checker.check_model(model)
    target.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(target))
    if target.stat().st_size != TARGET_SIZE or sha256(target) != TARGET_SHA256:
        raise ValueError("Derived model differs from the tested artifact; use the pinned ONNX tooling version")
    return changes


def read_at(stream: BinaryIO, offset: int, size: int) -> bytes:
    stream.seek(offset)
    return stream.read(size)


def compact_patch(source: Path, target: Path) -> dict:
    """Find only small changed protobuf regions; skip identical weight blocks in C.

    Anchors resynchronize the two byte streams after small length/node changes.
    A final streaming hash verifies every reconstructed byte, so an ambiguous
    anchor cannot produce an incorrect patch. The strict patch-size bound stops
    an unexpected serializer change from embedding weights in the APK.
    """
    edits = []
    source_size, target_size = source.stat().st_size, target.stat().st_size
    old_position = new_position = 0
    with source.open("rb") as old, target.open("rb") as new:
        while old_position < source_size or new_position < target_size:
            before = read_at(old, old_position, 65536)
            after = read_at(new, new_position, 65536)
            if before == after:
                old_position += len(before)
                new_position += len(after)
                continue
            prefix = next((i for i, (a, b) in enumerate(zip(before, after)) if a != b), min(len(before), len(after)))
            old_position += prefix
            new_position += prefix
            before = read_at(old, old_position, 65536)
            after = read_at(new, new_position, 65536)
            match = None
            for lookahead in range(64, min(len(before) - 1024, 32768), 64):
                anchor = before[lookahead:lookahead + 1024]
                found = after.find(anchor)
                if found >= 0:
                    match = lookahead, found
                    break
            if match is None:
                if max(source_size - old_position, target_size - new_position) > 32768:
                    raise ValueError("A model region changed too much for a compact patch")
                match = source_size - old_position, target_size - new_position
            remove, insert_size = match
            edits.append({"offset": old_position, "remove": remove,
                          "insert_base64": base64.b64encode(read_at(new, new_position, insert_size)).decode("ascii")})
            old_position += remove
            new_position += insert_size
    if sum(edit["remove"] + len(base64.b64decode(edit["insert_base64"])) for edit in edits) > 65536:
        raise ValueError("Model patch unexpectedly includes large regions")
    digest = hashlib.sha256()
    size = 0
    with source.open("rb") as stream:
        position = 0
        for edit in edits:
            remaining = edit["offset"] - position
            while remaining:
                chunk = stream.read(min(remaining, 65536))
                if not chunk:
                    raise ValueError("Source ended during patch self-test")
                digest.update(chunk)
                remaining -= len(chunk)
                size += len(chunk)
            stream.seek(edit["remove"], 1)
            inserted = base64.b64decode(edit["insert_base64"])
            digest.update(inserted)
            size += len(inserted)
            position = edit["offset"] + edit["remove"]
        while chunk := stream.read(65536):
            digest.update(chunk)
            size += len(chunk)
    if size != target_size or digest.hexdigest() != sha256(target):
        raise ValueError("Generated patch did not reproduce the exact derived model")
    return {"source_size": source_size, "source_sha256": sha256(source),
            "target_size": target_size, "target_sha256": digest.hexdigest(), "edits": edits}


def kotlin_spec(patch: dict) -> str:
    edits = ",\n".join(
        f'            ModelEdit({edit["offset"]}L, {edit["remove"]}L, bytes("{edit["insert_base64"]}"))'
        for edit in patch["edits"]
    )
    return f'''package com.itantra.translation

import java.util.Base64

/** Generated by scripts/optimize_translation_decoder.py; weights retain the upstream license. */
internal object NllbDecoderPatch {{
    val spec = ModelPatchSpec(
        sourceSize = {patch["source_size"]}L,
        sourceSha256 = "{patch["source_sha256"]}",
        targetSize = {patch["target_size"]}L,
        targetSha256 = "{patch["target_sha256"]}",
        edits = listOf(
{edits},
        ),
    )
    private fun bytes(value: String): ByteArray = Base64.getDecoder().decode(value)
}}
'''


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--patch-json", type=Path)
    parser.add_argument("--kotlin-output", type=Path)
    parser.add_argument("--existing-output", action="store_true", help="Reuse an already generated, hash-verified output")
    args = parser.parse_args()
    if args.existing_output:
        if args.source.stat().st_size != SOURCE_SIZE or sha256(args.source) != SOURCE_SHA256:
            raise ValueError("Source does not match the pinned decoder")
        if args.output.stat().st_size != TARGET_SIZE or sha256(args.output) != TARGET_SHA256:
            raise ValueError("Output does not match the tested derived decoder")
        changes = None
    else:
        changes = optimize(args.source, args.output)
    patch = compact_patch(args.source, args.output)
    if args.patch_json:
        args.patch_json.parent.mkdir(parents=True, exist_ok=True)
        args.patch_json.write_text(json.dumps(patch, indent=2) + "\n")
    if args.kotlin_output:
        args.kotlin_output.parent.mkdir(parents=True, exist_ok=True)
        args.kotlin_output.write_text(kotlin_spec(patch))
    print(json.dumps({"source_sha256": patch["source_sha256"], "target_sha256": patch["target_sha256"],
                      "edits": len(patch["edits"]),
                      "patch_bytes": sum(len(base64.b64decode(edit["insert_base64"])) for edit in patch["edits"]),
                      "changed_branches": changes}, indent=2))


if __name__ == "__main__":
    main()
