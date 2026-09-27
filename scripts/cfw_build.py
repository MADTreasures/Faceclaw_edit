#!/usr/bin/env python3
"""
Build and check the Faceclaw custom firmware for Even Realities G2 glasses -- without flashing.

  python3 cfw_build.py                     download Even's stock 2.3.0.24, build the custom image
  python3 cfw_build.py --stock FILE        use a stock image you already have
  python3 cfw_build.py --check FILE        only validate an image (any EVENOTA file)

Steps of a build (every step must pass, otherwise nothing is written):
  1. stock image: SHA-256 must equal the pinned value of the patch set
  2. apply the patch set (offset / expected old bytes / new bytes, in list order)
  3. patched image: SHA-256 must equal the pinned output value
  4. container validation of both images: structure, CRC-32C of every component
     (MSB-first, init 0, no xorout), main-app preamble (load address, length,
     zlib CRC-32) and the MRAM ceiling (the bootloader's only brick guard)

Standard library only. Even's firmware is never included with this script; it is downloaded
from Even's CDN and stays on your machine. Independent re-implementation of the rules in
docs/analysis/06-firmware.md (same semantics as g2flash's apply_patches.py / validate_firmware).
"""
import argparse
import hashlib
import json
import os
import struct
import sys
import urllib.request
import zlib

STOCK_URL = "https://cdn.evenreal.co/firmware/1dbdf37b03a1169c384945e94d671371.bin"
HERE = os.path.dirname(os.path.abspath(__file__))

MAGIC = b"EVENOTA\x00"
HEADER, TOC_ENTRY, SUBHEADER = 0x40, 16, 128
NAME_OFF, NAME_LEN = 48, 80
MAIN_APP = "ota/s200_firmware_ota.bin"
APP_LOAD = 0x00438000
APP_PREAMBLE = 0x20
APP_MAX_END = 0x007F0000
BLOCK = 4096


class Bad(Exception):
    pass


# ---------------------------------------------------------------- checksums

def _crc32c_msb_table():
    t = []
    for b in range(256):
        c = b << 24
        for _ in range(8):
            c = ((c << 1) ^ 0x1EDC6F41) & 0xFFFFFFFF if c & 0x80000000 else (c << 1) & 0xFFFFFFFF
        t.append(c)
    return t


_T = _crc32c_msb_table()


def crc32c_msb(data):
    crc = 0
    for byte in data:
        crc = ((crc << 8) & 0xFFFFFFFF) ^ _T[((crc >> 24) ^ byte) & 0xFF]
    return crc


def self_test():
    assert crc32c_msb(b"123456789") == 0xC052A8C8, "CRC-32C (MSB-first) check value"
    assert crc32c_msb(bytes(range(256))) == 0xD7B91914, "CRC-32C (MSB-first) vector"
    assert zlib.crc32(b"123456789") & 0xFFFFFFFF == 0xCBF43926, "zlib CRC-32 check value"


# ---------------------------------------------------------------- container

def u32(buf, off):
    if off < 0 or off + 4 > len(buf):
        raise Bad(f"image truncated at 0x{off:x}")
    return struct.unpack_from("<I", buf, off)[0]


def validate(img):
    """Returns the component list; raises Bad with a reason for anything unsafe."""
    if len(img) < HEADER:
        raise Bad("file too small")
    if img[:8] != MAGIC:
        raise Bad("not an EVENOTA image")
    n = u32(img, 8)
    if not 0 < n <= 64:
        raise Bad(f"implausible component count {n}")
    toc_end = HEADER + n * TOC_ENTRY
    if toc_end > len(img):
        raise Bad("table of contents runs past the end")
    comps = []
    for i in range(n):
        e = HEADER + i * TOC_ENTRY
        off, size, crc = u32(img, e + 4), u32(img, e + 8), u32(img, e + 12)
        if off < toc_end or off + SUBHEADER > len(img):
            raise Bad(f"component {i}: subheader outside the file")
        ps = u32(img, off + 8)
        if ps <= 0 or off + SUBHEADER + ps > len(img):
            raise Bad(f"component {i}: payload outside the file")
        if size != ps + SUBHEADER:
            raise Bad(f"component {i}: table size {size} != payload size {ps} + 128")
        name = img[off + NAME_OFF: off + NAME_OFF + NAME_LEN].split(b"\x00")[0].decode("latin-1")
        c = {"index": i, "off": off, "ps": ps, "crc": crc, "name": name, "end": off + SUBHEADER + ps}
        for o in comps:
            if c["off"] < o["end"] and o["off"] < c["end"]:
                raise Bad(f"components {o['name']} and {name} overlap")
        comps.append(c)
    if not 5 <= n <= 6:
        raise Bad(f"expected 5-6 components, found {n}")
    for c in comps:
        payload = img[c["off"] + SUBHEADER: c["end"]]
        calc = crc32c_msb(payload)
        sub = u32(img, c["off"] + 12)
        if calc != c["crc"] or calc != sub:
            raise Bad(f"{c['name']}: stale CRC-32C (computed {calc:08x}, table {c['crc']:08x}, subheader {sub:08x})")
    mains = [c for c in comps if c["name"] == MAIN_APP]
    if len(mains) != 1:
        raise Bad(f"expected exactly one {MAIN_APP}, found {len(mains)}")
    m = mains[0]
    p = m["off"] + SUBHEADER
    if m["ps"] < APP_PREAMBLE:
        raise Bad("main app smaller than its preamble")
    length = u32(img, p) & 0xFFFFFF
    stored = u32(img, p + 4)
    load = u32(img, p + 0x14)
    if load != APP_LOAD:
        raise Bad(f"main-app load address 0x{load:x}, expected 0x{APP_LOAD:x}")
    if length != m["ps"]:
        raise Bad(f"main-app preamble length {length} != payload size {m['ps']}")
    calc = zlib.crc32(img[p + 8: p + m["ps"]]) & 0xFFFFFFFF
    if calc != stored:
        raise Bad(f"main-app preamble CRC-32 stale (stored {stored:08x}, computed {calc:08x})")
    end = APP_LOAD + m["ps"] - APP_PREAMBLE
    if end > APP_MAX_END:
        raise Bad(f"main app too large: ends at 0x{end:x}, {end - APP_MAX_END} bytes past 0x{APP_MAX_END:x} (brick risk)")
    return comps, end


