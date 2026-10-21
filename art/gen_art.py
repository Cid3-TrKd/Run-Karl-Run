#!/usr/bin/env python3
"""Run-Karl-Run — art generator.

Everything here is drawn from scratch with PIL, except the launcher icon, which
is derived from cid3trk's own ghost-Karl rendering (art/ghost-karl.jpeg).

Outputs (relative to the project root):
  app/src/main/res/mipmap-*/ic_launcher.png
  app/src/main/res/mipmap-*/ic_launcher_round.png
  app/src/main/res/mipmap-*/ic_launcher_foreground.png
  app/src/main/res/drawable-xxhdpi/cat_run_0..7.png     (72px = 24dp)
  app/src/main/res/drawable-nodpi/fatcat_*.png          (256px colourways)
  art/preview/sheet.png                                 (inspection only)
"""

import os
import math
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
PREVIEW = os.path.join(ROOT, "art", "preview")

GHOST_KARL = os.path.join(ROOT, "art", "ghost-karl.jpeg")

BLACK = (0, 0, 0, 255)

# ---------------------------------------------------------------- launcher icon

def _rounded_square(size, radius_ratio=0.0):
    m = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(m)
    r = int(size * 4 * radius_ratio)
    d.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=r, fill=255)
    return m.resize((size, size), Image.LANCZOS)


