"""Local English -> Portuguese OCR and typesetting worker. No comic pages are uploaded.

Only model installation needs the internet. Text lines are found by EasyOCR's CRAFT
detector, grouped into balloons, read balloon by balloon with Tesseract and translated
by a local LLM served by Ollama (all balloons of a page in one request, so the model sees
the conversation), or by Argos when no LLM is configured or it fails. Erased text is repainted with the balloon's own colour; text drawn straight
over artwork (sound effects, logos) is left as is, since there is no flat fill to repaint.
The original image remains in MangaVerse.
"""
import csv
import io
import json
import logging
import multiprocessing
import os
import re
import socket
import time
import urllib.request
from concurrent.futures import ProcessPoolExecutor
from concurrent.futures.process import BrokenProcessPool
from dataclasses import dataclass
from pathlib import Path
import subprocess
import threading
import warnings
from functools import lru_cache
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy
from PIL import Image, ImageDraw, ImageFont, ImageOps
from scipy import ndimage

Image.MAX_IMAGE_PIXELS = 30_000_000
MAX_BODY = 25 * 1024 * 1024
FONTS = (
    "/usr/share/fonts/opentype/comic-neue/ComicNeue-Bold.otf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
)
FONT = next((font for font in FONTS if Path(font).exists()), FONTS[-1])
SLOTS = threading.BoundedSemaphore(2)
IDLE_SECONDS = float(os.environ.get("TRANSLATOR_IDLE_MINUTES", "5")) * 60
# Empty LLM_URL keeps the Argos-only engine.
LLM_URL = os.environ.get("TRANSLATOR_LLM_URL", "").rstrip("/")
LLM_MODEL = os.environ.get("TRANSLATOR_LLM_MODEL", "qwen3:4b-instruct")
USE_GPU = os.environ.get("TRANSLATOR_GPU", "0") == "1"
TRANSLATE_MANY = None
TRANSLATE = None
MODEL_LOCK = threading.Lock()
DETECT_LOCK = threading.Lock()
DETECTOR = None
# CRAFT finds comic lettering reliably once the page's long side is about this size; larger
# canvases found the same lines in twice the time on both low- and high-resolution scans.
DETECT_SIDE = 1600
# Tesseract reads clean lettering best when capitals are roughly this tall.
READ_HEIGHT = 48
FUNCTION_WORDS = {"A", "I", "AN", "AS", "AT", "BE", "BY", "DO", "HE", "IF", "IN", "IS", "IT", "ME", "MY", "NO", "OF",
                  "ON", "OR", "SO", "TO", "UP", "US", "WE", "ALL", "AND", "ARE", "BUT", "CAN", "FOR", "GET", "GOT",
                  "HAS", "HER", "HIM", "HIS", "HOW", "ITS", "NOT", "NOW", "OUT", "SHE", "THE", "WAS", "WHO", "WHY",
                  "YOU"}
SHORT_WORDS = {"A", "I", "AH", "AM", "AN", "AS", "AT", "BE", "BY", "DO", "GO", "HE", "HI", "IF", "IN", "IS",
               "IT", "ME", "MY", "NO", "OF", "OH", "OK", "ON", "OR", "SO", "TO", "UH", "UM", "UP", "US", "WE"}


WORDLIST = "/usr/share/dict/american-english"
# Visually close letters in comic lettering that Tesseract swaps; only applied to unknown words.
CONFUSIONS = (("F", "T"), ("T", "F"), ("F", "H"), ("H", "F"), ("A", "K"), ("I", "L"), ("L", "I"), ("C", "E"),
              ("E", "C"), ("C", "O"), ("O", "C"), ("D", "O"), ("O", "D"), ("H", "N"), ("N", "H"), ("U", "V"),
              ("V", "U"), ("R", "T"), ("E", "F"), ("F", "E"), ("T", "L"), ("L", "T"), ("B", "D"),
              ("A", "H"), ("RN", "M"), ("VV", "W"), ("CL", "D"), ("LI", "U"))


@dataclass
class Region:
    text: str
    box: tuple  # text bounds: left, top, right, bottom
    interior: numpy.ndarray  # balloon pixels inside `window`, lettering included
    window: tuple  # left, top of `interior` on the page
    fill: tuple  # balloon colour
    ink: tuple  # lettering colour
    height: int  # source line height in pixels
    caps: bool = False  # lettered in capitals; the translation is set the same way


@lru_cache(maxsize=1)
def dictionary():
    """Lowercase word -> True if it is an ordinary word, False if only a proper name (Bruce, Gotham)."""
    words = {}
    try:
        with open(WORDLIST, encoding="utf-8") as stream:
            for entry in stream:
                entry = entry.strip()
                if entry:
                    key = entry.lower()
                    words[key] = words.get(key, False) or entry == key
    except OSError:
        logging.warning("Dicionário %s ausente; nomes próprios e correções de OCR ficam desativados.", WORDLIST)
    return words


def load_detector():
    import easyocr
    import torch

    torch.set_num_threads(int(os.environ.get("OMP_NUM_THREADS", "2")))
    gpu = USE_GPU and torch.cuda.is_available()
    if USE_GPU and not gpu:
        logging.warning("TRANSLATOR_GPU=1, mas a GPU não está visível no contêiner; detectando texto na CPU.")
    directory = Path(os.environ.get("XDG_DATA_HOME", "/models")) / "easyocr"
    directory.mkdir(parents=True, exist_ok=True)
    logging.info("Carregando detector de texto (o download ocorre somente no primeiro uso do volume).")
    return easyocr.Reader(["en"], gpu=gpu, model_storage_directory=str(directory), verbose=False)


