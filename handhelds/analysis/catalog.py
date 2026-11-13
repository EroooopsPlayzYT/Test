"""Handheld catalogue: canonical model names, brand and category, matched by regex.

Order matters: the first pattern that matches a (lowercased) title wins, so specific
models come before generic ones. Priority models (RP6 8/12GB first, then AYN, Retroid,
Anbernic RG556/RG557) are marked with a priority rank; lower is more important.
"""
import re

PC = "PC handheld (x86)"
ANDROID = "Android handheld"
LINUX = "Linux retro handheld"
NINTENDO = "Nintendo"
STREAM = "Streaming handheld"
SONY = "Sony legacy"
CLONE = "Retro clone / mini"
PHONE = "Phone controller"

# (canonical model, brand, category, regex, priority or None)
CATALOG = [
    # --- priority: Retroid Pocket 6 ---
    ("Retroid Pocket 6", "Retroid", ANDROID, r"retroid\s*pocket\s*6|\brp\s*6\b", 2),
    # --- AYN ---
    ("AYN Odin 3", "AYN", ANDROID, r"odin\s*3", 3),
    ("AYN Odin 2 Portal", "AYN", ANDROID, r"odin\s*2\s*portal", 3),
    ("AYN Odin 2 Mini", "AYN", ANDROID, r"odin\s*2\s*mini", 3),
    ("AYN Odin 2 Max", "AYN", ANDROID, r"odin\s*2\s*max", 3),
    ("AYN Odin 2", "AYN", ANDROID, r"odin\s*2", 3),
    ("AYN Odin Lite", "AYN", ANDROID, r"odin\s*lite", 3),
    ("AYN Thor", "AYN", ANDROID, r"\bayn\b.*\bthor\b|\bthor\b.*\bayn\b", 3),
    ("AYN Loki", "AYN", PC, r"\bloki\b", 3),
    ("AYN Odin", "AYN", ANDROID, r"\bodin\b", 3),
    # --- Retroid ---
    ("Retroid Pocket 5", "Retroid", ANDROID, r"retroid\s*pocket\s*5|\brp\s*5\b", 4),
    ("Retroid Pocket Flip 2", "Retroid", ANDROID, r"retroid\s*pocket\s*flip\s*2", 4),
    ("Retroid Pocket Flip", "Retroid", ANDROID, r"retroid\s*pocket\s*flip", 4),
    ("Retroid Pocket Mini V2", "Retroid", ANDROID, r"retroid\s*pocket\s*mini\s*v\s*2", 4),
    ("Retroid Pocket Mini", "Retroid", ANDROID, r"retroid\s*pocket\s*mini", 4),
    ("Retroid Pocket Classic", "Retroid", ANDROID, r"retroid\s*pocket\s*classic", 4),
    ("Retroid Pocket 4 Pro", "Retroid", ANDROID, r"retroid\s*pocket\s*4\s*pro", 4),
    ("Retroid Pocket 4", "Retroid", ANDROID, r"retroid\s*pocket\s*4", 4),
    ("Retroid Pocket 3+", "Retroid", ANDROID, r"retroid\s*pocket\s*3\s*(\+|plus)", 4),
    ("Retroid Pocket 3", "Retroid", ANDROID, r"retroid\s*pocket\s*3", 4),
    ("Retroid Pocket 2S", "Retroid", ANDROID, r"retroid\s*pocket\s*2\s*s", 4),
    ("Retroid (other)", "Retroid", ANDROID, r"retroid", 4),
    # --- Anbernic priority ---
    ("Anbernic RG557", "Anbernic", ANDROID, r"rg\s*-?\s*557", 5),
    ("Anbernic RG556", "Anbernic", ANDROID, r"rg\s*-?\s*556", 5),
    # --- other Anbernic ---
    ("Anbernic RG477M", "Anbernic", ANDROID, r"rg\s*-?\s*477", None),
    ("Anbernic RG476H", "Anbernic", ANDROID, r"rg\s*-?\s*476", None),
    ("Anbernic RG406", "Anbernic", ANDROID, r"rg\s*-?\s*406", None),
    ("Anbernic RG405", "Anbernic", ANDROID, r"rg\s*-?\s*405", None),
    ("Anbernic RG DS", "Anbernic", ANDROID, r"rg\s*-?\s*ds\b", None),
    ("Anbernic RG Slide", "Anbernic", ANDROID, r"rg\s*-?\s*slide", None),
    ("Anbernic RG55G1", "Anbernic", ANDROID, r"rg\s*-?\s*55\s*g", None),
    ("Anbernic RG Rotate", "Anbernic", LINUX, r"rg\s*-?\s*rotate", None),
    ("Anbernic RG35XX", "Anbernic", LINUX, r"rg\s*-?\s*35\s*xx", None),
    ("Anbernic RG34XX", "Anbernic", LINUX, r"rg\s*-?\s*34\s*xx", None),
    ("Anbernic RG40XX", "Anbernic", LINUX, r"rg\s*-?\s*40\s*xx", None),
    ("Anbernic RG28XX", "Anbernic", LINUX, r"rg\s*-?\s*28\s*xx", None),
    ("Anbernic RG CubeXX", "Anbernic", LINUX, r"cube\s*xx", None),
    ("Anbernic (other)", "Anbernic", LINUX, r"anbernic|\brg\s*\d{2,3}", None),
    # --- PC handhelds ---
    ("Steam Deck OLED", "Valve", PC, r"steam\s*deck.*oled|oled.*steam\s*deck", None),
    ("Steam Deck LCD", "Valve", PC, r"steam\s*deck", None),
    ("ROG Xbox Ally X", "Asus", PC, r"xbox\s*ally\s*x", None),
    ("ROG Xbox Ally", "Asus", PC, r"xbox\s*ally", None),
    ("ROG Ally X", "Asus", PC, r"rog\s*ally\s*x\b", None),
    ("ROG Ally", "Asus", PC, r"rog\s*ally|asus.*ally", None),
    ("Legion Go 2", "Lenovo", PC, r"legion\s*go\s*2", None),
    ("Legion Go S", "Lenovo", PC, r"legion\s*go\s*s\b", None),
    ("Legion Go", "Lenovo", PC, r"legion\s*go", None),
    ("MSI Claw 8 AI+", "MSI", PC, r"claw\s*8", None),
    ("MSI Claw 7 AI+", "MSI", PC, r"claw\s*7", None),
    ("MSI Claw A8", "MSI", PC, r"claw\s*a8", None),
    ("MSI Claw", "MSI", PC, r"msi\s*claw|\bclaw\b", None),
    ("Ayaneo Pocket", "Ayaneo", ANDROID, r"aya\s*neo.*pocket|ayoneo.*pocket", None),
    ("Ayaneo", "Ayaneo", PC, r"aya\s*neo|ayoneo", None),
    # --- Nintendo / Sony / streaming ---
    ("Nintendo Switch 2", "Nintendo", NINTENDO, r"switch\s*2", None),
    ("Nintendo Switch OLED", "Nintendo", NINTENDO, r"switch.*oled", None),
    ("Nintendo Switch Lite", "Nintendo", NINTENDO, r"switch\s*lite", None),
    ("Nintendo Switch", "Nintendo", NINTENDO, r"nintendo\s*switch|\bswitch\b", None),
    ("Nintendo 3DS/2DS", "Nintendo", NINTENDO, r"\b[23]\s*ds\b|new\s*[23]ds", None),
    ("PlayStation Portal", "Sony", STREAM, r"playstation\s*portal|ps\s*portal|portal\s*remote", None),
    ("PS Vita", "Sony", SONY, r"\bvita\b", None),
    ("PSP", "Sony", SONY, r"\bpsp\b", None),
    # --- Linux / clones ---
    ("Miyoo", "Miyoo", LINUX, r"miyoo", None),
    ("Powkiddy", "Powkiddy", LINUX, r"powkiddy", None),
    ("TrimUI", "TrimUI", LINUX, r"trim\s*ui", None),
    ("R36S", "Generic", LINUX, r"\br3[56]\s*(s|max|h)\b|\br36s\b", None),
    ("Phone controller", "Various", PHONE, r"gamesir|telefon\s*oyun|backbone|kishi|\bx5s\b", None),
    ("Retro clone", "Generic", CLONE,
     r"el\s*atari|retro|game\s*box|\bsup\b|oyunlu|nostalji|mini\s*oyun|gameboy|game\s*boy|atari|ateri|"
     r"gamepad|el\s*tipi|tasinabilir.*(konsol|oyun)|oyun\s*konsolu|game\s*console|klasik\s*oyun|\bs10\b|\bg5\b|"
     r"\bm18\b|\bj36\b|\bxgb35\b|dahili\s*oyun|tv\s*baglanti", None),
]
_COMPILED = [(m, b, c, re.compile(rx), p) for m, b, c, rx, p in CATALOG]


_TR = str.maketrans("ıİşŞçÇğĞöÖüÜ", "iissccggoouu")


def normalize(text):
    """Lowercase and fold Turkish letters, so 'Powkıddy' matches 'powkiddy'."""
    return (text or "").translate(_TR).lower()


def classify(title):
    """-> (model, brand, category, catalog_priority) or (None, None, None, None)."""
    low = normalize(title)
    for model, brand, cat, rx, prio in _COMPILED:
        if rx.search(low):
            return model, brand, cat, prio
    return None, None, None, None


def tier(price):
    """Price tier, TRY."""
    if price is None:
        return None
    for limit, name in ((2500, "budget"), (10000, "entry"), (25000, "mid"), (50000, "high")):
        if price < limit:
            return name
    return "premium"
