import mercantile
import rasterio
from rasterio.transform import from_bounds
from rasterio.merge import merge
from rasterio.io import MemoryFile
import requests
import numpy as np
from PIL import Image
import logging
import os

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# REPLACE WITH YOUR TOKEN
MAPBOX_ACCESS_TOKEN = "pk.eyJ1IjoiY21jZmFkZGVuOSIsImEiOiJjbWxvNGhhYWIwNmpmM2VvbTJ5YjJ3MmZxIn0.zxZ__KSBdP8KuLN0rzULlw"
TILE_URL = f"https://api.mapbox.com/styles/v1/mapbox/satellite-v9/tiles/512/{{z}}/{{x}}/{{y}}@2x?access_token={MAPBOX_ACCESS_TOKEN}"

def fetch_tile_as_bytes(tile):
    """Downloads a tile and returns raw bytes."""
    url = TILE_URL.format(x=tile.x, y=tile.y, z=tile.z)
    try:
        r = requests.get(url, headers={"User-Agent": "MissionPlanner/1.0"}, timeout=10)
        return r.content if r.status_code == 200 else None
    except:
        return None

def get_stitched_image(bbox, zoom=17):
    """
    Downloads and stitches tiles for a bbox.
    Returns: (numpy_image, affine_transform, crs)
    """
    west, south, east, north = bbox
    tiles = list(mercantile.tiles(west, south, east, north, zoom))
    
    if not tiles:
        raise ValueError("No tiles found for this area.")

    mem_files = []
    datasets_to_merge = []

    try:
        for tile in tiles:
            tile_bytes = fetch_tile_as_bytes(tile)
            if tile_bytes:
                mem_file = MemoryFile(tile_bytes)
                mem_files.append(mem_file)
                dataset = mem_file.open()
                
                # Create Transform for 512x512 tile
                tile_bounds = mercantile.xy_bounds(tile)
                transform = from_bounds(
                    tile_bounds.left, tile_bounds.bottom,
                    tile_bounds.right, tile_bounds.top,
                    dataset.width, dataset.height
                )
                
                # Create temp Geo-referenced dataset
                temp_dst = MemoryFile()
                mem_files.append(temp_dst)
                with temp_dst.open(
                    driver='GTiff', height=dataset.height, width=dataset.width,
                    count=3, dtype=dataset.read(1).dtype,
                    crs='EPSG:3857', transform=transform
                ) as dest:
                    dest.write(dataset.read())
                
                datasets_to_merge.append(temp_dst.open())

        if not datasets_to_merge:
            raise ValueError("Failed to download any tiles.")

        # Stitch
        mosaic, out_trans = merge(datasets_to_merge)

        # Crop to BBox
        left, bottom = mercantile.xy(west, south)
        right, top = mercantile.xy(east, north)
        
        row_start, col_start = ~out_trans * (left, top)
        row_stop, col_stop = ~out_trans * (right, bottom)
        
        row_start, row_stop = sorted([int(row_start), int(row_stop)])
        col_start, col_stop = sorted([int(col_start), int(col_stop)])

        cropped_img = mosaic[:, row_start:row_stop, col_start:col_stop]
        new_transform = from_bounds(left, bottom, right, top, cropped_img.shape[2], cropped_img.shape[1])

        return cropped_img, new_transform, 'EPSG:3857'

    finally:
        for f in datasets_to_merge + mem_files:
            try: f.close()
            except: pass

# Inside export_service.py

def generate_custom_package(bounds_dict):
    generated_files = []

    # 1. PROCESS BLUE BOX -> GEOTIFF (The ForeFlight Overlay)
    if 'blue' in bounds_dict and bounds_dict['blue']:
        try:
            logger.info("Generating GeoTIFF for ForeFlight...")
            # We use zoom 17 for a balance of high-res and file size
            img, transform, crs = get_stitched_image(bounds_dict['blue'], zoom=17)
            
            filename = "ForeFlight_Imagery_Overlay.tif"
            
            # The 'with rasterio.open' block creates the actual GeoTIFF
            with rasterio.open(
                filename, 
                "w", 
                driver="GTiff",  # Standard GeoTIFF driver
                height=img.shape[1], 
                width=img.shape[2], 
                count=3,
                dtype=img.dtype, 
                crs=crs,          # Crucial: EPSG:3857
                transform=transform # Crucial: Maps pixels to Lat/Long
            ) as dest:
                dest.write(img)
            
            generated_files.append(filename)
        except Exception as e:
            logger.error(f"GeoTIFF generation failed: {e}")

    # 2. PROCESS RED BOX -> JPG (Standard Visual)
    if 'red' in bounds_dict and bounds_dict['red']:
        try:
            # Existing JPG logic...
            img_red, _, _ = get_stitched_image(bounds_dict['red'], zoom=18)
            filename_red = "Target_Detail.jpg"
            img_array = np.moveaxis(img_red, 0, -1)
            Image.fromarray(img_array.astype('uint8')).save(filename_red, "JPEG", quality=100)
            generated_files.append(filename_red)
        except Exception as e:
            logger.error(f"JPG generation failed: {e}")

    return generated_files