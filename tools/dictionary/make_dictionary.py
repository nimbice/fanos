"""Builds Fanos's built-in dictionary from Open English WordNet.

    python tools/dictionary/make_dictionary.py english-wordnet-2025.xml.gz core/data/src/main/assets/dictionary/oewn-2025.dict

(The tests' small dictionary: sample.xml to sample.dict in core/data/src/test/resources/dictionary, with blocks of 120 bytes.)

The source is the 2025 edition's WN-LMF file, english-wordnet-2025.xml.gz, from
https://github.com/globalwordnet/english-wordnet/releases/tag/2025-edition (Open English WordNet, CC BY 4.0).
Kept: single words and the lower-case phrases ("rib cage", "give up"; not names), each with its first senses as each
part of speech (a definition and an example), how it's said (GB and US), and the irregular forms that point to it ("went"
to go). OfflineDictionary.kt in core/data reads it.

The file: "FANOSDIC", then big-endian u32 version, block count and directory length; the directory, a block's first
key (u16 length, UTF-8) and its offset, compressed and raw lengths (u32 each, offsets counted from the end of the
directory); then the zlib-compressed blocks. A block holds lines in key order:

    key \\t gb \\x1f us \\t part \\x1e part ... \\t lemma \\x1d pos \\x1f ...

where a part is its part of speech then its senses, \\x1f between, and a sense is its definition \\x1d its example.
"""
import gzip
import struct
import sys
import xml.etree.ElementTree as ET
import zlib

POS = {"n": "noun", "v": "verb", "a": "adjective", "s": "adjective", "r": "adverb", "c": "conjunction", "p": "preposition", "x": "other", "u": "other"}
SENSES = 3
BLOCK = 32 * 1024


def clean(text):
    return " ".join("".join(" " if ord(c) < 0x20 else c for c in (text or "")).split())


def sentence(text):
    """WordNet's definitions as the online dictionaries give theirs: a capital letter, and a full stop."""
    text = clean(text)
    if text[:1].islower():
        text = text[0].upper() + text[1:]
    if text and (text[-1].isalnum() or text[-1] in ")’'\""):
        text += "."
    return text


def unquote(text):
    text = clean(text)
    if len(text) >= 2 and text[0] in "\"“" and text[-1] in "\"”":
        text = text[1:-1].strip()
    return text


def read(path):
    entries, synsets = [], {}
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rb") as f:
        for _, el in ET.iterparse(f, events=("end",)):
            if el.tag == "LexicalEntry":
                lemma = el.find("Lemma")
                prons = {p.get("variety") or "": clean(p.text) for p in lemma.findall("Pronunciation")}
                entries.append({
                    "lemma": lemma.get("writtenForm"),
                    "pos": POS.get(lemma.get("partOfSpeech"), "other"),
                    "gb": prons.get("GB") or next(iter(prons.values()), ""),
                    "us": prons.get("US") or "",
                    "forms": [form.get("writtenForm") for form in el.findall("Form")],
                    "senses": [sense.get("synset") for sense in el.findall("Sense")],
                })
                el.clear()
            elif el.tag == "Synset":
                definition = el.find("Definition")
                example = el.find("Example")
                synsets[el.get("id")] = (sentence(definition.text if definition is not None else ""), unquote(example.text if example is not None else ""))
                el.clear()
    return entries, synsets


def usable(key, lemma=None):
    """A key to keep: a single word, or a phrase written in lower case (names and Latin binomials aren't)."""
    if not key or any(ord(c) >= 0x10000 or ord(c) < 0x20 for c in key):
        return False
    return " " not in key or (lemma is not None and lemma == lemma.lower())


def gather(entries, synsets):
    words = {}

    def word(key):
        return words.setdefault(key, {"gb": "", "us": "", "parts": {}, "forms": []})

    # Lower-case lemmas first: "march" the walk before "March" the month.
    for entry in sorted(entries, key=lambda e: e["lemma"] != e["lemma"].lower()):
        key = " ".join(entry["lemma"].lower().split())
        if not usable(key, entry["lemma"]):
            continue
        w = word(key)
        w["gb"] = w["gb"] or entry["gb"]
        w["us"] = w["us"] or entry["us"]
        senses = w["parts"].setdefault(entry["pos"], [])
        for synset in entry["senses"]:
            if len(senses) >= SENSES:
                break
            sense = synsets.get(synset)
            if sense and sense[0] and sense not in senses:
                senses.append(sense)
        for form in entry["forms"]:
            form = (form or "").lower()
            if usable(form) and form != key and (key, entry["pos"]) not in word(form)["forms"]:
                word(form)["forms"].append((key, entry["pos"]))
    return words


def line(key, w):
    parts = "\x1e".join("\x1f".join([pos] + [d + "\x1d" + e for d, e in senses]) for pos, senses in w["parts"].items() if senses)
    forms = "\x1f".join(lemma + "\x1d" + pos for lemma, pos in w["forms"])
    return f"{key}\t{w['gb']}\x1f{w['us']}\t{parts}\t{forms}\n"


def write(words, path, block=BLOCK):
    blocks, current, first = [], [], None
    size = 0
    for key in sorted(words):
        text = line(key, words[key]).encode("utf-8")
        if current and size + len(text) > block:
            blocks.append((first, b"".join(current)))
            current, size = [], 0
        if not current:
            first = key
        current.append(text)
        size += len(text)
    if current:
        blocks.append((first, b"".join(current)))
    directory, data = [], []
    offset = 0
    for first, raw in blocks:
        packed = zlib.compress(raw, 9)
        k = first.encode("utf-8")
        directory.append(struct.pack(">H", len(k)) + k + struct.pack(">III", offset, len(packed), len(raw)))
        data.append(packed)
        offset += len(packed)
    directory = b"".join(directory)
    with open(path, "wb") as f:
        f.write(b"FANOSDIC" + struct.pack(">III", 1, len(blocks), len(directory)) + directory + b"".join(data))
    return len(blocks)


if __name__ == "__main__":
    source, target = sys.argv[1], sys.argv[2]
    words = gather(*read(source))
    count = write(words, target, int(sys.argv[3]) if len(sys.argv) > 3 else BLOCK)
    print(f"{len(words)} words in {count} blocks to {target}")
