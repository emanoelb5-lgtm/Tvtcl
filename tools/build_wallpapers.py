#!/usr/bin/env python3
"""Empacota cinco fotografias Unsplash em 1280x720 WebP, 100% offline em runtime.
O script roda SOMENTE no GitHub Actions, não na televisão.
"""
from pathlib import Path
from io import BytesIO
import urllib.request
import time
from PIL import Image, ImageOps, ImageEnhance

# Obras distribuídas de acordo com a licença Unsplash.
# Ver NOTICE_WALLPAPERS.md e seus links de autoria.
PHOTOS = [
    "photo-1506744038136-46273834b3fb",  # Montanhas e lago
    "photo-1470770841072-f978cf4d019e",  # Lago de montanha
    "photo-1500534623283-312aade485b7",  # Panorama natural
    "photo-1519681393784-d120267933ba",  # Montanhas noturnas
    "photo-1441974231531-c6227db76b6e",  # Floresta
]

out = Path("app/src/main/res/drawable-nodpi")
out.mkdir(parents=True, exist_ok=True)
for number, photo in enumerate(PHOTOS, 1):
    url = "https://images.unsplash.com/" + photo + "?w=1280&h=720&fit=crop&q=70&fm=jpg"
    data = None
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "TCL-Light-AndroidTV/0.5"})
            with urllib.request.urlopen(req, timeout=30) as response:
                data = response.read()
            if len(data) < 5000:
                raise ValueError("Image data too small")
            break
        except Exception as error:
            print(f"Photo {number}: attempt {attempt + 1}: {error}")
            time.sleep(2 + attempt * 2)
    if data is None:
        raise RuntimeError(f"Could not acquire licensed photo {number}: {photo}")
    with Image.open(BytesIO(data)) as original:
        image = ImageOps.fit(original.convert("RGB"), (1280, 720),
                             method=Image.Resampling.LANCZOS)
        image = ImageEnhance.Color(image).enhance(.92)
        dest = out / f"wallpaper_{number:02d}.webp"
        image.save(dest, format="WEBP", quality=65, method=5)
        print(f"{dest}: {dest.stat().st_size:,} bytes")
