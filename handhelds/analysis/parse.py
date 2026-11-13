"""Title parsing: RAM / storage, condition, sold, bundle and accessory flags."""
import re

ACCESSORY = re.compile(
    r"kılıf|kilif|çanta|canta|koruyucu|temperli|\bcam\b|\bgrip\b|stand|şarj|sarj|kablo|adaptör|adaptor|"
    r"thumb|analog\s*(başlık|koruyucu)|sticker|kaplama|\bcase\b|\bskin\b|film|\bdock\b|silikon|joy-?con|"
    r"kontrolc|oyun\s*kolu|amiibo|hediye\s*kart|yedek\s*parça|batarya\b|tuş\s*takımı|sadece\s*kılıf", re.I)
GAME = re.compile(r"\boyunu\b|\bswitch\s*oyun\b|\bps5\b.*oyun|edition\b(?!.*konsol)", re.I)
USED = re.compile(r"ikinci\s*el|2\.\s*el|\bused\b|az\s*kullan|temiz|kullanılmış|kullanilmis|"
                  r"s[ıi]f[ıi]r\s*(ayar|gibi)", re.I)
NEW = re.compile(r"\bsıfır\b(?!\s*ayar)|\bsifir\b(?!\s*ayar)|kapalı\s*kutu|\bnew\b|faturalı|garantili", re.I)
NOT_HANDHELD = re.compile(r"lavabo|banyo|dolab[ıi]|mobilya|yatak|koltuk|masa\b|sandalye|halı|perde|ayakkab|"
                          r"elbise|tişört|parfüm|mutfak|tencere", re.I)
WANTED = re.compile(r"al[ıi]n[ıi]k|al[ıi]n[ıi]r\b|ar[ıi]yorum|aran[ıi]yor|\bwtb\b|almak\s*istiyorum", re.I)
NEW_STRONG = re.compile(r"\bs[ıi]f[ıi]r\b(?!\s*(ayar|gibi))|kapal[ıi]\s*kutu|ac[ıi]lmam[ıi]ş|\bmühürlü\b|\bbrand\s*new\b", re.I)
SOLD = re.compile(r"\bsatıldı\b|\bsatildi\b|\bsold\b", re.I)
BUNDLE = re.compile(r"\+\s*\w*\s*oyun|bundle|\bset\b|hediye|\+\s*(sd|kart|çanta|kılıf)|full\s*set", re.I)


class _Num:
    def __init__(self, n):
        self.n = n

    def group(self, _):
        return str(self.n)


def _largest_gb(t):
    """Biggest 'NNN GB' that isn't RAM: storage in '12GB+256GB' is 256, not 12."""
    nums = [int(m) for m in re.findall(r"(\d{2,4})\s*gb(?!\s*(?:ram|lpddr|/))", t)]
    nums = [n for n in nums if n >= 32]
    return _Num(max(nums)) if nums else None


def variant(title):
    """(ram_gb, storage_gb) from a title; storage in GB (1TB -> 1024). Unknown -> None."""
    t = (title or "").lower()
    ram = (re.search(r"(\d{1,2})\s*gb\s*(?:ram|lpddr|/\s*\d)", t) or re.search(r"ram\s*:?\s*(\d{1,2})\s*gb", t)
           or re.search(r"(?<![\d.])(\d{1,2})\s*/\s*(?:\d{3,4}\b|\d\s*tb)", t)
           or re.search(r"(?<![\d.])(\d{1,2})\s*gb\s*\+", t))
    rom = (re.search(r"(?<![\d.])\d{1,2}\s*(?:gb)?\s*/\s*(\d{3,4})\b", t)
           or re.search(r"(?<![\d.])\d{1,2}\s*(?:gb)?\s*/\s*(\d)\s*tb", t)
           or _largest_gb(t) or re.search(r"(\d)\s*tb", t))
    ram_gb = int(ram.group(1)) if ram else None
    storage_gb = None
    if rom:
        n = int(rom.group(1))
        storage_gb = n * 1024 if n < 10 else n
    if storage_gb is not None and ram_gb is not None and storage_gb <= ram_gb:
        storage_gb = None
    return ram_gb, storage_gb


def variant_label(ram_gb, storage_gb):
    if ram_gb is None and storage_gb is None:
        return None
    rom = None if storage_gb is None else (f"{storage_gb // 1024}TB" if storage_gb >= 1024 else f"{storage_gb}GB")
    return f"{str(ram_gb) + 'GB' if ram_gb else '?'}/{rom or '?'}"


def condition(title, hint=None):
    """'used' | 'new' | None.
    The title wins over the source default: a forum post saying "SIFIR KAPALI KUTU" is new,
    "sıfır ayarında" / "sıfır gibi" (like new) is used."""
    if NEW_STRONG.search(title or ""):
        return "new"
    if USED.search(title or ""):
        return "used"
    if hint:
        h = hint.lower()
        if "used" in h or "ikinci" in h:
            return "used"
        if "new" in h or "sıfır" in h:
            return "new"
    if USED.search(title or ""):
        return "used"
    if NEW.search(title or ""):
        return "new"
    return None


def parse_title(title, condition_hint=None):
    ram, rom = variant(title)
    return {
        "ram_gb": ram,
        "storage_gb": rom,
        "variant": variant_label(ram, rom),
        "condition": condition(title, condition_hint),
        "sold": bool(SOLD.search(title or "")) or ("sold" in (condition_hint or "").lower()),
        "bundle": bool(BUNDLE.search(title or "")),
        "is_accessory": bool(ACCESSORY.search(title or "")) or bool(GAME.search(title or ""))
                        or bool(NOT_HANDHELD.search(title or "")),   # furniture etc. matched by a stray word
        "wanted": bool(WANTED.search(title or "")),       # "ALINIK ..." = someone looking to buy
    }
