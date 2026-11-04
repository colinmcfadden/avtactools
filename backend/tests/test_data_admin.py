"""The admin's Data tab (docs/DATA_ADMIN_AND_PREBUILT_3D_PLAN.md §10): the disk rules every write keeps, the catalog and
the terrain cache after a new DEM, USGS's products narrowed to the newest of each cell, a DEM's checks, a download job end
to end (USGS and Terrarium stood in for), and the routes, which only an admin's session reaches."""

import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

BACKEND_DIR = Path(__file__).resolve().parents[1]
if str(BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(BACKEND_DIR))

import numpy as np  # noqa: E402
import rasterio  # noqa: E402
from flask import Flask  # noqa: E402
from rasterio.transform import from_origin  # noqa: E402

import terrain_tiles  # noqa: E402
from data_admin import jobs, layers, usgs, validate, volumes  # noqa: E402
from models import DataJob, User, db  # noqa: E402
from terrain_provider import LOCAL_CATALOG, LocalRasterCatalog  # noqa: E402

GB = volumes.GB


def write_dem(path, *, west=-84.6, north=34.8, cells=64, size=0.0001, height=400.0, tiled=True, crs="EPSG:4269",
              nodata=-999999.0, bands=1):
    """A small DEM over north Georgia: ``height`` everywhere, a little slope so it is not flat."""
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    data = (height + np.linspace(0, 5, cells * cells, dtype="float32").reshape(cells, cells))
    profile = {"driver": "GTiff", "width": cells, "height": cells, "count": bands, "dtype": "float32", "crs": crs,
               "transform": from_origin(west, north, size, size), "nodata": nodata}
    if tiled:
        profile.update(tiled=True, blockxsize=16, blockysize=16)
    with rasterio.open(path, "w", **profile) as dataset:
        for band in range(1, bands + 1):
            dataset.write(data, band)
    return Path(path)


def flat_terrarium(height):
    return lambda lons, lats: [height] * len(lons)


class Env:
    """Environment variables for one test, put back after."""

    def __init__(self, case, **values):
        patcher = patch.dict(os.environ, {k: str(v) for k, v in values.items()})
        patcher.start()
        case.addCleanup(patcher.stop)


# -- the disk ---------------------------------------------------------------------------------------

class DiskRuleTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def test_a_folder_on_the_containers_own_disk_is_refused_unless_a_local_run_allows_it(self):
        Env(self, DATA_ADMIN="write", DATA_ADMIN_RESERVE_GB="0")
        with patch.object(volumes, "on_container_disk", return_value=True):
            with self.assertRaisesRegex(volumes.NoRoom, "not a mounted volume"):
                volumes.check_room(self.dir, 10)
            with patch.dict(os.environ, {"DATA_ADMIN_SAME_DISK": "allow"}):
                volumes.check_room(self.dir, 10)
                # Production never takes the override: there the folder really is the VM's root disk.
                with patch.dict(os.environ, {"APP_ENV": "production"}):
                    with self.assertRaisesRegex(volumes.NoRoom, "not a mounted volume"):
                        volumes.check_room(self.dir, 10)

    def test_the_reserve_is_kept_free(self):
        Env(self, DATA_ADMIN="write", DATA_ADMIN_SAME_DISK="allow")
        with patch.object(volumes.shutil, "disk_usage", return_value=volumes.shutil._ntuple_diskusage(1000 * GB, 900 * GB, 100 * GB)):
            volumes.check_room(self.dir, 0)
            volumes.check_room(self.dir, 0 * GB)
            with self.assertRaisesRegex(volumes.NoRoom, "Not enough room"):
                volumes.check_room(self.dir, 1)          # 10 % of 1 TB is the reserve, and 100 GB is all that is free
            self.assertEqual(volumes.reserve_bytes(1000 * GB), 100 * GB)
            self.assertEqual(volumes.reserve_bytes(50 * GB), 20 * GB)

    def test_nothing_is_written_unless_writes_are_on(self):
        Env(self, DATA_ADMIN="read", DATA_ADMIN_SAME_DISK="allow", DATA_ADMIN_RESERVE_GB="0")
        with self.assertRaisesRegex(volumes.NoRoom, "Writes are off"):
            volumes.check_room(self.dir, 1)

    def test_a_paths_mount_is_the_longest_mount_point_holding_it(self):
        info = self.dir / "mountinfo"
        real = os.path.realpath(self.dir)
        info.write_text(
            "22 1 0:21 / / rw - overlay overlay rw\n"
            f"30 22 8:17 /data/topo {real} rw,relatime - ext4 /dev/sdb1 rw\n")
        mount = volumes.mount_of(self.dir / "usgs", mountinfo=info)
        self.assertEqual(mount, {"mount_point": real, "device": "8:17", "fs": "ext4", "source": "/dev/sdb1"})
        self.assertIsNone(volumes.mount_of(self.dir, mountinfo=self.dir / "none"))


