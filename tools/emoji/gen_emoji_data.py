#!/usr/bin/env python3
"""Builds app/src/main/assets/yugi/emoji.tsv for the YugiPC emoji picker.

Inputs (download into one directory, pass it as the first argument):
  emoji-test.txt                 https://unicode.org/Public/emoji/15.1/emoji-test.txt
  annotations-{en,he}.xml        CLDR release-46 common/annotations/{en,he}.xml
  annotationsDerived-{en,he}.xml CLDR release-46 common/annotationsDerived/{en,he}.xml

Output: one line per emoji, by category and in Unicode order within it, fully-qualified only, no components:
  category <TAB> emoji <TAB> 5 skin-tone variants (space separated, or empty)
           <TAB> English name <TAB> English keywords (|) <TAB> Hebrew name <TAB> Hebrew keywords (|)
Categories: 0 smileys, 1 people, 2 nature, 3 food, 4 activities, 5 travel, 6 objects, 7 symbols, 8 flags.
"""
import re
import sys
import xml.etree.ElementTree as ET

GROUPS = {
    "Smileys & Emotion": 0, "People & Body": 1, "Animals & Nature": 2, "Food & Drink": 3,
    "Activities": 4, "Travel & Places": 5, "Objects": 6, "Symbols": 7, "Flags": 8,
}
TONES = [0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF]


def key(s):
    return s.replace("️", "")


def load_ann(paths):
    names, kws = {}, {}
    for p in paths:
        for a in ET.parse(p).getroot().iter("annotation"):
            cp = key(a.get("cp"))
            text = (a.text or "").strip()
            if a.get("type") == "tts":
                names.setdefault(cp, text)
            else:
                kws.setdefault(cp, [w.strip() for w in text.split("|") if w.strip()])
    return names, kws


def clean(s):
    return s.replace("\t", " ").replace("|", " ").strip()


def main(src, out):
    emojis, tones = [], {}
    group = None
    for line in open(f"{src}/emoji-test.txt", encoding="utf-8"):
        m = re.match(r"# group: (.+)", line)
        if m:
            group = GROUPS.get(m.group(1).strip())
            continue
        if line.startswith("#") or ";" not in line or group is None:
            continue
        cps, rest = line.split(";", 1)
        if not rest.strip().startswith("fully-qualified"):
            continue
        cps = [int(c, 16) for c in cps.split()]
        s = "".join(chr(c) for c in cps)
        used = [c for c in cps if c in TONES]
        if used:
            if len(set(used)) != 1:
                continue  # mixed tones: the picker offers one tone per emoji
            base = key("".join(chr(c) for c in cps if c not in TONES))
            tones.setdefault(base, {})[used[0]] = s
            continue
        emojis.append((group, s))
    # emoji-test.txt has Travel before Activities: the picker's tab order wins (stable sort)
    emojis.sort(key=lambda e: e[0])
    en_n, en_k = load_ann([f"{src}/annotations-en.xml", f"{src}/annotationsDerived-en.xml"])
    he_n, he_k = load_ann([f"{src}/annotations-he.xml", f"{src}/annotationsDerived-he.xml"])
    n = 0
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        for g, s in emojis:
            k = key(s)
            t = tones.get(k, {})
            tv = " ".join(t[c] for c in TONES) if len(t) == 5 else ""
            row = [str(g), s, tv]
            for names, kws in ((en_n, en_k), (he_n, he_k)):
                name = clean(names.get(k, ""))
                words = [clean(w) for w in kws.get(k, []) if clean(w) and clean(w) != name]
                row += [name, "|".join(dict.fromkeys(words))]
            f.write("\t".join(row) + "\n")
            n += 1
    print(f"{n} emoji, {sum(1 for g, s in emojis if key(s) in tones)} with skin tones")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