def detect_lines(image):
    """Horizontal text line boxes (left, top, right, bottom)."""
    # Small scans are enlarged and large ones reduced, so lettering reaches the detector at a similar size.
    mag = min(2.5, DETECT_SIDE / max(image.size))
    with DETECT_LOCK:
        boxes, _ = DETECTOR.detect(numpy.asarray(image), mag_ratio=mag, canvas_size=DETECT_SIDE, text_threshold=0.6,
                                   low_text=0.35, width_ths=0.8, add_margin=0.05)
    lines = []
    for x0, x1, y0, y1 in boxes[0]:
        x0, y0 = max(0, int(x0)), max(0, int(y0))
        x1, y1 = min(image.width, int(x1)), min(image.height, int(y1))
        if x1 - x0 >= 4 and y1 - y0 >= 4:
            lines.append((x0, y0, x1, y1))
    return lines


def bounds(boxes):
    return (min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes))


def line_height(lines):
    # Lower median: the detector sometimes boxes two lines together.
    return sorted(b[3] - b[1] for b in lines)[(len(lines) - 1) // 2]


def group_lines(lines):
    """Union lines of one balloon: stacked lines, neighbours on a line, and overlapping fragments."""
    parent = list(range(len(lines)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def close(a, b, low):
        x_overlap = min(a[2], b[2]) - max(a[0], b[0])
        y_overlap = min(a[3], b[3]) - max(a[1], b[1])
        stacked = -y_overlap <= 0.9 * low and x_overlap > 0.2 * min(a[2] - a[0], b[2] - b[0])
        same_line = y_overlap > 0.5 * low and -x_overlap <= 1.2 * low
        return stacked or same_line or x_overlap > 0 and y_overlap > 0

    for i, a in enumerate(lines):
        for j in range(i + 1, len(lines)):
            b = lines[j]
            ha, hb = a[3] - a[1], b[3] - b[1]
            if max(ha, hb) <= 1.8 * min(ha, hb) and close(a, b, min(ha, hb)):
                parent[find(i)] = find(j)
    groups = {}
    for i, line in enumerate(lines):
        groups.setdefault(find(i), []).append(line)
    groups = list(groups.values())
    # Detector fragments (a missed word, a merged double line) can leave one balloon as two groups.
    merged = True
    while merged:
        merged = False
        for i in range(len(groups)):
            for j in range(i + 1, len(groups)):
                a, b = bounds(groups[i]), bounds(groups[j])
                low = min(line_height(groups[i]), line_height(groups[j]))
                gap_x = max(a[0], b[0]) - min(a[2], b[2])
                gap_y = max(a[1], b[1]) - min(a[3], b[3])
                similar = max(line_height(groups[i]), line_height(groups[j])) <= 1.8 * low
                if gap_x < 0 and gap_y < 0 or max(gap_x, gap_y) <= 0.8 * low and similar:
                    groups[i] += groups.pop(j)
                    merged = True
                    break
            if merged:
                break
    return sorted(groups, key=lambda g: (bounds(g)[1], bounds(g)[0]))


def columns(lines):
    """Stacks of lines, left to right: joined balloons sit side by side and are read one by one."""
    parent = list(range(len(lines)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    for i, a in enumerate(lines):
        for j in range(i + 1, len(lines)):
            b = lines[j]
            low = min(a[3] - a[1], b[3] - b[1])
            x_overlap = min(a[2], b[2]) - max(a[0], b[0])
            y_overlap = min(a[3], b[3]) - max(a[1], b[1])
            stacked = -y_overlap <= 0.9 * low and x_overlap > 0.2 * min(a[2] - a[0], b[2] - b[0])
            # Words of one line split by the detector are a space apart, not a column gap.
            same_line = y_overlap > 0.5 * low and -x_overlap < 0.8 * low
            if stacked or same_line:
                parent[find(i)] = find(j)
    stacks = {}
    for i, line in enumerate(lines):
        stacks.setdefault(find(i), []).append(line)
    return sorted(stacks.values(), key=lambda stack: bounds(stack)[0])


def lettering_rows(lines, height):
    """Line boxes widened toward the group edges, so words the detector clipped are covered too."""
    left, top, right, bottom = bounds(lines)
    reach, pad = round(height * 2.5), max(1, round(height * 0.15))
    return [(max(left, x0 - reach), max(top - pad, y0 - pad), min(right, x1 + reach), min(bottom + pad, y1 + pad))
            for x0, y0, x1, y1 in lines]


def background(pixels, box, height):
    """Flat colour around the text, or None when it sits on artwork.

    Balloons are oval, so only the middle half of each side is sampled: the corners
    of a rectangle around the text often fall on the outline or outside. One side may
    fail, since torn-paper captions are cut close to the lettering.
    """
    best = None
    for margin in (max(2, round(height * 0.15)), max(2, round(height * 0.3)), max(3, round(height * 0.6))):
        left, top = max(0, box[0] - margin), max(0, box[1] - margin)
        right, bottom = min(pixels.shape[1], box[2] + margin), min(pixels.shape[0], box[3] + margin)
        if right - left < 8 or bottom - top < 8:
            continue
        qx, qy = (right - left) // 4, (bottom - top) // 4
        sides = (pixels[top:top + 2, left + qx:right - qx], pixels[bottom - 2:bottom, left + qx:right - qx],
                 pixels[top + qy:bottom - qy, left:left + 2], pixels[top + qy:bottom - qy, right - 2:right])
        sides = [side.reshape(-1, 3).astype(numpy.int16) for side in sides]
        colour = numpy.median(numpy.concatenate(sides), axis=0)
        # Captions hug their text, so one side may touch the outline; the other three must be flat.
        flat = sorted((numpy.abs(side - colour).max(axis=1) < 40).mean() for side in sides)[1]
        if flat >= 0.85 and (best is None or flat > best[0]):
            best = (flat, tuple(int(c) for c in colour))
    return best and best[1]


def dominant_colour(pixels, box):
    area = pixels[box[1]:box[3], box[0]:box[2]].reshape(-1, 3)
    bins = (area // 24).astype(numpy.int32)
    keys = bins[:, 0] * 10000 + bins[:, 1] * 100 + bins[:, 2]
    values, counts = numpy.unique(keys, return_counts=True)
    return tuple(int(c) for c in numpy.median(area[keys == values[counts.argmax()]], axis=0))


def balloon(pixels, lines, height):
    """(fill, interior, window) when the text sits in a closed flat balloon or caption, else None.

    The colour around the text is tried first, then the dominant colour under the text for
    balloons cut close to the lettering. Either must be most of the text area, and its flat
    region (letters included) must cover the text and be closed: flat colour running out of
    the search window is scenery such as sky or a wall; a balloon's tail may cross it, but
    only as a narrow strip. White is the exception, since balloons that break the panel
    border open into the page margin.
    """
    box = bounds(lines)
    area = numpy.concatenate([pixels[y0:y1, x0:x1].reshape(-1, 3) for x0, y0, x1, y1 in lines]).astype(numpy.int16)
    for fill in (background(pixels, box, height), dominant_colour(pixels, box)):
        if fill is None or (numpy.abs(area - numpy.array(fill)).max(axis=1) < 40).mean() < 0.5:
            continue
        interior, (ox, oy) = balloon_interior(pixels, box, fill, height)
        # Measured on the lines, not their bounding box: joined balloons leave corners outside.
        covered = numpy.mean([interior[y0 - oy:y1 - oy, x0 - ox:x1 - ox].mean() for x0, y0, x1, y1 in lines])
        tail = 2 * height
        open_sides = (interior[0].sum() > tail and oy > 0,
                      interior[-1].sum() > tail and oy + interior.shape[0] < pixels.shape[0],
                      interior[:, 0].sum() > tail and ox > 0,
                      interior[:, -1].sum() > tail and ox + interior.shape[1] < pixels.shape[1])
        if covered < 0.85:
            continue
        if any(open_sides):
            if luminance(fill) < 235:
                continue
            # White balloons often break out of the panel into the page margin; keep the
            # lettering area close to the text instead of the whole margin.
            reach = round(height * 1.5)
            near = numpy.zeros_like(interior)
            near[max(0, box[1] - oy - reach):box[3] - oy + reach, max(0, box[0] - ox - reach):box[2] - ox + reach] = True
            interior &= near
        return fill, interior, (ox, oy)
    return None


def split_by_balloon(pixels, lines):
    """Separate lines of touching balloons, which spacing alone groups together, by the
    balloon each line sits in."""
    parent = list(range(len(lines)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    colours = [numpy.array(dominant_colour(pixels, line), dtype=numpy.int16) for line in lines]
    for i, line in enumerate(lines):
        interior, (ox, oy) = balloon_interior(pixels, line, tuple(colours[i]), line[3] - line[1])
        for j, other in enumerate(lines):
            x, y = (other[0] + other[2]) // 2 - ox, (other[1] + other[3]) // 2 - oy
            # Dark lettering lies inside any dark region, and light sound effects inside light
            # balloons, so fills and letter sizes must match too.
            heights = sorted((line[3] - line[1], other[3] - other[1]))
            if j != i and 0 <= y < interior.shape[0] and 0 <= x < interior.shape[1] and interior[y, x] \
                    and numpy.abs(colours[i] - colours[j]).max() < 40 and heights[1] <= 1.8 * heights[0]:
                parent[find(j)] = find(i)
    groups = {}
    for i, line in enumerate(lines):
        groups.setdefault(find(i), []).append(line)
    return list(groups.values())


def luminance(colour):
    return 0.299 * colour[0] + 0.587 * colour[1] + 0.114 * colour[2]


def read_text(image, rows, fill, height):
    """Tesseract over the lettering rows only, so outlines and nearby art are not read."""
    left, top, right, bottom = bounds(rows)
    pad = 8
    shade = round(luminance(fill))
    canvas = Image.new("L", (right - left + 2 * pad, bottom - top + 2 * pad), shade)
    gray = image.convert("L")
    for x0, y0, x1, y1 in rows:
        canvas.paste(gray.crop((x0, y0, x1, y1)), (x0 - left + pad, y0 - top + pad))
    if shade < 128:
        canvas = ImageOps.invert(canvas)
    scale = min(4.0, max(1.0, READ_HEIGHT / height))
    canvas = ImageOps.autocontrast(canvas.resize((round(canvas.width * scale), round(canvas.height * scale)),
                                                 Image.LANCZOS))
    stream = io.BytesIO()
    canvas.save(stream, format="PNG")
    result = subprocess.run(
        ["tesseract", "stdin", "stdout", "-l", "eng", "--psm", "6", "--dpi", "300",
         "-c", "tessedit_char_blacklist=|\\~{}<>_=«»", "tsv"],
        input=stream.getvalue(), capture_output=True, timeout=60, check=True,
    )
    rows = csv.DictReader(io.StringIO(result.stdout.decode("utf-8")), delimiter="\t", quoting=csv.QUOTE_NONE)
    words = []
    for row in rows:
        text = (row.get("text") or "").strip()
        if text:
            words.append((text, float(row["conf"]), (row["block_num"], row["par_num"], row["line_num"])))
    return words


def capitals(words):
    """Share of capitals among the confidently read letters (all letters if none are)."""
    letters = [c for text, conf, _ in words if conf >= 25 for c in text if c.isalpha()] \
        or [c for text, _, _ in words for c in text if c.isalpha()]
    return sum(c.isupper() for c in letters) / len(letters) if letters else 0.0


def clean_text(words, caps=None):
    """Join Tesseract words into English, undoing common misreads of comic lettering."""
    if caps is None:
        caps = capitals(words) >= 0.6
    numeric = any(re.search(r"\d{2}", text) for text, _, _ in words)
    out = []
    for index, (text, conf, line) in enumerate(words):
        following = words[index + 1][0] if index + 1 < len(words) else ""
        if text in ("10", "70", "fo") and following[:1].isalpha():
            text = "TO" if caps else "to"  # Lettered T read as 7 or 1.
        elif numeric and re.match(r"[147](?=[A-Z]{2})", text):
            # "1IN 649,740" (often read as 4IN or 7IN): the number one, not the word I.
            text, conf = "1 " + text[1:], max(conf, 50)
        elif caps:
            text = fix_caps_word(text)
        text = re.sub(r"[A-Za-z][A-Za-z']{2,}[A-Za-z]", correct_word, text)
        if len(re.findall(r"[#@$%&*^]", text)) >= 2 or re.fullmatch(r"\d+%[!?.,]*|#\d+[!?.,]*", text):
            continue  # Censored swearing ("#$%^", read as "#598" or "5%"); the translation drops it anyway.
        core = re.sub(r"[^A-Za-z0-9']", "", text).strip("'")
        if not core and not re.fullmatch(r"[.!?,'\"-]+|[@#$%&*!?]{3,}", text):
            continue
        # Tesseract often scores a correct last word near zero; keep it when it is a real word.
        if conf < 25 and (not core.isalpha() or len(core) < 3 or known(core) is None):
            continue
        if conf < 50 and len(core) <= 3 and core.isalpha() and known(core) is None:
            continue
        if len(core) <= 2 and conf < 60 and core.upper() not in SHORT_WORDS:
            continue
        if out and out[-1][1] != line and re.search(r"[A-Za-z]-$", out[-1][0]) and not out[-1][0].endswith("--") \
                and text[:1].isalpha():
            # Hyphenated across lines; compounds ("FIFTY-FIFTY") keep the hyphen.
            head, joined = out[-1][0][:-1], out[-1][0][:-1] + text
            compound = known(re.sub(r"[^A-Za-z']", "", joined)) is None and known(head) is not None \
                and known(re.sub(r"[^A-Za-z']", "", text)) is not None
            out[-1] = (out[-1][0] + text if compound else joined, line, min(conf, out[-1][2]))
        else:
            out.append((text, line, conf))
    # Stray marks at the end of a balloon (outline, tail) read as one or two characters.
    # Likewise a stray mark before the first word, and a lone digit after the last ("AGAIN 4").
    while len(out) > 1 and re.fullmatch(r"[^A-Za-z0-9]*[B-HJ-Zb-hj-z][^A-Za-z0-9]*", out[0][0]):
        out.pop(0)
    if len(out) > 1 and re.fullmatch(r"\d", out[-1][0]) and not re.search(r"\d", out[-2][0]):
        out.pop()
    while out and len(re.sub(r"[^A-Za-z0-9]", "", out[-1][0])) in (1, 2) \
            and re.sub(r"[^A-Za-z]", "", out[-1][0]).upper() not in SHORT_WORDS - {"A"} \
            and (out[-1][2] < 70 or len(out) > 1 and re.search(r"[.!?\"”]$", out[-2][0])):
        out.pop()
    return " ".join(text for text, _, _ in out), caps


def fix_caps_word(word):
    if word == "1":
        return "I"
    word = re.sub(r"^1(?=[A-Z]{2})", "I ", word)  # "1CAN'T": a lettered I joined to the next word.
    if re.search(r"[A-Z]", word):
        word = re.sub(r"(?<=[A-Z])0|0(?=[A-Z])", "O", word)
        word = re.sub(r"^7(?=[A-Z]{2})", "T", word)
        word = re.sub(r"(?<=[A-Z])/(?=[A-Z])", "I", word)
        word = re.sub(r"(?<=[A-Z])/(?=$|[\"'”’])", "!", word)
        word = re.sub(r"^\\", "I", word)
        # Lettered question marks come back as 2 or a trailing Z: "YOU2", "ME2!", "SEEZ".
        word = re.sub(r"(?<=[A-Z])2Z?(?=$|[!\"”’'.,])", "?", word)
        # A serifed I comes back as T: "T'M CALLING", "AND T DIDN'T".
        word = re.sub(r"^T(?=$|'(?:M|LL|VE|D)\b|[.,!?])", "I", word)
        if re.fullmatch(r"[A-Z']{2,}Z", word) and not re.search(r"(ZZ|TZ|IZ)$", word) \
                and word.lower() not in dictionary():
            word = word[:-1] + "?"
    return word


def correct_word(match):
    word = match.group(0)
    if not dictionary() or known(word) is not None:
        return word
    title = word[:1].isupper() and not word[1:].isupper()
    lower = word.islower() or title
    word = word.upper()
    candidates = set()
    for wrong, right in CONFUSIONS:
        start = word.find(wrong)
        while start != -1:
            candidate = word[:start] + right + word[start + len(wrong):]
            if known(candidate) is not None:
                candidates.add(candidate)
            start = word.find(wrong, start + 1)
    if not candidates:
        candidates = {" ".join(parts)} if (parts := segment(word)) else set()
    if len(candidates) != 1:
        return match.group(0)
    fixed = candidates.pop()
    return fixed.capitalize() if title else fixed.lower() if lower else fixed


def segment(word):
    """Split words the OCR glued together ("TELLITAGAIN", "HEHASA") into the fewest
    ordinary dictionary words; None when there is no such split."""
    words = dictionary()

    def piece(text):
        key = text.lower()
        return words.get(key) is True and (len(text) > 2 or text in SHORT_WORDS)

    best = {0: []}
    for end in range(1, len(word) + 1):
        options = [best[start] + [word[start:end]] for start in range(max(0, end - 15), end)
                   if start in best and piece(word[start:end])]
        if options:
            # Fewest words; on a tie, longer words ("TELL IT AGAIN", not "TEL LIT AGAIN").
            best[end] = min(options, key=lambda parts: (len(parts), -sum(len(part) ** 2 for part in parts)))
    parts = best.get(len(word))
    # Glued words always involve a short function word; compounds such as "GODSPEED" do not.
    if parts and 1 < len(parts) <= 4 and any(part in FUNCTION_WORDS for part in parts):
        return parts
    return None


def known(word):
    """True for an ordinary word, False for a proper name, None when not in the dictionary."""
    words = dictionary()
    key = word.lower().replace("’", "'")
    if key in words:
        return words[key]
    if key.endswith("'s") and key[:-2] in words:
        return words[key[:-2]]
    if "'" in key and key.split("'")[0] in words:
        return words[key.split("'")[0]]  # Contractions the list lacks: "what're", "y'all".
    return None


def sentence_case(text):
    """Argos translates all-caps lettering badly; give it ordinary sentence case.

    Names stay capitalised (and so untranslated): dictionary proper nouns and words the
    dictionary does not know, such as invented names.
    """
    def word_case(match):
        word = match.group(0)
        kind = known(word) if dictionary() else True
        return word.capitalize() if kind is not True and len(word) > 1 else word.lower()

    text = re.sub(r"[A-Za-z][A-Za-z'’]*", word_case, text)
    text = re.sub(r"(^|[.!?]\s+)([a-z])", lambda m: m.group(1) + m.group(2).upper(), text)
    return re.sub(r"\bi\b", "I", text)


# Interjections the translation model leaves in English.
INTERJECTIONS = {"hey": "ei", "huh": "hã", "wow": "uau", "yeah": "é", "yep": "aham", "nope": "não",
                 "whoa": "opa", "oops": "opa", "ouch": "ai", "shh": "psiu", "gee": "nossa"}


def interjections(english, portuguese):
    """Translate interjections that came back untouched, keeping their capitalisation."""
    def swap(match):
        word = match.group(0)
        target = INTERJECTIONS[word.lower()]
        return target.upper() if word.isupper() else target.capitalize() if word[0].isupper() else target

    pattern = r"\b(" + "|".join(INTERJECTIONS) + r")\b"
    if not re.search(pattern, english, re.IGNORECASE):
        return portuguese
    return re.sub(pattern, swap, portuguese, flags=re.IGNORECASE)


def comic_dashes(text):
    """Comic lettering marks interruptions with "--"; Argos reads mid-sentence ones as breaks."""
    text = re.sub(r"^[\s.,\-—]*-{2,}\s*", "", text)
    text = re.sub(r"\s*-{2,}[\s\"”]*$", "...", text)
    return re.sub(r"\s*-{2,}\s*", " — ", text)


def balloon_interior(pixels, box, fill, height):
    """Mask of the flat fill around the text with the letters' holes filled: the balloon, not its outline."""
    reach = height * 4
    left, top = max(0, box[0] - reach), max(0, box[1] - reach)
    right, bottom = min(pixels.shape[1], box[2] + reach), min(pixels.shape[0], box[3] + reach)
    flat = numpy.abs(pixels[top:bottom, left:right].astype(numpy.int16) - numpy.array(fill)).max(axis=-1) < 40
    labels, _ = ndimage.label(flat)
    # The balloon is the flat region with the most pixels between the letters; other regions
    # the text box grazes (the panel behind a caption) are not part of it.
    touching = labels[box[1] - top:box[3] - top, box[0] - left:box[2] - left]
    ids, counts = numpy.unique(touching[touching > 0], return_counts=True)
    if not len(ids):
        return numpy.zeros_like(flat), (left, top)
    return ndimage.binary_fill_holes(labels == ids[counts.argmax()]), (left, top)


def plausible(words, text):
    """Average confidence, or failing that, mostly real words: bold or tilted lettering scores low."""
    confident = [conf for _, conf, _ in words if conf >= 0]
    if sum(c.isalpha() for c in text) < 2 or not confident:
        return False
    if sum(confident) / len(confident) >= 45:
        return True
    tokens = [t for t in re.findall(r"[A-Za-z][A-Za-z']+", text)]
    return bool(tokens) and dictionary() and sum(known(t) is not None for t in tokens) >= 0.75 * len(tokens)


def recognize(image):
    pixels = numpy.asarray(image)
    found_text = []
    pending = [(group, False) for group in group_lines(detect_lines(image))]
    while pending:
        lines, strict = pending.pop(0)
        box, height = bounds(lines), line_height(lines)
        found = balloon(pixels, lines, height)
        if found is None:
            if not strict and len(lines) > 1:
                pending += [(group, True) for group in split_by_balloon(pixels, lines)]
            continue
        fill, interior, window = found
        words = []
        for index, stack in enumerate(columns(lines)):
            words += [(text, conf, (index, *line)) for text, conf, line in
                      read_text(image, lettering_rows(stack, height), fill, height)]
        found_text.append((words, lines, box, height, fill, interior, window))
    # Lettering is consistent within a page: a balloon the OCR read partly in lower case on a
    # page lettered in capitals is treated, and set, as capitals too.
    shares = [capitals(words) for words, *_ in found_text]
    page_caps = shares and sum(share >= 0.6 for share in shares) >= 0.7 * len(shares)
    regions = []
    for share, (words, lines, box, height, fill, interior, window) in zip(shares, found_text):
        caps = share >= 0.6 or page_caps and share >= 0.3
        if caps:
            words = [(text.upper(), conf, line) for text, conf, line in words]
        text, caps = clean_text(words, caps)
        if not plausible(words, text):
            continue
        if caps:
            text = sentence_case(text)
        inside = numpy.concatenate([pixels[y0:y1, x0:x1].reshape(-1, 3) for x0, y0, x1, y1 in lines]).astype(numpy.int16)
        strokes = inside[numpy.abs(inside - numpy.array(fill)).max(axis=1) > 100]
        ink = tuple(int(c) for c in numpy.median(strokes, axis=0)) if len(strokes) else \
            ((0, 0, 0) if luminance(fill) >= 128 else (255, 255, 255))
        regions.append(Region(comic_dashes(text), box, interior, window, fill, ink, height, caps))
    return regions


def line_slots(region, top, count, step, pad):
    """(width, centre x) for each line band: the widest run of balloon across the text's columns.

    Centring each line in its own run follows ovals drawn off-centre and joined balloons."""
    ox, oy = region.window
    left, right = region.box[0] - ox, region.box[2] - ox
    slots = []
    for i in range(count):
        band = region.interior[top + i * step:top + (i + 1) * step].all(axis=0)
        edges = numpy.flatnonzero(numpy.diff(numpy.concatenate(([0], band.astype(numpy.int8), [0]))))
        runs = [(start, end) for start, end in zip(edges[::2], edges[1::2]) if end > left and start < right]
        start, end = max(runs, key=lambda run: run[1] - run[0], default=(0, 0))
        slots.append((end - start - 2 * pad, (start + end) / 2 + ox))
    return slots


def wrap(draw, words, widths, font, split):
    """Greedy fill of lines with individual widths; None when the words do not fit."""
    lines, line = [], ""
    for word in words:
        while True:
            if len(lines) == len(widths):
                return None
            candidate = f"{line} {word}".strip()
            if draw.textlength(candidate, font=font) <= widths[len(lines)]:
                line = candidate
                break
            if line:
                lines.append(line)
                line = ""
                continue
            if not split or len(word) == 1:
                return None
            # Split the word at the longest prefix that fits this line.
            cut = max((i for i in range(1, len(word)) if draw.textlength(word[:i], font=font) <= widths[len(lines)]),
                      default=0)
            if not cut:
                return None
            lines.append(word[:cut])
            word = word[cut:]
    if line:
        if len(lines) == len(widths):
            return None
        lines.append(line)
    return lines


def shape_text(draw, region, text, largest, smallest):
    """Letter the text the way the balloon is shaped: each line as wide as the balloon at its height."""
    rows = region.interior.shape[0]
    cy = (region.box[1] + region.box[3]) // 2 - region.window[1]
    pad = max(1, region.height // 6)
    words = text.split()
    for split in (False, True):
        for size in range(largest, smallest - 1, -1):
            font = ImageFont.truetype(FONT, size)
            ascent, descent = font.getmetrics()
            step = ascent + descent + max(1, size // 10)
            for count in range(1, rows // step + 1):
                top = cy - count * step // 2
                if top < 0 or top + count * step > rows:
                    break
                lines = wrap(draw, words, [width for width, _ in line_slots(region, top, count, step, pad)], font, split)
                if lines:
                    top += (count - len(lines)) * step // 2
                    slots = line_slots(region, top, len(lines), step, pad)
                    return font, [(slots[i][1], top + region.window[1] + i * step, line) for i, line in enumerate(lines)]
    return None


def fit_text(draw, text, width, height, largest=64, smallest=6):
    # Find the largest font that fits, shrinking before splitting a word across lines.
    for split in (False, True):
        for size in range(min(largest, max(smallest, height)), smallest - 1, -1):
            font = ImageFont.truetype(FONT, size)
            lines, line = [], ""
            for word in text.split():
                candidate = f"{line} {word}".strip()
                if draw.textlength(candidate, font=font) <= width:
                    line = candidate
                    continue
                if line:
                    lines.append(line)
                line = ""
                if not split:
                    line = word
                    continue
                for char in word:
                    if line and draw.textlength(line + char, font=font) > width:
                        lines.append(line)
                        line = ""
                    line += char
            if line:
                lines.append(line)
            wrapped = "\n".join(lines)
            bounds = draw.multiline_textbbox((0, 0), wrapped, font=font, spacing=1, align="center")
            if bounds[2] - bounds[0] <= width and bounds[3] - bounds[1] <= height:
                return wrapped, font, bounds
    raise ValueError("Texto traduzido não cabe na região reconhecida.")


def typeset(image, region, portuguese):
    draw = ImageDraw.Draw(image)
    # Repaint the balloon near the text (letters included), stopping at its outline.
    reach = region.height
    ox, oy = region.window
    left, top = max(0, region.box[0] - reach - ox), max(0, region.box[1] - reach - oy)
    right, bottom = region.box[2] + reach - ox, region.box[3] + reach - oy
    erase = numpy.zeros_like(region.interior)
    erase[top:bottom, left:right] = region.interior[top:bottom, left:right]
    image.paste(region.fill, (ox, oy, ox + erase.shape[1], oy + erase.shape[0]), Image.fromarray(erase))
    largest = max(8, round(region.height * 1.1))
    smallest = max(6, round(region.height * 0.55))
    shaped = shape_text(draw, region, portuguese, largest, smallest)
    if shaped:
        font, lines = shaped
        for x, y, line in lines:
            draw.text((x - draw.textlength(line, font=font) / 2, y), line, font=font, fill=region.ink)
        return
    # Too long even at the smallest legible size: let it run past the text area rather than drop it.
    left, top, right, bottom = region.box
    text, font, area = fit_text(draw, portuguese, max(right - left, region.height * 4), 10 ** 6, smallest, smallest)
    x = left + (right - left - (area[2] - area[0])) / 2 - area[0]
    y = top + (bottom - top - (area[3] - area[1])) / 2 - area[1]
    draw.multiline_text((x, y), text, font=font, fill=region.ink, spacing=1, align="center")


QUOTES = "\"“”"


def unquote(text):
    """Narration often quotes speech across captions; translators misplace those quote marks,
    so they are removed before translating and put back where the source had them."""
    opens = text.lstrip()[:1] in QUOTES
    closes = text.rstrip(" .,!?…")[-1:] in QUOTES
    inner = text.translate({ord(q): None for q in QUOTES}).strip()
    inner = re.sub(r"^[^A-Za-z]*[a-z]", lambda m: m.group(0).upper(), inner)
    if opens != closes and len(inner.split()) < 3:
        # Quotes left open for the next caption come with narration; on a short line it is OCR noise.
        opens = closes = False
    return opens, inner, closes


def requote(opens, portuguese, closes):
    portuguese = (portuguese or "").strip().translate({ord(q): None for q in QUOTES}).strip()
    if not portuguese:
        return ""
    return ("“" if opens else "") + portuguese + ("”" if closes else "")


def translate_quoted(text, translate):
    opens, inner, closes = unquote(text)
    return requote(opens, translate(inner), closes) if inner else ""


LLM_PROMPT = """You translate comic book pages from English into Brazilian Portuguese.
You receive the text of every balloon and caption of one page, in reading order, as a JSON list.
The text was read by OCR: fix obviously misread words using the context before translating.
Rules:
- Return exactly one translation per item, in the same order.
- Natural, colloquial Brazilian Portuguese (never European Portuguese: "Me faz rir", not "Faz-me rir"),
  as in a professionally translated comic. Keep each speaker's tone; casual speech uses "tá", "pra", imperatives like "Sai".
- Keep established English terms used in Brazil (royal flush, poker), and translate idioms by meaning, not word by word.
- Be consistent across the page: same terms, same form of address ("você").
- Keep character names, places and code names as they are (Batman, Gotham, Arkham, Bruce, Alfred...).
- Keep the punctuation style: "...", "—", "!", "?". Do not add quotes, notes or explanations.
- Translate sound words and interjections (Hey → Ei, Huh → Hã) unless they are names.
- Credits: Writer → Roteiro, Artist → Arte, Colors → Cores, Letterer → Letras, Editor → Editor.
- Keep translations about as short as the original; they must fit the same balloon."""


def llm_request(path, payload, timeout):
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(LLM_URL + path, data=data,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read() or b"{}")


def load_llm():
    """Checks the model is installed in Ollama, pulling it once (about 2.5 GB) if needed."""
    if not LLM_URL:
        return None
    try:
        tags = llm_request("/api/tags", None, 10)
        names = {model["name"] for model in tags.get("models", [])}
        if LLM_MODEL not in names and LLM_MODEL + ":latest" not in names:
            logging.info("Baixando modelo %s no Ollama; isso acontece somente uma vez.", LLM_MODEL)
            llm_request("/api/pull", {"model": LLM_MODEL, "stream": False}, 3600)
    except Exception as error:
        logging.warning("LLM %s indisponível em %s (%s); traduzindo com Argos.", LLM_MODEL, LLM_URL, error)
        return None
    logging.info("Traduzindo com o LLM %s.", LLM_MODEL)
    return translate_with_llm


def translate_with_llm(texts):
    """All balloons of a page in one request, so names and terms stay consistent."""
    schema = {"type": "object", "required": ["translations"], "properties": {
        "translations": {"type": "array", "items": {"type": "string"}, "minItems": len(texts), "maxItems": len(texts)}}}
    reply = llm_request("/api/chat", {
        "model": LLM_MODEL, "stream": False, "format": schema,
        # The model leaves the GPU together with the rest of the engine when idle.
        "keep_alive": f"{max(1, int(IDLE_SECONDS // 60))}m",
        "options": {"temperature": 0.2, "num_ctx": 4096},
        "messages": [{"role": "system", "content": LLM_PROMPT},
                     {"role": "user", "content": json.dumps(texts, ensure_ascii=False)}]}, 300)
    translations = json.loads(reply["message"]["content"])["translations"]
    if len(translations) != len(texts) or not all(isinstance(t, str) for t in translations):
        raise ValueError(f"LLM devolveu {len(translations)} traduções para {len(texts)} balões")
    return translations


def translate_texts(texts, translate, translate_many=None):
    """Page-level translation with the LLM; any failure falls back to Argos balloon by balloon."""
    if translate_many and texts:
        try:
            return translate_many(texts)
        except Exception as error:
            logging.warning("Falha no LLM (%s); traduzindo a página com Argos.", error)
    return [translate(text) for text in texts]


def translate_page(data: bytes, translate, translate_many=None) -> bytes:
    with warnings.catch_warnings():
        warnings.simplefilter("error", Image.DecompressionBombWarning)
        with Image.open(io.BytesIO(data)) as source:
            image = ImageOps.exif_transpose(source).convert("RGB")
    if image.width * image.height > Image.MAX_IMAGE_PIXELS:
        raise ValueError("Página excede 30 milhões de pixels.")
    regions = recognize(image)
    parts = [unquote(region.text) for region in regions]
    pending = [index for index, (_, inner, _) in enumerate(parts) if inner]
    translated = dict(zip(pending, translate_texts([parts[i][1] for i in pending], translate, translate_many)))
    for index, region in enumerate(regions):
        opens, _, closes = parts[index]
        portuguese = interjections(region.text, requote(opens, translated.get(index), closes))
        if not portuguese:
            logging.warning("Tradução vazia; mantendo o original: %r", region.text)
            continue
        if region.caps:
            portuguese = portuguese.upper()
        try:
            typeset(image, region, portuguese)
        except Exception:
            logging.exception("Falha ao diagramar %r; mantendo o original", region.text)
    output = io.BytesIO()
    image.save(output, format="PNG", compress_level=3)
    return output.getvalue()


def load_translator():
    socket.setdefaulttimeout(60)
    import argostranslate.package
    import argostranslate.translate

    installed = argostranslate.package.get_installed_packages()
    if not any(p.from_code == "en" and p.to_code == "pb" for p in installed):
        logging.info("Baixando modelo inglês → português brasileiro; isso acontece somente no primeiro uso do volume.")
        argostranslate.package.update_package_index()
        packages = argostranslate.package.get_available_packages()
        package = next((p for p in packages if p.from_code == "en" and p.to_code == "pb"), None)
        if package is None:
            raise RuntimeError("Modelo direto en → pb (PT-BR) indisponível no índice Argos.")
        argostranslate.package.install_from_path(download_model(package))
    languages = argostranslate.translate.get_installed_languages()
    source = next(lang for lang in languages if lang.code == "en")
    target = next(lang for lang in languages if lang.code == "pb")
    model = source.get_translation(target)

    @lru_cache(maxsize=2048)
    def translate(text):
        # One model in RAM shared by any threads of the engine process.
        with MODEL_LOCK:
            return model.translate(text)

    translate("Hello.")  # Warm the model before accepting requests.
    return translate


def download_model(package):
    """Try the official distribution server and catalog URLs, with visible progress.

    The catalog's argos-net host can be unreachable. Stream into a partial file
    rather than buffering the complete model in memory or caching a failed download.
    """
    filename = f"translate-en_pb-{package.package_version.replace('.', '_')}.argosmodel"
    cache = Path(os.environ.get("XDG_CACHE_HOME", "/tmp")) / "mangaverse-models"
    cache.mkdir(parents=True, exist_ok=True)
    output = cache / filename
    if output.exists():
        return output
    urls = [f"https://data.argosopentech.com/argospm/v1/{filename}", *package.links]
    for url in dict.fromkeys(urls):
        if not url.startswith("https://"):
            continue
        partial = output.with_suffix(".part")
        try:
            logging.info("Obtendo modelo de %s", url)
            request = urllib.request.Request(url, headers={"User-Agent": "MangaVerse-Translator/1.0"})
            with urllib.request.urlopen(request, timeout=20) as response, partial.open("wb") as stream:
                total = 0
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    if total > 1024 * 1024 * 1024:
                        raise ValueError("Modelo excede 1 GB")
                    stream.write(chunk)
                    if total % (10 * 1024 * 1024) == 0:
                        logging.info("Modelo: %d MB baixados", total // (1024 * 1024))
            partial.replace(output)
            return output
        except Exception as error:
            partial.unlink(missing_ok=True)
            logging.warning("Falha no servidor de modelos %s: %s", url, error)
    raise RuntimeError("Não foi possível obter o modelo PT-BR dos servidores oficiais. Tente reiniciar translator mais tarde.")


def load_models():
    """Runs in the engine process: everything heavy lives there."""
    global DETECTOR, TRANSLATE, TRANSLATE_MANY
    logging.basicConfig(level=logging.INFO)
    DETECTOR = load_detector()
    TRANSLATE = load_translator()
    TRANSLATE_MANY = load_llm()


def engine_translate(data, threads=None):
    if threads:
        # Chosen in MangaVerse's settings; applies from this page on, without reloading models.
        import torch
        torch.set_num_threads(threads)
    return translate_page(data, TRANSLATE, TRANSLATE_MANY)


class Engine:
    """Models live in a child process that exits after IDLE_SECONDS without pages, returning
    all of its memory (1–4 GB) to the system; the next page starts it again in a few seconds."""

    def __init__(self, initializer=load_models):
        self.initializer = initializer
        self.lock = threading.Lock()
        self.pool = None
        self.busy = 0
        self.last = time.monotonic()
        threading.Thread(target=self.unload_when_idle, daemon=True).start()

    def start(self):
        with self.lock:
            if self.pool is None:
                logging.info("Carregando modelos de tradução.")
                self.pool = ProcessPoolExecutor(1, multiprocessing.get_context("spawn"), initializer=self.initializer)
            return self.pool

    def run(self, function, *args):
        pool = self.start()
        with self.lock:
            self.busy += 1
        try:
            return pool.submit(function, *args).result()
        except BrokenProcessPool:
            # The engine died (for example, out of memory); start a fresh one next time.
            with self.lock:
                if self.pool is pool:
                    self.pool = None
            raise
        finally:
            with self.lock:
                self.busy -= 1
                self.last = time.monotonic()

    def translate(self, data, threads=None):
        return self.run(engine_translate, data, threads)

    def loaded(self):
        return self.pool is not None

    def unload_when_idle(self):
        while True:
            time.sleep(min(30, IDLE_SECONDS))
            with self.lock:
                if self.pool is None or self.busy or time.monotonic() - self.last < IDLE_SECONDS:
                    continue
                pool, self.pool = self.pool, None
            pool.shutdown(wait=True)
            logging.info("Tradutor ocioso; modelos descarregados da memória.")


ENGINE_NAME = (f"{LLM_MODEL}+argos" if LLM_URL else "argos").encode() + b"-easyocr-tesseract"


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        loaded = b"true" if self.server.engine.loaded() else b"false"
        self.wfile.write(b'{"ready":true,"loaded":' + loaded + b',"source":"en","target":"pt-br","engine":"' + ENGINE_NAME + b'"}')

    def do_POST(self):
        if self.path != "/translate":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            threads = int(self.headers.get("X-Threads", "0")) or None
        except ValueError:
            self.send_error(400)
            return
        if threads is not None:
            threads = max(1, min(threads, os.cpu_count() or 1))
        if not 0 < length <= MAX_BODY:
            self.send_error(413)
            return
        if not SLOTS.acquire(blocking=False):
            self.send_error(503, "Tradutor ocupado")
            return
        try:
            self.connection.settimeout(120)
            data = self.rfile.read(length)
            if len(data) != length:
                raise ValueError("Imagem incompleta")
            output = self.server.engine.translate(data, threads)
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(output)))
            self.end_headers()
            self.wfile.write(output)
        except Exception:
            logging.exception("Falha ao traduzir página")
            self.send_error(422, "Falha no reconhecimento ou na tradução da página")
        finally:
            SLOTS.release()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    engine = Engine()
    # Load once at startup so a first-time model download happens now, not on the first page.
    engine.run(int)
    server = ThreadingHTTPServer(("0.0.0.0", 8091), Handler)
    server.engine = engine
    logging.info("Tradutor local pronto na porta 8091; modelos são descarregados após %d min sem uso.", IDLE_SECONDS // 60)
    server.serve_forever()
