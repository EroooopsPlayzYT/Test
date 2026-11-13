import bisect
import random
import unittest

from analysis import algorithms as alg
from analysis.catalog import classify, normalize
from analysis.parse import parse_title, variant


class BinarySearch(unittest.TestCase):
    def test_bounds_match_bisect(self):
        rng = random.Random(1)
        for _ in range(200):
            a = sorted(rng.randint(0, 50) for _ in range(rng.randint(0, 40)))
            x = rng.randint(-5, 55)
            self.assertEqual(alg.lower_bound(a, x), bisect.bisect_left(a, x))
            self.assertEqual(alg.upper_bound(a, x), bisect.bisect_right(a, x))

    def test_budget_band_percentile(self):
        rows = [{"price_try": p} for p in (7500, 8499, 12500, 13000, 24000, 24000)]
        self.assertEqual(len(alg.under_budget(rows, 13000)), 4)
        self.assertEqual(len(alg.price_band(rows, 8000, 20000)), 3)
        self.assertEqual(alg.percentile_rank([p["price_try"] for p in rows], 12500), 2 / 6)

    def test_max_price_for_deal(self):
        self.assertAlmostEqual(alg.max_price_for_deal(24000, 0.10), 21600, delta=1.5)


class Parsing(unittest.TestCase):
    def test_variants(self):
        self.assertEqual(variant("Retroid Pocket 6 8/128"), (8, 128))
        self.assertEqual(variant("Retroid Pocket 6 - 8GB Ram - 256GB Dahili Hafıza"), (8, 256))
        self.assertEqual(variant("RP6 12GB+256GB"), (12, 256))
        self.assertEqual(variant("Retroid Pocket 6 [12GB/128GB]"), (12, 128))
        self.assertEqual(variant("AYN Odin 2 Max 16/1TB"), (16, 1024))
        self.assertEqual(variant("Retroid Pocket 6"), (None, None))

    def test_flags(self):
        p = parse_title("Sıfır Ayarında Retroid Pocket 3+ El Konsolu")
        self.assertEqual(p["condition"], "used")
        self.assertTrue(parse_title("Retroid Pocket 5 Grip Kılıf")["is_accessory"])
        self.assertTrue(parse_title("[SATILDI] Steam Deck OLED")["sold"])
        self.assertTrue(parse_title("Nintendo Switch 2 + Mario Kart Bundle")["bundle"])


class Categorising(unittest.TestCase):
    def test_catalog(self):
        self.assertEqual(classify("Retroid pocket 6 8/128")[0], "Retroid Pocket 6")
        self.assertEqual(classify("AYN Odin 2 Portal 8/128")[0], "AYN Odin 2 Portal")
        self.assertEqual(classify("Anbernic RG 557 Beyaz")[0], "Anbernic RG557")
        self.assertEqual(classify("RG556 Android Retro")[0], "Anbernic RG556")
        self.assertEqual(classify("Valve Steam Deck Oled 512 GB")[0], "Steam Deck OLED")
        self.assertEqual(classify("Powkıddy RGB20S 64GB")[0], "Powkiddy")
        self.assertEqual(normalize("Taşınabilir"), "tasinabilir")


if __name__ == "__main__":
    unittest.main()