def gen_launcher_icons():
    src = Image.open(GHOST_KARL).convert("RGB")
    # Square-crop to be safe, then resize as needed.
    side = min(src.size)
    left = (src.width - side) // 2
    top = (src.height - side) // 2
    src = src.crop((left, top, left + side, top + side))

    legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    adaptive = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

    for dens, px in legacy.items():
        d = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        icon = src.resize((px, px), Image.LANCZOS).convert("RGBA")
        icon.save(os.path.join(d, "ic_launcher.png"))

        # circular mask for the round variant
        mask = _rounded_square(px, 0.5)
        rnd = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        rnd.paste(icon, (0, 0), mask)
        rnd.save(os.path.join(d, "ic_launcher_round.png"))

    for dens, canvas in adaptive.items():
        d = os.path.join(RES, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        # 66% of the canvas keeps the cat inside the 72dp safe zone.
        inner = int(round(canvas * 0.66))
        img = src.resize((inner, inner), Image.LANCZOS).convert("RGBA")
        out = Image.new("RGBA", (canvas, canvas), BLACK)
        off = (canvas - inner) // 2
        out.paste(img, (off, off))
        out.save(os.path.join(d, "ic_launcher_foreground.png"))

    print("launcher icons: 5 densities (legacy, round, adaptive foreground)")


# ------------------------------------------------------------ running cat frames
# Drawn in a 24x24 unit space, rendered at 16x and downsampled.

S = 16           # render scale
W = 24 * S       # 384


def _p(x, y):
    return (x * S, y * S)


def _stroke(draw, pts, width_units):
    draw.line([_p(*pt) for pt in pts], fill=BLACK,
              width=int(round(width_units * S)), joint="curve")
    for pt in pts:
        r = width_units * S / 2.0
        x, y = _p(*pt)
        draw.ellipse([x - r, y - r, x + r, y + r], fill=BLACK)


def _leg(draw, hip, a1, a2, l1=2.55, l2=2.75, width=1.85):
    """Two-segment leg. Angles measured from straight-down, + = forward."""
    hx, hy = hip
    kx = hx + l1 * math.sin(math.radians(a1))
    ky = hy + l1 * math.cos(math.radians(a1))
    px = kx + l2 * math.sin(math.radians(a2))
    py = ky + l2 * math.cos(math.radians(a2))
    _stroke(draw, [(hx, hy), (kx, ky), (px, py)], width)


def _run_frame(phase):
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # s = +1 fully extended, s = -1 fully gathered. A gallop only reads at 24dp
    # if the whole silhouette changes, not just the legs: the body must be long
    # and low when the legs are splayed, short and high when they tuck under.
    s = math.sin(phase)
    c = math.cos(phase)

    hl = 6.3 + 1.6 * s          # body half length
    hh = 3.9 - 0.45 * s         # body half height
    cx = 11.4
    cy = 12.3 + 1.2 * s         # low on the reach, high on the gather

    # tail streams out behind and whips once per stride
    tx, ty = cx - hl + 0.7, cy - 1.4
    _stroke(d, [(tx, ty),
                (tx - 1.8, ty - 0.1 + 0.5 * c),
                (tx - 3.1, ty - 1.4 + 0.95 * c),
                (tx - 3.5, ty - 3.0 + 1.35 * c)], 1.75)

    # Legs: the reach splays front forward and hind back; the gather folds both
    # under the body. The near pair trails the far pair slightly.
    for near, off in ((False, 0.55), (True, 0.0)):
        sw = math.sin(phase + off)
        fold = 62.0 * (1.0 - sw) * 0.5
        ha = -58.0 * sw
        _leg(d, (cx - hl + 2.4, cy + hh - 0.85), ha, ha - fold,
             l1=2.5, l2=2.6, width=1.7 if near else 1.48)
        fa = 58.0 * sw
        _leg(d, (cx + hl - 2.4, cy + hh - 0.85), fa, fa + fold,
             l1=2.4, l2=2.5, width=1.76 if near else 1.5)

    # body
    d.ellipse([_p(cx - hl, cy - hh), _p(cx + hl, cy + hh)], fill=BLACK)

    # head, carried on the front of the body
    hx, hy, hr = cx + hl - 1.1, cy - 2.0, 3.25
    d.ellipse([_p(hx - hr, hy - hr), _p(hx + hr, hy + hr)], fill=BLACK)
    d.polygon([_p(hx - 2.5, hy - 1.7), _p(hx - 1.9, hy - 5.1), _p(hx - 0.2, hy - 2.4)], fill=BLACK)
    d.polygon([_p(hx + 0.55, hy - 2.4), _p(hx + 2.45, hy - 4.85), _p(hx + 3.0, hy - 1.5)], fill=BLACK)
    return img


def gen_run_frames():
    d = os.path.join(RES, "drawable-xxhdpi")
    os.makedirs(d, exist_ok=True)
    frames = []
    for f in range(8):
        img = _run_frame(2 * math.pi * f / 8.0)
        out = img.resize((72, 72), Image.LANCZOS)
        out.save(os.path.join(d, "cat_run_%d.png" % f))
        frames.append(out)
    print("run frames: 8 x 72px (24dp)")


# -------------------------------------------------------------- fat-cat colourways

CAT = 256          # output size
DESIGN = 384       # the fat-cats are authored in this coordinate space
R = 2              # render scale (768px canvas, then downsample to CAT)

INK = (24, 20, 18, 255)        # outline colour

COLOURWAYS = {
    # key: (body, belly, ear_inner, eye, eye_style)
    "black":  ((28, 28, 30, 255),  (46, 46, 50, 255),  (120, 70, 80, 255),  (250, 196, 20, 255), "yellow"),
    "white":  ((246, 242, 232, 255), (255, 255, 255, 255), (232, 170, 180, 255), (18, 18, 18, 255), "black"),
    "grey":   ((138, 138, 142, 255), (166, 166, 170, 255), (205, 140, 150, 255), (18, 18, 18, 255), "black"),
    "blue":   ((70, 110, 160, 255), (96, 136, 186, 255), (230, 160, 170, 255), (250, 196, 20, 255), "yellow"),
    "orange": ((214, 126, 44, 255), (236, 158, 78, 255), (240, 160, 170, 255), (250, 196, 20, 255), "yellow"),
    "calico": ((246, 242, 232, 255), (255, 255, 255, 255), (232, 170, 180, 255), (250, 196, 20, 255), "yellow"),
}

PATCH_ORANGE = (214, 126, 44, 255)
PATCH_DARK = (34, 30, 28, 255)


def _scale(pts):
    return [(x * R, y * R) for x, y in pts]


def _tapered(draw, pts, widths, fill):
    """A polyline with a continuously varying radius (no gaps, round ends)."""
    for i in range(len(pts) - 1):
        (x0, y0), (x1, y1) = pts[i], pts[i + 1]
        w0, w1 = widths[i], widths[i + 1]
        dist = math.hypot(x1 - x0, y1 - y0)
        steps = max(2, int(dist / 1.5))
        for s in range(steps + 1):
            t = s / steps
            x = x0 + (x1 - x0) * t
            y = y0 + (y1 - y0) * t
            w = w0 + (w1 - w0) * t
            draw.ellipse([x - w, y - w, x + w, y + w], fill=fill)


def _draw_fatcat(body, belly, ear_inner, eye, eye_style, calico=False):
    n = DESIGN * R
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    def S(v):
        return v * R

    # --- silhouette mask (body, head, ears, tail, paws) for calico patches
    mask = Image.new("L", (n, n), 0)
    md = ImageDraw.Draw(mask)

    body_box = [S(78), S(150), S(178), S(345)]
    head_box = [S(62), S(58), S(194), S(212)]
    tail_pts = [(S(150), S(300)), (S(200), S(292)), (S(240), S(262)),
                (S(256), S(222)), (S(246), S(182)), (S(224), S(160))]
    tail_w = [S(16), S(15), S(13), S(11), S(9), S(7)]

    md.ellipse(body_box, fill=255)
    md.ellipse(head_box, fill=255)
    md.polygon(_scale([(92, 96), (74, 26), (128, 62)]), fill=255)
    md.polygon(_scale([(164, 62), (196, 24), (206, 98)]), fill=255)
    _tapered(md, tail_pts, tail_w, 255)
    md.ellipse([S(112), S(310), S(178), S(352)], fill=255)
    md.ellipse([S(170), S(306), S(228), S(350)], fill=255)

    # --- tail (outline first, then the fill on top)
    _tapered(d, tail_pts, [w + S(4) for w in tail_w], INK)
    _tapered(d, tail_pts, tail_w, body)

    # --- body
    d.ellipse(body_box, fill=body, outline=INK, width=S(5))
    # belly
    d.ellipse([S(104), S(226), S(196), S(330)], fill=belly)

    # --- paws
    for box in ([S(112), S(310), S(162), S(348)], [S(178), S(306), S(228), S(344)]):
        d.ellipse(box, fill=belly, outline=INK, width=S(4))

    # --- head
    d.ellipse(head_box, fill=body, outline=INK, width=S(5))

    # --- ears (drawn over the head outline)
    for tri, inner in [
        (_scale([(92, 96), (74, 26), (128, 62)]), _scale([(96, 84), (84, 42), (116, 66)])),
        (_scale([(164, 62), (196, 24), (206, 98)]), _scale([(172, 70), (192, 40), (198, 86)])),
    ]:
        d.polygon(tri, fill=body, outline=INK)
        d.line(tri + [tri[0]], fill=INK, width=S(5), joint="curve")
        d.polygon(inner, fill=ear_inner)

    # --- calico patches, clipped to the silhouette
    if calico:
        patches = Image.new("RGBA", (n, n), (0, 0, 0, 0))
        pd = ImageDraw.Draw(patches)
        pd.ellipse([S(60), S(64), S(130), S(150)], fill=PATCH_DARK)          # left brow
        pd.ellipse([S(150), S(44), S(206), S(120)], fill=PATCH_ORANGE)        # right ear/head
        pd.ellipse([S(66), S(196), S(150), S(300)], fill=PATCH_ORANGE)       # left flank
        pd.ellipse([S(160), S(230), S(212), S(330)], fill=PATCH_DARK)        # right flank
        pd.ellipse([S(120), S(120), S(168), S(170)], fill=PATCH_ORANGE)      # forehead
        patches.putalpha(Image.composite(patches.getchannel("A"), Image.new("L", (n, n), 0), mask))
        patches = Image.composite(patches, Image.new("RGBA", (n, n), (0, 0, 0, 0)), mask)
        img.alpha_composite(patches)
        d = ImageDraw.Draw(img)

    # --- eyes
    for cx in (S(128), S(192)):
        cy = S(146)
        er = S(26)
        if eye_style == "yellow":
            d.ellipse([cx - er, cy - er, cx + er, cy + er], fill=eye, outline=INK, width=S(3))
            d.ellipse([cx - S(10), cy - S(12), cx + S(10), cy + S(12)], fill=INK)
            d.ellipse([cx - S(16), cy - S(16), cx - S(4), cy - S(4)], fill=(255, 255, 255, 235))
        else:
            d.ellipse([cx - er, cy - er, cx + er, cy + er], fill=eye)
            d.ellipse([cx - S(8), cy - S(10), cx + S(8), cy + S(10)], fill=(70, 70, 74, 255))
            d.ellipse([cx - S(15), cy - S(15), cx - S(6), cy - S(6)], fill=(255, 255, 255, 220))

    # --- nose + mouth
    nose = _scale([(122, 178), (134, 178), (128, 190)])
    d.polygon(nose, fill=(226, 138, 150, 255), outline=INK)
    d.arc([S(112), S(186), S(128), S(202)], 0, 90, fill=INK, width=S(3))
    d.arc([S(128), S(186), S(144), S(202)], 90, 180, fill=INK, width=S(3))
    d.line([S(128), S(190), S(128), S(198)], fill=INK, width=S(3))

    # --- whiskers (dark on light coats, light on dark coats)
    lum = 0.299 * body[0] + 0.587 * body[1] + 0.114 * body[2]
    whisker = (95, 95, 95, 220) if lum > 150 else (238, 238, 238, 200)
    for y, dx in [(S(176), 0), (S(184), 4), (S(192), 8)]:
        d.line([S(100), y, S(44), y - S(6) + dx], fill=whisker, width=S(2))
        d.line([S(156), y, S(212), y - S(6) + dx], fill=whisker, width=S(2))

    return img.resize((CAT, CAT), Image.LANCZOS)


def gen_fatcats():
    d = os.path.join(RES, "drawable-nodpi")
    os.makedirs(d, exist_ok=True)
    imgs = {}
    for key, (body, belly, ear_inner, eye, style) in COLOURWAYS.items():
        img = _draw_fatcat(body, belly, ear_inner, eye, style, calico=(key == "calico"))
        img.save(os.path.join(d, "fatcat_%s.png" % key))
        imgs[key] = img
    print("fat-cats: %d colourways" % len(imgs))
    return imgs


# ------------------------------------------------------------------- inspection

def gen_preview(fatcats, frames):
    os.makedirs(PREVIEW, exist_ok=True)
    sheet = Image.new("RGBA", (6 * 256, 256 + 8 * 96 + 40), (18, 18, 20, 255))
    for i, key in enumerate(COLOURWAYS):
        sheet.alpha_composite(fatcats[key], (i * 256, 0))
    for i in range(8):
        f = frames[i].resize((96, 96), Image.LANCZOS)
        sheet.alpha_composite(f, (i * 96 + 8, 276))
    sheet.save(os.path.join(PREVIEW, "sheet.png"))
    print("preview: art/preview/sheet.png")


if __name__ == "__main__":
    gen_launcher_icons()
    gen_run_frames()
    fatcats = gen_fatcats()
    frames = [Image.open(os.path.join(RES, "drawable-xxhdpi", "cat_run_%d.png" % i)).convert("RGBA")
              for i in range(8)]
    gen_preview(fatcats, frames)
