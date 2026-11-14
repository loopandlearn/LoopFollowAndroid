"""Builds the Android launcher icon layers from the iOS AppIcon set.

Usage: python3 tools/make_icon.py <LoopFollow>/LoopFollow/Resources/Assets.xcassets/AppIcon.appiconset app/src/main/res
Needs Pillow.
"""
import sys, os
from PIL import Image
src_dir, res = sys.argv[1], sys.argv[2]
full = Image.open(f"{src_dir}/1024.png").convert("RGBA")
tinted = Image.open(f"{src_dir}/1024-tinted.png").convert("RGBA")
dens = {"mdpi":1,"hdpi":1.5,"xhdpi":2,"xxhdpi":3,"xxxhdpi":4}
for d,f in dens.items():
    canvas = round(108*f); inner = round(72*f); off=(canvas-inner)//2
    fg = Image.new("RGBA",(canvas,canvas),(0,0,0,0))
    fg.paste(full.resize((inner,inner),Image.LANCZOS),(off,off))
    os.makedirs(f"{res}/mipmap-{d}",exist_ok=True)
    fg.save(f"{res}/mipmap-{d}/ic_launcher_foreground.png")
    # monochrome: luminance of tinted image as alpha, ring only
    g = tinted.convert("L").resize((inner,inner),Image.LANCZOS)
    mono = Image.new("RGBA",(canvas,canvas),(0,0,0,0))
    a = Image.new("RGBA",(inner,inner),(255,255,255,255)); a.putalpha(g)
    mono.paste(a,(off,off))
    mono.save(f"{res}/mipmap-{d}/ic_launcher_monochrome.png")
    # legacy square for any launcher that ignores adaptive
    full.resize((round(48*f),round(48*f)),Image.LANCZOS).save(f"{res}/mipmap-{d}/ic_launcher.png")
