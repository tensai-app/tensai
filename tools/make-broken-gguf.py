#!/usr/bin/env python3
"""Writes the TEN-91 fixture: a GGUF header whose `general.name` is not UTF-8.

    python3 tools/make-broken-gguf.py app/src/androidTest/assets/gguf/broken-utf8-name.gguf

Why a hand-written file rather than a corrupted real model: the GGUF reader
does not validate the strings it hands back (`gguf.cpp` reads a length, then
that many bytes, and stops there), so the only way to get invalid bytes into
`general.name` is to put them there. Truncating a real model instead makes the
whole header unreadable, which is the case that already worked — the file is
rejected and the import screen says so. What TEN-91 is about is the file that
parses *fine* and carries one string llama.cpp will happily return and
nlohmann::json will refuse to serialize: its strict error handler raises
type_error.316, and before the fix that exception left `nativeReadGgufMetadata`
as a C++ exception and killed the process.

The file is a complete, valid GGUF v3 with zero tensors:

    "GGUF" | version u32=3 | n_tensors u64=0 | n_kv u64=6 | kv pairs...

Zero tensors is what keeps it ~200 bytes: `lm_gguf_init_from_file` skips the
tensor-info loop, the alignment seek and the whole data section when there are
none, and `nativeReadGgufMetadata` passes `ctx = nullptr` so no ggml context is
ever built. Everything the reader does touch is present and well-formed.

The bad bytes in the name are three separate flavours of invalid, so a fix that
only handles one of them still trips over the others:

    \\xff        a byte that can never appear in UTF-8 at all
    \\xc3        a two-byte lead with no continuation after it
    \\xe2\\x82   a three-byte sequence cut one byte short

`general.architecture` is left as clean "llama" on purpose: it is the other
raw-from-the-header string in that JSON, and keeping it valid proves the crash
comes from the name alone. To build the mirror-image fixture, swap which of the
two gets BAD_NAME.

Expected behaviour on device, reading this file:

    before the fix   SIGABRT, `terminating with uncaught exception of type
                     nlohmann::json_abi_v3::detail::type_error` in logcat, and
                     the process is gone — nothing Kotlin-side catches it
    after the fix    metadata comes back, one U+FFFD per bad flavour:
                     {"architecture":"llama","name":"Broken�Model� and�",...}

Verified against `third_party/llama.kt/llama-kt/src/main/cpp/gguf.cpp` at the
submodule revision this fixture was added on.
"""

import struct
import sys
from pathlib import Path

# enum lm_gguf_type, gguf.h
TYPE_UINT32 = 4
TYPE_STRING = 8

BAD_NAME = b"Broken\xffModel\xc3 and\xe2\x82"


def gguf_string(raw: bytes) -> bytes:
    """A GGUF string: u64 byte length, then the bytes. No encoding is implied —
    which is exactly the hole this fixture goes through."""
    return struct.pack("<Q", len(raw)) + raw


def kv_string(key: str, raw: bytes) -> bytes:
    return gguf_string(key.encode()) + struct.pack("<I", TYPE_STRING) + gguf_string(raw)


def kv_u32(key: str, value: int) -> bytes:
    return (
        gguf_string(key.encode())
        + struct.pack("<I", TYPE_UINT32)
        + struct.pack("<I", value)
    )


def build() -> bytes:
    # A plausible small quantised model, so nothing else in the read fails
    # first and masks the string: file_type 15 is Q4_K_M, and the three llama.*
    # keys are the ones nativeReadGgufMetadata reads by name. Head counts are
    # deliberately absent — the KV-shape block then reports 0, its documented
    # "can't tell" answer, which is not what is under test here.
    kvs = [
        kv_string("general.architecture", b"llama"),
        kv_string("general.name", BAD_NAME),
        kv_u32("general.file_type", 15),
        kv_u32("llama.block_count", 2),
        kv_u32("llama.embedding_length", 64),
        kv_u32("llama.context_length", 2048),
    ]
    header = (
        b"GGUF"
        + struct.pack("<I", 3)
        + struct.pack("<Q", 0)
        + struct.pack("<Q", len(kvs))
    )
    return header + b"".join(kvs)


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[2].strip(), file=sys.stderr)
        return 2
    out = Path(sys.argv[1])
    out.parent.mkdir(parents=True, exist_ok=True)
    data = build()
    out.write_bytes(data)
    print(f"{out}: {len(data)} bytes, general.name = {BAD_NAME!r}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