# -- the catalog and the terrain cache after a new DEM ---------------------------------------------------

class CatalogTests(unittest.TestCase):
    def test_dot_folders_are_not_scanned_and_a_refresh_is_at_once(self):
        root = Path(tempfile.mkdtemp())
        Env(self, TERRAIN_DATA_DIR=root, TERRAIN_CATALOG_REFRESH_SECONDS="3600")
        catalog = LocalRasterCatalog()
        write_dem(root / "usgs" / "13" / "a.tif")
        write_dem(root / ".incoming" / "b.tif")      # staged: half written, perhaps
        self.assertEqual([Path(e.path).name for e in catalog.entries()], ["a.tif"])
        write_dem(root / "usgs" / "13" / "c.tif", west=-84.5)
        self.assertEqual(len(catalog.entries()), 1)   # the interval has not run out
        self.assertEqual(sorted(Path(e.path).name for e in catalog.refresh()), ["a.tif", "c.tif"])

    def test_old_terrain_generations_are_let_go_and_only_folders_named_as_generations(self):
        cache = Path(tempfile.mkdtemp())
        dems = Path(tempfile.mkdtemp())
        write_dem(dems / "a.tif")
        Env(self, TERRAIN_TILE_CACHE_DIR=cache, TERRAIN_DATA_DIR=dems, TERRAIN_WARM_LEVEL="-1")
        LOCAL_CATALOG.refresh()
        self.addCleanup(LOCAL_CATALOG.refresh)
        current = terrain_tiles.current_generation()
        owners = {current: terrain_tiles.deployment(), "0123456789abcdef": terrain_tiles.deployment(),
                  "1111111111111111": "staging0000000000", "2222222222222222": None, "notageneration": None}
        for name, owner in owners.items():
            (cache / name / "9").mkdir(parents=True)
            (cache / name / "9" / "1.i16").write_bytes(b"x" * 10)
            if owner:
                (cache / name / terrain_tiles.OWNER_FILE).write_text(owner)
        listed = {g["name"]: g for g in terrain_tiles.generations()}
        self.assertEqual(sorted(listed), sorted([current, "0123456789abcdef", "1111111111111111", "2222222222222222"]))
        self.assertTrue(listed[current]["current"])
        self.assertEqual(listed["1111111111111111"]["owner"], "other")
        self.assertEqual(listed["2222222222222222"]["owner"], "unmarked")
        # Only this deployment's old one goes: staging shares the cache, and an unmarked one may be its live tiles.
        self.assertEqual(terrain_tiles.sweep_generations(), 1)
        self.assertEqual(sorted(p.name for p in cache.iterdir()),
                         sorted([current, "1111111111111111", "2222222222222222", "notageneration"]))

    def test_the_deployment_is_named_without_the_databases_password(self):
        with patch.dict(os.environ, {"DATABASE_URL": "postgresql://u:secret@db.prod.example:5432/postgres"}):
            prod = terrain_tiles.deployment()
        with patch.dict(os.environ, {"DATABASE_URL": "postgresql://u:other@db.prod.example:5432/postgres"}):
            self.assertEqual(terrain_tiles.deployment(), prod)
        with patch.dict(os.environ, {"DATABASE_URL": "postgresql://u:secret@db.staging.example:5432/postgres"}):
            self.assertNotEqual(terrain_tiles.deployment(), prod)


# -- USGS's products -------------------------------------------------------------------------------

def item(name, *, size=480_000_000, published="2022-05-12", bounds=(-85.0, 34.0, -84.0, 35.0),
         host="https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/historical/n35w085/"):
    return {"title": name, "downloadURL": host + name, "sizeInBytes": size, "publicationDate": published,
            "lastUpdated": published + "T00:00:00", "boundingBox": dict(zip(("minX", "minY", "maxX", "maxY"), bounds))}


class Entry:
    def __init__(self, path, bounds, resolution):
        self.path, self.bounds_latlon, self.resolution_m, self.kind, self.vertical_datum = path, bounds, resolution, "cog", ""