def describe(img, label, allow):
    comps, end = validate(img)
    sha = hashlib.sha256(img).hexdigest()
    print(f"\n{label}: {len(img):,} bytes  sha256 {sha}  -> {allow.get(sha, 'NOT on the allow-list')}")
    print(f"  {'#':>2}  {'component':<28} {'bytes':>10} {'blocks':>7}  crc32c")
    for c in comps:
        print(f"  {c['index']:>2}  {c['name']:<28} {c['ps']:>10,} {-(-c['ps'] // BLOCK):>7}  {c['crc']:08x}")
    print(f"  main app ends at 0x{end:06x}; headroom below 0x{APP_MAX_END:06x}: {APP_MAX_END - end:,} bytes")
    return sha


# ---------------------------------------------------------------- patch set

def strict_hex(s):
    if len(s) % 2 or any(ch not in "0123456789abcdefABCDEF" for ch in s):
        raise Bad("invalid hex in patch set")
    return bytes.fromhex(s)


def apply_patches(base, spec):
    buf = bytearray(base)
    for i, op in enumerate(spec["patches"]):
        off = op["offset"]
        old, new = strict_hex(op.get("old", "")), strict_hex(op["new"])
        tag = f"patch #{i} @ 0x{off:x} ({op.get('desc', '')})"
        if off < 0:
            raise Bad(f"{tag}: negative offset")
        if old:
            if len(old) != len(new) or off + len(old) > len(buf):
                raise Bad(f"{tag}: outside the image")
            cur = bytes(buf[off: off + len(old)])
            if cur == new and cur != old:
                continue  # already applied
            if cur != old:
                raise Bad(f"{tag}: expected {old.hex()}, found {cur.hex()} (wrong base image?)")
            buf[off: off + len(new)] = new
        else:
            end = off + len(new)
            if end <= len(buf) and bytes(buf[off:end]) == new:
                continue
            if off != len(buf):
                raise Bad(f"{tag}: append offset 0x{off:x} != image length 0x{len(buf):x}")
            buf.extend(new)
    return bytes(buf)


# ---------------------------------------------------------------- main

def download(path):
    print(f"downloading {STOCK_URL}")
    req = urllib.request.Request(STOCK_URL, headers={"User-Agent": "faceclaw-edit-cfw-build"})
    with urllib.request.urlopen(req, timeout=60) as r:
        data = r.read()
    tmp = path + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)
    return data


def main(argv=None):
    ap = argparse.ArgumentParser(description="Build/check the Faceclaw custom firmware (never flashes).")
    ap.add_argument("--stock", help="stock image (default: download to g2_2.3.0.24.bin)")
    ap.add_argument("--patches", default=os.path.join(HERE, "cfw_patches.json"), help="patch set JSON")
    ap.add_argument("--out", default="g2_2.3.0.24_cfw.bin", help="output file for the custom image")
    ap.add_argument("--check", metavar="IMAGE", help="only validate IMAGE and exit")
    args = ap.parse_args(argv)
    self_test()

    if not os.path.isfile(args.patches):
        alt = os.path.join(HERE, "..", "core", "src", "main", "resources", "firmware", "cfw_patches.json")
        if os.path.isfile(alt):
            args.patches = alt
    spec = json.load(open(args.patches))
    allow = {spec["base_sha256"]: f"stock ({spec['base']})", spec["output_sha256"]: "custom firmware (patch set output)"}

    try:
        if args.check:
            describe(open(args.check, "rb").read(), args.check, allow)
            return 0
        if args.stock:
            stock = open(args.stock, "rb").read()
        else:
            path = spec["base"]
            stock = open(path, "rb").read() if os.path.isfile(path) else download(path)
        got = hashlib.sha256(stock).hexdigest()
        if got != spec["base_sha256"]:
            raise Bad(f"stock image hash mismatch\n  expected {spec['base_sha256']}\n  got      {got}")
        describe(stock, "stock", allow)
        out = apply_patches(stock, spec)
        got = hashlib.sha256(out).hexdigest()
        if got != spec["output_sha256"]:
            raise Bad(f"patched image hash mismatch\n  expected {spec['output_sha256']}\n  got      {got}")
        describe(out, "custom", allow)
        tmp = args.out + ".part"
        with open(tmp, "wb") as f:
            f.write(out)
        os.replace(tmp, args.out)
        print(f"\nwrote {args.out} ({len(spec['patches'])} patches applied, all checks passed)")
        return 0
    except Bad as e:
        print(f"\nERROR: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
