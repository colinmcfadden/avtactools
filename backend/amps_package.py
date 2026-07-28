"""Validation for AMPS packages uploaded to an aircraft profile.

Both a mission (``.msnx``) and a vehicle installation (``.vidx``) are OPC zips.
This module checks that an upload really is one, and pulls out the airframe
identity so the admin doesn't have to type the ``vehicledescription`` string by
hand. Nothing here mutates the package — export does that in the browser.
"""

import io
import re
import zipfile

# mission/vehicles.xml carries e.g.
#   <vehicledescription>Air:Rotary Wing:H60:9856:Default:1.0014:UH-60L</vehicledescription>
_VEHICLE_DESCRIPTION = re.compile(
    rb'<vehicledescription>(.*?)</vehicledescription>', re.IGNORECASE | re.DOTALL
)

# OPC part names percent-encode spaces, so the folder is "Vehicle%20Installations".
_VIDX_PART = re.compile(r'^Vehicle(?:%20| )Installations/([^/]+)\.vidx$', re.IGNORECASE)

MAX_MEMBERS = 5000


def _read_member(archive, *candidates):
    names = {name.lower(): name for name in archive.namelist()}
    for candidate in candidates:
        actual = names.get(candidate.lower())
        if actual:
            return archive.read(actual)
    return None


def inspect_amps_package(data, extension):
    """Validate ``data`` and describe the airframe it carries.

    Returns a dict with ``vehicle_description`` and ``vidx_name`` when they can
    be determined, or ``error`` with a message fit for a flash banner.
    """
    if not data or data[:2] != b'PK':
        return {"error": "That file isn't a valid AMPS package (expected a zip archive)."}

    try:
        archive = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        return {"error": "That file is corrupt or not a zip archive."}

    try:
        names = archive.namelist()
    except Exception:  # noqa: BLE001 — a malformed central directory
        return {"error": "That archive's contents couldn't be read."}

    if len(names) > MAX_MEMBERS:
        return {"error": "That archive has an implausible number of entries."}

    if extension == 'msnx':
        vehicles = _read_member(archive, 'mission/vehicles.xml')
        if vehicles is None:
            return {
                "error": "That .msnx has no mission/vehicles.xml — it doesn't look "
                         "like a mission saved out of AMPS."
            }
        match = _VEHICLE_DESCRIPTION.search(vehicles)
        description = match.group(1).decode('utf-8', 'replace').strip() if match else None

        vidx_name = None
        for name in names:
            found = _VIDX_PART.match(name)
            if found:
                vidx_name = found.group(1)
                break
        if not vidx_name:
            return {
                "error": "That .msnx has no vehicle installation (.vidx) inside, so "
                         "export can't build the airframe from it."
            }
        return {"vehicle_description": description, "vidx_name": vidx_name}

    # A .vidx wraps a single airframe folder plus a matching root xml, e.g.
    # "UH60L/" and "UH60L.xml".
    roots = {name.split('/', 1)[0] for name in names if name.strip()}
    xml_roots = [
        name[:-4] for name in names
        if name.lower().endswith('.xml') and '/' not in name
    ]
    if not xml_roots:
        return {
            "error": "That .vidx has no root manifest xml — it doesn't look like an "
                     "AMPS vehicle installation."
        }
    airframe = xml_roots[0]
    if airframe not in roots:
        # Not fatal: the transplant keys off the manifest name either way.
        pass
    return {"vehicle_description": None, "vidx_name": airframe}
