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

    def test_reviewer_bugs(self):
        # "SIFIR KAPALI KUTU" on a forum is new, not used
        self.assertEqual(parse_title("##SATILIK## LEGION GO 2 - SIFIR - KAPALI KUTU", "used")["condition"], "new")
        self.assertEqual(parse_title("SIFIR GİBİ NİNTENDO SWİTCH OLED", "used")["condition"], "used")
        # want-to-buy posts are not listings
        self.assertTrue(parse_title("ALINIK GBA SP")["wanted"])
        self.assertTrue(parse_title("Retroid Pocket 6 arıyorum")["wanted"])
        self.assertFalse(parse_title("Retroid Pocket 6 satılık")["wanted"])


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


class JunkFilters(unittest.TestCase):
    """Real titles that slipped into the data before; each must stay out."""

    def test_not_handhelds(self):
        for t in ["MOZDE Belmo Odin Oval Aynalı Kapaklı Lavabo Üstü Banyo Dolabı",   # bathroom cabinet
                  "GPD Ritmo Tek Gövde Lavabo Bataryası",                            # faucet
                  "İzoly Odin İ3 12100F 32Gb Ddr4 Ram 1Tb Nvme M.2 Ssd Rtx4060",     # desktop PC
                  "DJI Osmo Pocket 4P Vlog Combo Gimbal",                             # camera gimbal
                  "2 Adet Lav Odin Çay Seti 6 Kişilik",                               # tea set
                  "Switch 2 pro kol",                                                 # controller
                  "Nintendo Mariokart World Switch 2",                                # game
                  "Nintendo Switch - Mario Pop  Exclusive Series Gamepad"]:          # controller
            self.assertTrue(parse_title(t)["is_accessory"] or classify(t)[0] is None, t)

    def test_books_are_not_ayn(self):
        for t in ["Kedimin Adı Odin 2 - Kara Goblinler Kraliçesi", "Atlas Shrugged by Ayn Rand"]:
            self.assertIsNone(classify(t)[0], t)

    def test_clones_are_clones(self):
        for t in ["Nessiworld Sup Mini Psp 400 Oyun Konsolu", "QASUL G9 3.0 İnç Ekran Oyun Konsolu",
                  "Retro El Atarisi 3.5 Hd Ekran Psp Ps1 Mario Gta Fıfa Destekli 10.000 Oyun Konsolu",
                  "Profesyone 5 Inc Full Hd Ekranlı 3D 20000 Oyunlu Joysticek RG50X Konsol"]:
            self.assertEqual(classify(t)[0], "Retro clone", t)

    def test_real_devices_survive(self):
        cases = {"R36S 15000 Oyunlu Retro Konsol": "R36S", "Anbernic RG35XX Plus 5000 oyunlu": "Anbernic RG35XX",
                 "Sony PSP 3004 Konsol": "PSP", "AYN Odin 2 Portal 8/128": "AYN Odin 2 Portal",
                 "Odin 2 Mini 12GB 256GB": "AYN Odin 2 Mini", "Nintendo Switch 2 + Mario Kart World Bundle": "Nintendo Switch 2"}
        for t, m in cases.items():
            self.assertEqual(classify(t)[0], m, t)
            self.assertFalse(parse_title(t)["is_accessory"], t)


class ReviewRound2(unittest.TestCase):
    def test_sold_markers(self):
        for t in ["STEAM DECK OLED [ALINDI]", "[SATILDI] RG556", "Switch Lite - KAPANDI"]:
            self.assertTrue(parse_title(t)["sold"], t)
        self.assertFalse(parse_title("ALINIK GBA SP")["sold"])   # wanted, not sold

    def test_turkish_hint(self):
        self.assertEqual(parse_title("Retroid Pocket 4 pro", "İkinci El")["condition"], "used")

    def test_canonical_source_and_url(self):
        from analysis.run import canonical_source, canonical_url
        self.assertEqual(canonical_source("forum.donanimhaber.com"), "donanimhaber")
        self.assertEqual(canonical_source("akakce/Hepsiburada/Mini More"), "Hepsiburada/Mini More")
        self.assertEqual(canonical_source("idefix.com"), canonical_source("idefix"))
        self.assertEqual(canonical_url("https://forum.donanimhaber.com/x--1?a=1"),
                         canonical_url("http://www.forum.donanimhaber.com/x--1"))
