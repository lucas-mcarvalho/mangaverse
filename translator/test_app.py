import io
import time
from pathlib import Path
import unittest
from unittest.mock import patch

import numpy
from PIL import Image, ImageDraw, ImageFont

import app


class TranslationPageTest(unittest.TestCase):
    def setUp(self):
        if not Path(app.FONT).exists():
            self.font = patch.object(app, "FONT", "/usr/share/fonts/TTF/DejaVuSans.ttf")
            self.font.start()
            self.addCleanup(self.font.stop)

    def page(self, text="HELLO WORLD.", background="#3a6ea5"):
        """A white oval balloon with lettering on a coloured panel."""
        image = Image.new("RGB", (900, 350), background)
        draw = ImageDraw.Draw(image)
        draw.ellipse((60, 60, 480, 220), fill="white", outline="black", width=4)
        if text:
            draw.text((140, 120), text, fill="black", font=ImageFont.truetype(app.FONT, 36))
        return image

    def encode(self, image):
        data = io.BytesIO()
        image.save(data, format="PNG")
        return data.getvalue()

    def test_detected_balloon_is_read_translated_and_lettered_inside_its_outline(self):
        if app.DETECTOR is None:
            try:
                app.DETECTOR = app.load_detector()
            except Exception as error:  # Models are downloaded in the container on first start.
                self.skipTest(f"Detector de texto indisponível: {error}")
        original = self.page()
        seen = []

        def translate(text):
            seen.append(text)
            return "Olá mundo."

        output = Image.open(io.BytesIO(app.translate_page(self.encode(original), translate))).convert("RGB")
        self.assertEqual(seen, ["Hello world."])
        self.assertEqual(output.size, original.size)
        self.assertEqual(original.crop((500, 0, 900, 350)).tobytes(), output.crop((500, 0, 900, 350)).tobytes())
        # Outline and panel around the balloon are untouched; the lettering changed.
        self.assertEqual(output.getpixel((60, 140)), original.getpixel((60, 140)))
        self.assertNotEqual(original.crop((140, 120, 400, 170)).tobytes(), output.crop((140, 120, 400, 170)).tobytes())
        self.assertTrue(any(output.getpixel((x, y)) == (0, 0, 0) for x in range(100, 440) for y in range(100, 180)))

    def test_art_page_without_text_needs_no_translation(self):
        with patch.object(app, "detect_lines", return_value=[]):
            output = app.translate_page(self.encode(self.page("")), lambda _: self.fail("No text to translate"))
        self.assertEqual(Image.open(io.BytesIO(output)).size, (900, 350))

    def test_text_over_artwork_is_left_untouched(self):
        image = Image.new("RGB", (400, 200))
        noise = numpy.random.default_rng(1).integers(0, 255, (200, 400, 3), dtype=numpy.uint8)
        image.paste(Image.fromarray(noise))
        ImageDraw.Draw(image).text((100, 80), "KRAKA", fill="white", font=ImageFont.truetype(app.FONT, 36))
        with patch.object(app, "detect_lines", return_value=[(98, 82, 220, 120)]):
            output = app.translate_page(self.encode(image), lambda _: self.fail("Sound effects stay as drawn"))
        self.assertEqual(Image.open(io.BytesIO(output)).convert("RGB").tobytes(), image.tobytes())

    def test_empty_translation_keeps_the_original_lettering(self):
        original = self.page()
        region = app.Region("Hello", (140, 120, 400, 160), numpy.ones((350, 900), bool), (0, 0),
                            (255, 255, 255), (0, 0, 0), 36)
        with patch.object(app, "recognize", return_value=[region]):
            output = app.translate_page(self.encode(original), lambda _: " ")
        self.assertEqual(Image.open(io.BytesIO(output)).convert("RGB").tobytes(), original.tobytes())

    def test_invalid_image_is_rejected(self):
        with self.assertRaises(Exception):
            app.translate_page(b"not an image", lambda _: "Oi")

    def test_long_translation_wraps_without_clipping(self):
        draw = ImageDraw.Draw(Image.new("RGB", (300, 200)))
        text, font, bounds = app.fit_text(draw, "Uma tradução muito mais longa que o texto original em inglês.", 140, 65)
        self.assertLessEqual(bounds[2] - bounds[0], 140)
        self.assertLessEqual(bounds[3] - bounds[1], 65)
        self.assertGreaterEqual(font.size, 4)
        self.assertIn("\n", text)

    def test_shaped_lettering_follows_the_balloon(self):
        interior = numpy.zeros((200, 300), bool)
        yy, xx = numpy.mgrid[:200, :300]
        interior[((xx - 150) / 140) ** 2 + ((yy - 100) / 90) ** 2 <= 1] = True
        region = app.Region("x", (90, 80, 210, 120), interior, (0, 0), (255, 255, 255), (0, 0, 0), 20)
        draw = ImageDraw.Draw(Image.new("RGB", (300, 200)))
        font, lines = app.shape_text(draw, region, "Estou tentando te poupar da dor que passei", 22, 11)
        self.assertGreater(len(lines), 1)
        for x, y, line in lines:
            width = draw.textlength(line, font=font)
            for px in (round(x - width / 2), round(x + width / 2) - 1):
                self.assertTrue(interior[y, px] and interior[min(199, y + font.size), px], line)

    def test_comic_lettering_cleanup(self):
        words = [("SEEZ", 80, 1), ("HIS", 90, 1), ("LEFT", 90, 1), ("\\S", 60, 2), ("NOT", 90, 2),
                 ("70", 70, 2), ("B/BBO/", 70, 3), ("HIM.", 0, 3), ("ff", 80, 3)]
        text, caps = app.clean_text(words)
        self.assertTrue(caps)
        self.assertEqual(text, "SEE? HIS LEFT IS NOT TO BIBBO! HIM.")

    def test_names_keep_capitals_when_caps_lettering_is_lowered(self):
        if not app.dictionary():
            self.skipTest("Dicionário wamerican ausente no host; incluído no Dockerfile do tradutor.")
        self.assertEqual(app.sentence_case("HE'S MY SON, JIM. I KNOW BIBBO."), "He's my son, Jim. I know Bibbo.")
        self.assertEqual(app.clean_text([("STAFF", 90, 1), ("CONTIDENT", 80, 1)]), ("STAFF CONFIDENT", True))

    def test_misreads_typical_of_this_lettering(self):
        if not app.dictionary():
            self.skipTest("Dicionário wamerican ausente no host; incluído no Dockerfile do tradutor.")
        cases = {"WHAT DID HE OFFER YOU2": "WHAT DID HE OFFER YOU?", "T'M CALLING BECAUSE T CAN'T": "I'M CALLING BECAUSE I CAN'T",
                 "PFFT. TELLITAGAIN.": "PFFT. TELL IT AGAIN.", "HE'S #$%^ EVIL": "HE'S EVIL", "BON'T PUSH IT.": "DON'T PUSH IT.",
                 "THAT'S BIBBO.": "THAT'S BIBBO."}
        for source, expected in cases.items():
            self.assertEqual(app.clean_text([(word, 90, 1) for word in source.split()])[0], expected)

    def test_caption_quotes_are_kept_where_the_source_had_them(self):
        bracket = lambda text: f"[{text}]"
        self.assertEqual(app.translate_quoted('"frankly, we\'re all scared."', bracket), "“[Frankly, we're all scared.]”")
        self.assertEqual(app.translate_quoted('"he said so.', bracket), "“[He said so.]")
        self.assertEqual(app.translate_quoted('"hey yourself.', bracket), "[Hey yourself.]")

    def test_untranslated_interjections_are_translated(self):
        self.assertEqual(app.interjections("Hey.", "Hey."), "Ei.")
        self.assertEqual(app.interjections("HEY, BRUCE!", "HEY, BRUCE!"), "EI, BRUCE!")
        self.assertEqual(app.interjections("They said yes.", "Eles disseram sim."), "Eles disseram sim.")

    def test_comic_dashes_do_not_split_sentences(self):
        self.assertEqual(app.comic_dashes("--Coming for you, Bib--"), "Coming for you, Bib...")
        self.assertEqual(app.comic_dashes("sutures--and grafts--are"), "sutures — and grafts — are")

    def test_page_is_translated_by_the_llm_in_one_request_keeping_quotes(self):
        regions = [app.Region(text, (140, 120, 400, 160), numpy.ones((350, 900), bool), (0, 0),
                              (255, 255, 255), (0, 0, 0), 36) for text in ('"We\'re all scared."', "Hey, Bruce!")]
        requests = []

        def many(texts):
            requests.append(texts)
            return ["Todos estamos com medo.", "Ei, Bruce!"]

        drawn = []
        with patch.object(app, "recognize", return_value=regions), \
                patch.object(app, "typeset", lambda image, region, text: drawn.append(text)):
            app.translate_page(self.encode(self.page("")), lambda _: self.fail("Argos is only a fallback"), many)
        self.assertEqual(requests, [["We're all scared.", "Hey, Bruce!"]])
        self.assertEqual(drawn, ["“Todos estamos com medo.”", "Ei, Bruce!"])

    def test_llm_failure_or_wrong_count_falls_back_to_argos(self):
        argos = lambda text: f"pt:{text}"
        self.assertEqual(app.translate_texts(["A", "B"], argos, lambda texts: 1 / 0), ["pt:A", "pt:B"])
        reply = {"message": {"content": '{"translations": ["só uma"]}'}}
        with patch.object(app, "llm_request", return_value=reply):
            self.assertEqual(app.translate_texts(["A", "B"], argos, app.translate_with_llm), ["pt:A", "pt:B"])

    def test_missing_llm_model_is_pulled_once_and_unreachable_ollama_means_argos(self):
        calls = []

        def request(path, payload, timeout):
            calls.append(path)
            return {"models": [{"name": "outro:latest"}]} if path == "/api/tags" else {}

        with patch.object(app, "LLM_URL", "http://ollama:11434"), patch.object(app, "llm_request", request):
            self.assertIs(app.load_llm(), app.translate_with_llm)
        self.assertEqual(calls, ["/api/tags", "/api/pull"])
        with patch.object(app, "LLM_URL", "http://ollama:11434"), \
                patch.object(app, "llm_request", side_effect=OSError("recusado")):
            self.assertIsNone(app.load_llm())
        with patch.object(app, "LLM_URL", ""):
            self.assertIsNone(app.load_llm())


class EngineTest(unittest.TestCase):
    def test_engine_process_is_released_after_idle_time_and_restarted_on_demand(self):
        with patch.object(app, "IDLE_SECONDS", 0.2):
            engine = app.Engine(initializer=None)
            self.assertFalse(engine.loaded())
            self.assertEqual(engine.run(abs, -3), 3)
            self.assertTrue(engine.loaded())
            for _ in range(50):
                if not engine.loaded():
                    break
                time.sleep(0.1)
            self.assertFalse(engine.loaded())
            self.assertEqual(engine.run(abs, -4), 4)


if __name__ == "__main__":
    unittest.main()