class UsgsTests(unittest.TestCase):
    def test_the_newest_of_each_cell_and_nothing_that_is_not_a_dem_tile(self):
        items = [item("USGS_13_n35w085_20110401.tif", published="2011-04-01"),
                 item("USGS_13_n35w085_20260429.tif", published="2026-04-29"),
                 item("USGS_13_n35w085_20230215.tif", published="2023-02-15"),
                 item("USGS_13_n35w085_20170824.tif", size=464_883, published="2027-01-01"),     # 465 KB: not a tile
                 item("USGS_13_n35w084_20220512.tif", bounds=(-84.0, 34.0, -83.0, 35.0)),
                 item("USGS_13_n36w085.tif", host="https://evil.example.com/")]                  # not USGS's host
        chosen, skipped = usgs.candidates(items, "13")
        self.assertEqual([c.name for c in chosen], ["USGS_13_n35w085_20260429.tif", "USGS_13_n35w084_20220512.tif"])
        self.assertEqual(skipped, 4)
        self.assertEqual(chosen[0].folder, ["usgs", "13"])
        self.assertEqual(chosen[0].published, "2026-04-29")

    def test_one_metre_tiles_go_in_their_projects_folder(self):
        host = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/1m/Projects/GA/TIFF/"
        chosen, _ = usgs.candidates([
            item("USGS_1M_16_x72y385_GA_Statewide_2018_B18_DRRA.tif", size=346_000_000, host=host, published="2022-05-31"),
            item("USGS_1M_16_x72y385_GA_Older_2012.tif", size=340_000_000, host=host, published="2014-01-01"),
        ], "1m")
        self.assertEqual([(c.cell, c.folder) for c in chosen],
                         [("16 x72y385", ["usgs", "1m", "GA_Statewide_2018_B18_DRRA"])])

    def test_what_the_server_holds_is_marked_by_name_by_cell_or_by_footprint(self):
        chosen, _ = usgs.candidates([item("USGS_13_n35w085_20260429.tif"),
                                     item("USGS_13_n35w084_20220512.tif", bounds=(-84.0, 34.0, -83.0, 35.0)),
                                     item("USGS_13_n34w085_20220512.tif", bounds=(-85.0, 33.0, -84.0, 34.0)),
                                     item("USGS_13_n34w084_20220512.tif", bounds=(-84.0, 33.0, -83.0, 34.0))], "13")
        entries = [Entry("/t/USGS_13_n35w085.tif", (34.0, -85.0, 35.0, -84.0), 10.3),          # the same cell, current name
                   Entry("/t/mine.tif", (33.0, -84.01, 34.01, -82.99), 10.3),                   # covers n34w084
                   Entry("/t/fine.tif", (34.0, -84.0, 35.0, -83.0), 1.0)]                       # 1 m: not a duplicate
        usgs.mark_held(chosen, entries, "13", lambda p: Path(p).name)
        held = {c.name: c.held for c in chosen}
        self.assertEqual({c.name: c.same_tile for c in chosen if c.held},
                         {"USGS_13_n35w085_20260429.tif": True, "USGS_13_n34w084_20220512.tif": True})
        self.assertEqual(held, {"USGS_13_n35w085_20260429.tif": "USGS_13_n35w085.tif", "USGS_13_n35w084_20220512.tif": None,
                                "USGS_13_n34w085_20220512.tif": None, "USGS_13_n34w084_20220512.tif": "mine.tif"})
        self.assertNotIn("held_path", chosen[0].for_page())

    def test_a_project_name_that_could_climb_out_or_hide_is_left_out(self):
        host = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/1m/Projects/X/TIFF/"
        chosen, skipped = usgs.candidates([item("USGS_1M_16_x1y2_...tif", host=host), item("USGS_1M_16_x1y3_.hidden.tif", host=host),
                                           item("USGS_one_meter_x45y380_GA_Old_2010.tif", host=host)], "1m")
        self.assertEqual([(c.name, c.folder[-1]) for c in chosen], [("USGS_one_meter_x45y380_GA_Old_2010.tif", "GA_Old_2010")])
        self.assertEqual(skipped, 2)
        self.assertFalse(usgs.allowed_url("https://prd-tnm.s3.amazonaws.com:abc/x.tif"))

    def test_a_cell_inside_a_larger_dem_is_covered_not_the_same_tile(self):
        chosen, _ = usgs.candidates([item("USGS_13_n34w085_20220512.tif", bounds=(-85.0, 33.0, -84.0, 34.0))], "13")
        usgs.mark_held(chosen, [Entry("/t/ga_statewide_10m.tif", (30.0, -86.0, 36.0, -80.0), 10.3)], "13",
                       lambda p: Path(p).name)
        self.assertEqual((chosen[0].held, chosen[0].same_tile), ("ga_statewide_10m.tif", False))

    def test_only_the_lower_48_can_be_searched(self):
        for bbox in (None, [1, 2, 3], [-84, 34, -85, 35], [-150, 60, -149, 61]):
            with self.assertRaises(usgs.SearchError):
                usgs.check_area(bbox)

    def test_names_usgs_gives_its_3dep_dems(self):
        self.assertTrue(usgs.is_3dep_name("/data/topo/USGS_13_n35w085.tif"))
        self.assertTrue(usgs.is_3dep_name("USGS_1M_16_x72y385_GA_Statewide_2018_B18_DRRA.tif"))
        self.assertFalse(usgs.is_3dep_name("/data/topo/state_lidar_dem.tif"))


