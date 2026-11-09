"""How names and what is typed are folded before they are indexed or matched.

Lower case, accents taken off (``Peña`` is ``pena``), and every run of anything but a letter or a digit one
space (``St. Marys`` is ``st marys``). The index holds folded text and FTS4's ``simple`` tokenizer splits it
on spaces, so the server and Android match the same way whatever their SQLite's own Unicode support.
"""

import re
import unicodedata

_NOT_WORD = re.compile(r"[^a-z0-9]+")


def fold(text):
    """``text`` as the index holds it: lower-case ASCII words, one space apart."""
    if not text:
        return ""
    decomposed = unicodedata.normalize("NFKD", str(text))
    ascii_only = "".join(ch for ch in decomposed if not unicodedata.combining(ch))
    return _NOT_WORD.sub(" ", ascii_only.lower()).strip()


def compact(text):
    """``text`` folded with no spaces: how an identifier is compared (``k atl`` is ``katl``)."""
    return fold(text).replace(" ", "")
