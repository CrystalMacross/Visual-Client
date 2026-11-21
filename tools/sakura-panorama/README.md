# Sakura title-screen panorama

`render_panorama.py` renders a Minecraft-style cherry blossom garden at night (procedural, no game
assets) as the 6 cube faces Minecraft uses for the title-screen background. It is a small path
tracer: moonlight with soft shadows, one bounce of indirect light, glowing lanterns, volumetric
mist, reflective water with ripples, a starry sky, denoising, bloom and a filmic tone curve.

```
python -m venv venv && venv/bin/pip install numpy numba pillow
venv/bin/python render_panorama.py out 1024 24      # size, samples per pixel (about 5 min)
cp out/panorama_*.png ../texture-painter-patch/resources/assets/minecraft/textures/gui/title/background/
```

The patcher puts them into the Visual Client bundle jar (`assets/minecraft/textures/gui/title/background/`),
so the panorama replaces the vanilla one. A resource pack with its own panorama still wins.