# -- a DEM's checks ------------------------------------------------------------------------------------

class ValidateTests(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())

    def check(self, path, height=402.5):
        return validate.check_dem(path, heights=flat_terrarium(height))

    def test_a_usgs_like_dem_passes_and_states_no_datum(self):
        checked = self.check(write_dem(self.dir / "a.tif"))
        self.assertTrue(checked.ok, checked.reasons)
        self.assertIsNone(checked.stated_datum)
        self.assertLess(checked.resolution_m, 15)

    def test_heights_off_from_terrarium_are_refused(self):
        dem = write_dem(self.dir / "a.tif")
        self.assertIn("from Terrarium", " ".join(self.check(dem, height=432.0).reasons))     # ellipsoidal, about −30 m
        feet = write_dem(self.dir / "ft.tif", height=400 * 3.28084)
        self.assertFalse(self.check(feet).ok)
        self.assertTrue(validate.check_dem(dem, heights=lambda lons, lats: [None] * len(lons)).ok)   # no answer: a note

    def test_what_is_not_a_tiled_single_band_dem_in_the_lower_48_is_refused(self):
        cases = {
            "not tiled": write_dem(self.dir / "s.tif", tiled=False),
            "bands": write_dem(self.dir / "b.tif", bands=3),
            "lower 48": write_dem(self.dir / "o.tif", west=-150.0, north=61.0),
            "15 m": write_dem(self.dir / "c.tif", size=0.001),
            "nodata": write_dem(self.dir / "n.tif", nodata=None),
        }
        for reason, path in cases.items():
            checked = self.check(path)
            self.assertFalse(checked.ok, reason)
            self.assertIn(reason, " ".join(checked.reasons))
        junk = self.dir / "junk.tif"
        junk.write_bytes(b"not a tiff at all")
        self.assertFalse(self.check(junk).ok)

    def test_a_stated_datum_other_than_navd88_is_refused(self):
        other = write_dem(self.dir / "e.tif", crs="EPSG:4269+5714")      # mean sea level height
        self.assertIn("other than NAVD88", " ".join(self.check(other).reasons))
        feet = write_dem(self.dir / "f.tif", crs="EPSG:4269+6360")      # NAVD88 in US survey feet: 3.28 times too high
        self.assertIn("other than NAVD88 metres", " ".join(self.check(feet).reasons))
        navd = write_dem(self.dir / "v.tif", crs="EPSG:4269+5703")
        checked = self.check(navd)
        self.assertTrue(checked.ok, checked.reasons)
        self.assertEqual(checked.stated_datum, "NAVD88")

    def test_tagging_writes_the_side_file_the_catalog_reads_and_keeps_what_else_it_held(self):
        dem = write_dem(self.dir / "a.tif")
        side = Path(f"{dem}.aux.xml")
        side.write_text('<PAMDataset><Metadata><MDI key="STATISTICS_MEAN">400</MDI></Metadata></PAMDataset>')
        validate.tag_navd88(dem)
        with rasterio.open(dem) as dataset:
            tags = dataset.tags()
        self.assertEqual(tags["VERT_DATUM"], "NAVD88")
        self.assertEqual(tags["STATISTICS_MEAN"], "400")
        validate.tag_navd88(dem)
        self.assertEqual(side.read_text().count("VERT_DATUM"), 1)


# -- a download, end to end ------------------------------------------------------------------------------

class FakeResponse:
    def __init__(self, status, body, *, length=None, fail_after=None):
        self.status_code, self._body, self._fail_after = status, body, fail_after
        self.headers = {"Content-Length": str(len(body) if length is None else length)}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def iter_content(self, size):
        size = min(size, 1024)    # small pieces, so a drop can come part way through a small file
        sent = 0
        for start in range(0, len(self._body), size):
            if self._fail_after is not None and sent >= self._fail_after:
                raise ConnectionError("reset")
            chunk = self._body[start:start + size]
            sent += len(chunk)
            yield chunk


