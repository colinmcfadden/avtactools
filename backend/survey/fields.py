"""What the field survey records for one LZ, as the unit's book has it (docs/LZ_SURVEYS.md §1).

Every value is text as the survey wrote it ("1850", "-10° roll left side low", "OCT 2023"): the book is the source, and a
number is read out of the text only where it is compared with what EZ/PZ measured (survey/compare.py). The web lays these
out in the book's sections (frontend/src/feature/surveys/surveyFields.js); a key either side does not know is refused, so the
two lists cannot drift apart unseen (contracts/fixtures/surveys/donovan.json is written with them).
"""

FIELD_KEYS = (
    # Where it is
    "grid",
    "latLong",
    "ppr",
    # How to fly it: judgements, the admin's (§11, R3)
    "approachDeg",
    "exitDeg",
    "maxAcDay",
    "maxAcNight",
    # The book's "Maximum Weight (if applicable)"
    "maxWeight",
    # The ground
    "elevationFt",
    "size",
    "surface",
    "slope",
    # What to look out for
    "hazards",
    "warning",
    "remarks",
    # The survey profile: an obstacle's height and distance on the approach
    "profileObstacleFt",
    "profileDistanceM",
    # Who surveyed it when
    "surveyedOn",
    "surveyedBy",
    "surveyUnit",
)

MAX_VALUE = 2000


def clean_field(value):
    """``value`` as a field map to store, or raise ValueError saying what is wrong. Unknown keys and non-text values are refused;
    a blank value is kept as an empty string (it was asked and not known), never dropped."""
    if not isinstance(value, dict):
        raise ValueError("field must be an object of the survey's values")
    out = {}
    for key, text in value.items():
        if key not in FIELD_KEYS:
            raise ValueError(f"field has no value called {key!r}")
        if text is None:
            text = ""
        if not isinstance(text, str):
            raise ValueError(f"{key} must be text, as the survey wrote it")
        if len(text) > MAX_VALUE:
            raise ValueError(f"{key} can be at most {MAX_VALUE} characters")
        out[key] = text
    return out
