"""Searchable places for the map's search and the target field (docs/PLACE_SEARCH.md).

The FAA's airports, heliports, navaids and fixes (NASR) are read into one SQLite file with an FTS4 index
(`index.py`), searched by name or identifier (`search.py`), and kept to the current 28-day cycle by a
background refresh (`refresh.py`). GNIS's place names are to join the same index later.

Stdlib only (sqlite3, csv, zipfile), so the index can be built and searched where nothing else is installed,
and the same file is to be read by Android's own SQLite: FTS4 with the simple tokenizer over text folded
here to lower-case ASCII, which every SQLite the app runs on has.
"""