class FakeHosts:
    """USGS's file host: serves bytes by URL, honours Range, and can drop the first connection part way."""

    def __init__(self, files, *, drop_first=False):
        self.files, self.drop_first, self.asked = files, drop_first, []

    def get(self, url, headers=None, **_):
        self.asked.append((url, (headers or {}).get("Range")))
        body = self.files[url]
        start = 0
        if headers and headers.get("Range"):
            start = int(headers["Range"].split("=")[1].rstrip("-"))
        if self.drop_first:
            self.drop_first = False
            return FakeResponse(200, body, fail_after=len(body) // 2)
        return FakeResponse(206 if start else 200, body[start:])


class JobCase(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.cache = Path(tempfile.mkdtemp())
        Env(self, TERRAIN_DATA_DIR=self.root, TERRAIN_TILE_CACHE_DIR=self.cache, TERRAIN_WARM_LEVEL="-1",
            DATA_ADMIN="write", DATA_ADMIN_SAME_DISK="allow", DATA_ADMIN_RESERVE_GB="0")
        self.app = Flask(__name__, root_path=str(BACKEND_DIR))
        self.app.config.update(SQLALCHEMY_DATABASE_URI="sqlite://", TESTING=True, SECRET_KEY="test-only-admin-session-key")
        db.init_app(self.app)
        with self.app.app_context():
            db.create_all()
            admin = User(email="admin@example.com", name="Admin", role="admin", google_id="g-admin")
            db.session.add(admin)
            db.session.commit()
            self.admin_id = admin.id
        self.addCleanup(LOCAL_CATALOG.refresh)
        patcher = patch.object(validate, "terrarium_heights", flat_terrarium(402.5))
        patcher.start()
        self.addCleanup(patcher.stop)
        tiff = write_dem(Path(tempfile.mkdtemp()) / "USGS_13_n35w085_20260429.tif")
        self.body = tiff.read_bytes()
        self.url = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/13/TIFF/historical/n35w085/" + tiff.name
        self.candidate = {"name": tiff.name, "url": self.url, "size": len(self.body), "bounds": [-84.6, 34.7936, -84.5936, 34.8],
                          "folder": ["usgs", "13"], "held": None}

    def make_job(self, files, **fields):
        with self.app.app_context():
            job = DataJob(id=jobs.new_id(), product="13", files=files, files_total=len(files),
                          bytes_total=sum(f["size"] for f in files), **fields)
            db.session.add(job)
            db.session.commit()
            return job.id

    def run_job(self, job_id, hosts):
        with self.app.app_context(), patch.object(jobs.time, "sleep"), \
                patch.object(terrain_tiles, "rewarm_after_change") as rewarm:
            jobs.run(job_id, http=hosts)
            job = db.session.get(DataJob, job_id)
            return job.state, job.error, [dict(f) for f in job.files], rewarm.called


class JobTests(JobCase):
    def test_a_file_comes_down_resumes_is_tagged_and_put_in_place_and_the_catalog_sees_it(self):
        hosts = FakeHosts({self.url: self.body}, drop_first=True)
        state, error, files, rewarmed = self.run_job(self.make_job(jobs.plan([self.candidate], replace=False)), hosts)
        self.assertEqual((state, error), ("done", None))
        self.assertEqual(files[0]["state"], "done")
        placed = self.root / "usgs" / "13" / self.candidate["name"]
        self.assertEqual(placed.read_bytes(), self.body)
        self.assertTrue(Path(f"{placed}.aux.xml").is_file())
        resumed_at = int(hosts.asked[1][1].split("=")[1].rstrip("-"))              # the second try resumed
        self.assertTrue(0 < resumed_at < len(self.body))
        self.assertEqual(list((self.root / jobs.INCOMING).iterdir()), [])
        entry = next(e for e in LOCAL_CATALOG.entries() if Path(e.path) == placed)
        self.assertEqual(entry.vertical_datum, "NAVD88")
        self.assertTrue(rewarmed)

    def test_a_file_that_fails_its_checks_is_not_kept(self):
        with patch.object(validate, "terrarium_heights", flat_terrarium(432.5)):
            state, _, files, rewarmed = self.run_job(self.make_job(jobs.plan([self.candidate], replace=False)),
                                                      FakeHosts({self.url: self.body}))
        self.assertEqual(state, "done")
        self.assertEqual(files[0]["state"], "refused")
        self.assertIn("Terrarium", files[0]["reasons"][0])
        self.assertFalse((self.root / "usgs").exists() and any((self.root / "usgs").rglob("*.tif")))
        self.assertEqual(list((self.root / jobs.INCOMING).iterdir()), [])
        self.assertFalse(rewarmed)

    def test_a_file_whose_size_is_not_what_tnm_listed_is_refused(self):
        other = {**self.candidate, "name": "USGS_13_n35w084_20260429.tif", "url": self.url.replace("n35w085_", "n35w084_")}
        hosts = FakeHosts({self.url: self.body + b"extra", other["url"]: self.body})
        state, error, files, _ = self.run_job(self.make_job(jobs.plan([self.candidate, other], replace=False)), hosts)
        # One bad file is that file's failure; the job goes on to the next.
        self.assertEqual((state, error), ("done", None))
        self.assertEqual([f["state"] for f in files], ["failed", "done"])
        self.assertIn("not the size", files[0]["reasons"][0])
        self.assertEqual(list((self.root / jobs.INCOMING).glob("*.part")), [])

    def test_a_redirect_is_not_followed_and_a_busy_host_is_tried_again(self):
        class Busy(FakeHosts):
            def get(self, url, headers=None, **kwargs):
                self.asked.append((url, None))
                if len(self.asked) == 1:
                    return FakeResponse(503, b"")
                if len(self.asked) == 2:
                    return FakeResponse(302, b"")
                return super().get(url, headers, **kwargs)
        hosts = Busy({self.url: self.body})
        _, _, files, _ = self.run_job(self.make_job(jobs.plan([self.candidate], replace=False)), hosts)
        self.assertEqual(files[0]["state"], "failed")
        self.assertIn("302", files[0]["reasons"][0])
        self.assertEqual(len(hosts.asked), 2)

    def test_a_database_failure_still_ends_the_job(self):
        job_id = self.make_job(jobs.plan([self.candidate], replace=False))
        real = jobs._Progress.save
        calls = {"n": 0}

        def flaky(progress, *, force=False):
            calls["n"] += 1
            if calls["n"] == 3:
                raise RuntimeError("the pooler went away")
            return real(progress, force=force)
        with patch.object(jobs._Progress, "save", flaky):
            state, error, _, _ = self.run_job(job_id, FakeHosts({self.url: self.body}))
        self.assertEqual(state, "failed")         # never left as running, to be resumed at every look
        self.assertIn("went wrong", error)

    def test_no_room_stops_the_job_before_anything_is_fetched(self):
        hosts = FakeHosts({self.url: self.body})
        with patch.dict(os.environ, {"DATA_ADMIN_RESERVE_GB": "1e12"}):
            state, error, _, _ = self.run_job(self.make_job(jobs.plan([self.candidate], replace=False)), hosts)
        self.assertEqual(state, "failed")
        self.assertIn("Not enough room", error)
        self.assertEqual(hosts.asked, [])

    def test_a_url_on_another_host_is_never_fetched(self):
        planned = jobs.plan([{**self.candidate, "url": "https://evil.example.com/x.tif"}], replace=False)
        hosts = FakeHosts({})
        _, _, files, _ = self.run_job(self.make_job(planned), hosts)
        self.assertEqual(files[0]["state"], "refused")
        self.assertEqual(hosts.asked, [])

    def test_a_cancelled_job_stops_and_leaves_no_part(self):
        job_id = self.make_job(jobs.plan([self.candidate], replace=False), cancel_requested=True)
        state, _, _, _ = self.run_job(job_id, FakeHosts({self.url: self.body}))
        self.assertEqual(state, "cancelled")
        self.assertEqual(list((self.root / jobs.INCOMING).glob("*.part")), [])

    def test_replacing_moves_the_old_file_aside_and_held_files_are_skipped_otherwise(self):
        old = write_dem(self.root / "USGS_13_n35w085.tif")
        held = {**self.candidate, "held": "USGS_13_n35w085.tif", "held_path": str(old), "same_tile": True}
        self.assertEqual(jobs.plan([held], replace=False), [])
        state, _, files, _ = self.run_job(self.make_job(jobs.plan([held], replace=True), replace=True),
                                          FakeHosts({self.url: self.body}))
        self.assertEqual((state, files[0]["state"]), ("done", "done"))
        self.assertFalse(old.exists())
        self.assertEqual([p.name for p in (self.root / jobs.TRASH).rglob("*.tif")], ["USGS_13_n35w085.tif"])

    def test_a_larger_dem_covering_a_cell_is_never_replaced_for_it(self):
        # A statewide mosaic covers the cell: Replace must not move the whole mosaic aside to fill one cell.
        covered = {**self.candidate, "held": "ga_statewide_10m.tif", "held_path": str(self.root / "ga_statewide_10m.tif"),
                   "same_tile": False}
        self.assertEqual(jobs.plan([covered], replace=True), [])
        twice = [{**self.candidate, "held": "a.tif", "held_path": "/x/a.tif", "same_tile": True}] * 2
        self.assertEqual(len(jobs.plan(twice, replace=True)), 1)       # one held file is replaced once

    def test_the_streamed_cache_keeps_room_to_grow_on_the_same_disk(self):
        cache = self.root / "stream"
        cache.mkdir()
        with patch.dict(os.environ, {"LIDAR_STREAM_CACHE_DIR": str(cache), "LIDAR_STREAM_CACHE_MAX_GB": "1e9"}), \
                patch.object(layers, "stream_cache", return_value={"bytes": 0}):
            with self.assertRaisesRegex(volumes.NoRoom, "LIDAR_STREAM_CACHE_MAX_GB"):
                jobs.check_job(jobs.plan([self.candidate], replace=False), self.root)

    def test_messages_name_folders_never_the_servers_paths(self):
        with patch.dict(os.environ, {"DATA_ADMIN_RESERVE_GB": "1e12"}):
            with self.assertRaises(volumes.NoRoom) as refused:
                jobs.check_job(jobs.plan([self.candidate], replace=False), self.root)
        self.assertNotIn(str(self.root), str(refused.exception))

    def test_a_job_too_big_is_refused_before_it_starts(self):
        with self.assertRaisesRegex(volumes.NoRoom, "Nothing to download"):
            jobs.check_job([], self.root)
        big = [{**self.candidate, "size": 6 * GB}] * 4
        with self.assertRaisesRegex(volumes.NoRoom, "more than one job takes"):
            jobs.check_job(big, self.root)


# -- the layers ----------------------------------------------------------------------------------------

class StreamLayerTests(unittest.TestCase):
    def test_cached_pieces_are_counted_in_cells_and_never_listed(self):
        from lidar import ept, names

        root = Path(tempfile.mkdtemp())
        Env(self, LIDAR_STREAM_CACHE_DIR=root)
        secret = "s" * 40
        key = "0123456789abcdef"
        survey_name = "GA_Statewide_B3_2018"
        # A survey cube round Ellijay in Web Mercator, 25.6 km wide: depth 4 pieces are 1.6 km.
        x0, y0 = -9_420_000.0, 4_120_000.0
        folder = root / key
        (folder / "laz").mkdir(parents=True)
        (folder / "ept.json").write_text(json.dumps({"bounds": [x0, y0, 0, x0 + 25_600, y0 + 25_600, 25_600],
                                                     "span": 128, "dataType": "laszip", "srs": {"horizontal": "3857"}}))
        for node in (ept.Node(4, 3, 5, 0), ept.Node(4, 3, 5, 1), ept.Node(0, 0, 0, 0)):
            (folder / f"{names.piece_name(survey_name, node, secret=secret)}.pnts").write_bytes(b"p" * 100)
        (folder / "laz" / "4-3-5-0.laz").write_bytes(b"l" * 50)
        (root / "fedcba9876543210").mkdir()
        (root / "fedcba9876543210" / "old.pnts").write_bytes(b"o" * 30)

        class Survey:
            name, year = survey_name, 2018

        class FakeStreamer:
            def _by_key(self):
                return {key: (Survey(), None, None)}

            def _secret_now(self):
                return secret

        layers.forget()
        with patch("lidar.stream.streamer", return_value=FakeStreamer()):
            found = layers.stream_cache(fresh=True)
        self.assertEqual(found["surveys"][0]["pieces"], 3)
        self.assertEqual(found["surveys"][0]["laz"], 1)
        self.assertEqual(found["older_bytes"], 30)
        self.assertEqual(len(found["cells"]), 1)                 # the root piece is the survey's, not a cell's
        cell = found["cells"][0]["properties"]
        self.assertEqual((cell["pieces"], cell["laz"]), (2, 1))
        self.assertNotIn(key, json.dumps(found))                 # nothing names a piece or a key


# -- the routes ----------------------------------------------------------------------------------------

class RouteTests(JobCase):
    def setUp(self):
        super().setUp()
        from routes.admin_routes import admin_bp

        self.app.register_blueprint(admin_bp)
        self.dashboard = self.app.test_client()
        with self.dashboard.session_transaction() as session:
            session["admin_uid"] = self.admin_id
            session["csrf"] = "token"
        jobs.RUNNER.inline = True
        self.addCleanup(setattr, jobs.RUNNER, "inline", False)
        write_dem(self.root / "USGS_13_n35w086.tif", west=-85.6)
        LOCAL_CATALOG.refresh()

    def post(self, path, body=None):
        return self.dashboard.post(path, json=body or {}, headers={"X-CSRF": "token"})

    def test_off_is_not_there_and_nobody_but_an_admin_reaches_it(self):
        with patch.dict(os.environ, {"DATA_ADMIN": "off"}):
            self.assertEqual(self.dashboard.get("/admin/data").status_code, 404)
            self.assertNotIn("/admin/data", self.dashboard.get("/admin/packs").get_data(as_text=True))
        stranger = self.app.test_client()
        self.assertEqual(stranger.get("/admin/data/layers/dems.json").status_code, 302)    # to the admin login
        self.assertEqual(self.dashboard.post("/admin/data/usgs/search", json={}).status_code, 400)   # no CSRF token

    def test_the_page_and_its_layers(self):
        page = self.dashboard.get("/admin/data")
        self.assertEqual(page.status_code, 200)
        self.assertIn("maplibre-gl.mjs", page.get_data(as_text=True))
        dems = self.dashboard.get("/admin/data/layers/dems.json")
        self.assertEqual(dems.headers["Cache-Control"], "private, no-store")
        feature = dems.get_json()["features"][0]["properties"]
        self.assertEqual(feature["name"], "USGS_13_n35w086.tif")    # inside its root, not the server's whole path
        self.assertFalse(feature["navd88"])
        volumes_ = self.dashboard.get("/admin/data/volumes.json").get_json()
        self.assertNotIn(str(self.root), json.dumps(volumes_))
        self.assertEqual(self.dashboard.get("/admin/data/layers/nonsense.json").status_code, 404)

    def test_searching_then_downloading(self):
        items = [item(self.candidate["name"], size=len(self.body), bounds=(-84.6, 34.7936, -84.5936, 34.8))]
        with patch.object(usgs, "products", return_value=items), patch.object(usgs, "MIN_FILE_BYTES", 0):
            found = self.post("/admin/data/usgs/search", {"bbox": [-85, 34, -84, 35], "product": "13"}).get_json()
            self.assertEqual((found["new_files"], found["new_bytes"]), (1, len(self.body)))
            original = jobs.run
            with patch.object(jobs, "run", wraps=lambda job_id: original(job_id, http=FakeHosts({self.url: self.body}))), \
                    patch.object(terrain_tiles, "rewarm_after_change"):
                created = self.post("/admin/data/jobs", {"bbox": [-85, 34, -84, 35], "product": "13"})
                self.assertEqual(created.status_code, 202, created.get_data(as_text=True))
                jobs.RUNNER.run_pending(self.app)
        listed = self.dashboard.get("/admin/data/jobs.json").get_json()["jobs"]
        self.assertEqual(listed[0]["state"], "done")
        self.assertNotIn("held_path", json.dumps(listed))
        self.assertTrue((self.root / "usgs" / "13" / self.candidate["name"]).is_file())

    def test_a_cancel_is_asked_of_the_job_itself(self):
        job_id = self.make_job(jobs.plan([self.candidate], replace=False))
        self.post(f"/admin/data/jobs/{job_id}/cancel")
        with self.app.app_context():
            job = db.session.get(DataJob, job_id)
            self.assertEqual((job.state, job.cancel_requested), ("cancelled", True))

    def test_read_only_searches_but_never_downloads_or_tags(self):
        job_id = self.make_job(jobs.plan([self.candidate], replace=False))
        with patch.dict(os.environ, {"DATA_ADMIN": "read"}), patch.object(jobs.RUNNER, "enqueue") as enqueue:
            self.dashboard.get("/admin/data/jobs.json")
            enqueue.assert_not_called()                  # a job left from before is not run where writes are off
        with self.app.app_context():
            self.assertEqual(db.session.get(DataJob, job_id).state, "queued")
        with patch.dict(os.environ, {"DATA_ADMIN": "read"}):
            self.assertEqual(self.post("/admin/data/jobs", {"bbox": [-85, 34, -84, 35], "product": "13"}).status_code, 403)
            self.assertEqual(self.post("/admin/data/dems/tag-usgs").status_code, 403)

    def test_tagging_the_usgs_dems_on_the_server(self):
        write_dem(self.root / "my_own_survey.tif", west=-83.6)
        write_dem(self.root / "USGS_13_n35w083.tif", west=-82.6)
        Path(self.root / "USGS_13_n35w083.tif.aux.xml").write_text("<PAMDataset><not closed")
        LOCAL_CATALOG.refresh()
        answer = self.post("/admin/data/dems/tag-usgs").get_json()
        self.assertEqual(answer["tagged"], 1)
        self.assertIn("cannot be read", answer["refused"][0])          # a broken side file is left, and said
        self.assertTrue((self.root / "USGS_13_n35w086.tif.aux.xml").is_file())
        self.assertFalse((self.root / "my_own_survey.tif.aux.xml").exists())

    def test_one_download_at_a_time(self):
        self.make_job(jobs.plan([self.candidate], replace=False))
        items = [item(self.candidate["name"], size=len(self.body), bounds=(-84.6, 34.7936, -84.5936, 34.8))]
        with patch.object(usgs, "products", return_value=items), patch.object(usgs, "MIN_FILE_BYTES", 0):
            answer = self.post("/admin/data/jobs", {"bbox": [-85, 34, -84, 35], "product": "13"})
        self.assertEqual(answer.status_code, 409)


if __name__ == "__main__":
    unittest.main()
