"""Fanos launcher icon: one description, written as Android vector drawables and as an SVG preview.

The Greek letter phi as a copper lamp: the ring is the glass, the stroke through it the flame, carrying
on down as the stand. The flame casts a soft orange light out over the night, and behind the lamp, faint
and white, lies an open book.

    py tools/launcher_icon.py           writes a preview page (masks, sizes, themed) to the temp directory
    py tools/launcher_icon.py --write   also writes the drawables and the background colour

The lamp alone, in one colour, is also the notifications' status bar icon.
"""
import re
import sys
import tempfile
from pathlib import Path

OUT_RES = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
# Notifications are posted from :core:data, so their icon lives there.
OUT_NOTIFICATION = Path(__file__).resolve().parents[1] / "core" / "data" / "src" / "main" / "res" / "drawable" / "ic_notification_lamp.xml"
PREVIEW = Path(tempfile.gettempdir()) / "fanos-launcher-icon.html"

BACKGROUND = "#101828"

# ---- geometry -------------------------------------------------------------------------------------

RING = "M54,31a19,19 0,1 1,0 38a19,19 0,1 1,0 -38z M54,35.2a14.8,14.8 0,1 0,0 29.6a14.8,14.8 0,1 0,0 -29.6z"
GLASS = "M54,50m-14.8,0a14.8,14.8 0,1 1,29.6 0a14.8,14.8 0,1 1,-29.6 0"
FINIAL = "M51.9,26h4.2v8h-4.2z M54,22.5a3,3 0,1 1,0 6a3,3 0,1 1,0 -6z"
FLAME = "M54,37C57.8,42.2 60,46.6 60,51.2C60,55.2 57.3,58.2 54,58.2C50.7,58.2 48,55.2 48,51.2C48,46.6 50.2,42.2 54,37z"
FLAME_CORE = "M54,45C56.1,47.8 57.2,50 57.2,52.1C57.2,54.2 55.8,55.8 54,55.8C52.2,55.8 50.8,54.2 50.8,52.1C50.8,50 51.9,47.8 54,45z"
STAND = (
    "M51.9,57h4.2v19h-4.2z M48,75.5L60,75.5L61.5,78L46.5,78z "
    "M42.5,78h23c0.83,0 1.5,0.67 1.5,1.5v2c0,0.83 -0.67,1.5 -1.5,1.5h-23c-0.83,0 -1.5,-0.67 -1.5,-1.5v-2c0,-0.83 0.67,-1.5 1.5,-1.5z"
)
# The square of the grid the status bar icon shows (left, top, side): the lamp (x 35-73, y 22.5-83),
# centred, filling the height as a Material icon fills its 24dp less a 2dp margin.
NOTIFICATION_BOX = (18, 16.75, 72)

CANVAS = "M0,0h108v108h-108z"

# Everything but the lamp's glass: the book is behind the lamp, so none of it shows through the glass.
# The square runs clockwise and the circle anticlockwise, so under the nonzero rule the circle is a hole.
NOT_GLASS = "M0,0h108v108h-108z M54,31a19,19 0,1 0,0 38a19,19 0,1 0,0 -38z"

# The open book: two pages rising from the spine (x 54) to their outer edges.
SPINE_TOP, SPINE_BOTTOM = 59.5, 79.5
OUTER_X, OUTER_TOP, OUTER_BOTTOM = 26.5, 53.5, 74.5
STACK = 1.8  # the thickness of the page block under the open pages


def mirror(x):
    return 108 - x


def page_edges(side):
    """Top and bottom edges of one page as cubic Beziers from the spine outwards."""
    sx = 54.0
    ox = OUTER_X if side < 0 else mirror(OUTER_X)
    k1 = 54 + side * 4
    k2 = 54 + side * 14
    top = [(sx, SPINE_TOP), (k1, SPINE_TOP - 4.3), (k2, OUTER_TOP - 0.9), (ox, OUTER_TOP)]
    bottom = [(sx, SPINE_BOTTOM), (k1, SPINE_BOTTOM - 4.1), (k2, OUTER_BOTTOM - 1.1), (ox, OUTER_BOTTOM)]
    return top, bottom


def fmt(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def pt(p):
    return f"{fmt(p[0])},{fmt(p[1])}"


def page_path(side):
    top, bottom = page_edges(side)
    return (
        f"M{pt(top[0])}C{pt(top[1])} {pt(top[2])} {pt(top[3])}"
        f"L{pt(bottom[3])}C{pt(bottom[2])} {pt(bottom[1])} {pt(bottom[0])}z"
    )


def stack_path(side):
    _, bottom = page_edges(side)
    lower = [(x, y + STACK) for x, y in bottom]
    return (
        f"M{pt(bottom[0])}C{pt(bottom[1])} {pt(bottom[2])} {pt(bottom[3])}"
        f"L{pt(lower[3])}C{pt(lower[2])} {pt(lower[1])} {pt(lower[0])}z"
    )


def bezier(ctrl, t):
    (x0, y0), (x1, y1), (x2, y2), (x3, y3) = ctrl
    u = 1 - t
    return (
        u**3 * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t**3 * x3,
        u**3 * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t**3 * y3,
    )


def text_lines(side):
    """Lines of type on a page, following its curve."""
    top, bottom = page_edges(side)
    paths = []
    for f in (0.2, 0.34, 0.48, 0.62, 0.76):
        pts = []
        for i in range(9):
            t = 0.14 + (0.86 - 0.14) * i / 8
            a, b = bezier(top, t), bezier(bottom, t)
            pts.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f))
        paths.append("M" + " L".join(pt(p) for p in pts))
    return " ".join(paths)


# ---- paint ----------------------------------------------------------------------------------------


def solid(color, alpha=1.0):
    return ("solid", color, alpha)


def linear(x1, y1, x2, y2, stops):
    return ("linear", (x1, y1, x2, y2), stops)


def radial(cx, cy, r, stops):
    return ("radial", (cx, cy, r), stops)


METAL = linear(54, 22, 54, 84, [(0, "#F6C19B", 1), (0.45, "#CF7B48", 1), (1, "#843F20", 1)])  # copper
FLAME_FILL = radial(54, 55.5, 18.5, [(0, "#FFC247", 1), (0.45, "#FF9B2F", 1), (1, "#F0561C", 1)])
CORE_FILL = radial(54, 53.5, 7, [(0, "#FFF8E6", 1), (1, "#FFD46A", 1)])
GLOW_FILL = radial(54, 52, 14.8, [(0, "#FFB35A", 0.5), (0.55, "#FF8A3D", 0.16), (1, "#FF8A3D", 0.03)])
# The flame's light cast out over the night, gone before the corners.
HALO_FILL = radial(54, 51, 50, [(0, "#FF9B4A", 0.5), (0.35, "#FF8A3D", 0.22), (0.7, "#FF8A3D", 0.06), (1, "#FF8A3D", 0)])
# White pages, brightest near the lamp and faded towards their outer edges.
PAGE_FILL = radial(54, 55, 30, [(0, "#FFFFFF", 0.26), (0.6, "#F1EEE9", 0.18), (1, "#F1EEE9", 0.1)])
STACK_FILL = solid("#EDE7DE", 0.14)
LINES = ("stroke", "#2E2A26", 0.22, 0.8)


def colour_layers():
    book = [
        {"d": stack_path(-1) + " " + stack_path(1), "fill": STACK_FILL},
        {"d": page_path(-1) + " " + page_path(1), "fill": PAGE_FILL},
        {"d": text_lines(-1) + " " + text_lines(1), "stroke": LINES},
    ]
    return [
        {"d": CANVAS, "fill": HALO_FILL, "comment": "The flame's light, cast out over the night"},
        {"group": book, "clip": NOT_GLASS, "comment": "Behind the lamp, faint: an open book"},
        {"d": GLASS, "fill": GLOW_FILL, "comment": "Light inside the glass"},
        {"d": RING, "fill": METAL, "evenOdd": True, "comment": "Ring of the phi: the glass"},
        {"d": FINIAL, "fill": METAL, "comment": "Top of the stroke: the finial"},
        {"d": FLAME, "fill": FLAME_FILL, "comment": "The stroke through the ring: the flame"},
        {"d": FLAME_CORE, "fill": CORE_FILL},
        {"d": STAND, "fill": METAL, "comment": "Below the ring: the stand and foot"},
    ]


def mono_layers():
    black = solid("#000000")
    return [
        {"group": [{"d": page_path(-1) + " " + page_path(1) + " " + stack_path(-1) + " " + stack_path(1), "fill": solid("#000000", 0.25)}],
         "clip": NOT_GLASS, "comment": "The book, faint"},
        {"d": RING, "fill": black, "evenOdd": True},
        {"d": FINIAL, "fill": black},
        {"d": FLAME, "fill": black},
        {"d": STAND, "fill": black},
    ]


# ---- Android vector drawable ----------------------------------------------------------------------


def argb(color, alpha):
    return f"#{round(alpha * 255):02X}{color.lstrip('#').upper()}"


def vd_path(layer, indent):
    pad = " " * indent
    attrs = [f'android:pathData="{layer["d"]}"']
    if layer.get("evenOdd"):
        attrs.append('android:fillType="evenOdd"')
    gradient = None
    if "stroke" in layer:
        _, color, alpha, width = layer["stroke"]
        attrs += [f'android:strokeColor="{argb(color, alpha)}"', f'android:strokeWidth="{width}"', 'android:strokeLineCap="round"']
    else:
        kind, a, b = layer["fill"]
        if kind == "solid":
            attrs.insert(0, f'android:fillColor="{argb(a, b)}"')
        else:
            gradient = (kind, a, b)
    lines = []
    if layer.get("comment"):
        lines.append(f"{pad}<!-- {layer['comment']} -->")
    if gradient is None:
        lines.append(f"{pad}<path\n{pad}    " + f"\n{pad}    ".join(attrs) + " />")
        return lines
    kind, geo, stops = gradient
    lines.append(f"{pad}<path " + " ".join(attrs) + ">")
    lines.append(f'{pad}    <aapt:attr name="android:fillColor">')
    if kind == "linear":
        x1, y1, x2, y2 = geo
        head = f'android:type="linear" android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}"'
    else:
        cx, cy, r = geo
        head = f'android:type="radial" android:centerX="{cx}" android:centerY="{cy}" android:gradientRadius="{r}"'
    lines.append(f"{pad}        <gradient {head}>")
    for offset, color, alpha in stops:
        lines.append(f'{pad}            <item android:offset="{offset}" android:color="{argb(color, alpha)}" />')
    lines.append(f"{pad}        </gradient>")
    lines.append(f"{pad}    </aapt:attr>")
    lines.append(f"{pad}</path>")
    return lines


def vector_drawable(layers, comment):
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!--",
        *[f"    {line}" for line in comment.splitlines()],
        "    Generated by tools/launcher_icon.py: edit it there, not here.",
        "-->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    xmlns:aapt="http://schemas.android.com/aapt"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
    ]
    for layer in layers:
        if "group" in layer:
            out.append(f"    <!-- {layer['comment']} -->")
            out.append("    <group>")
            out.append(f'        <clip-path android:pathData="{layer["clip"]}" />')
            for child in layer["group"]:
                out += vd_path(child, 8)
            out.append("    </group>")
        else:
            out += vd_path(layer, 4)
    out.append("</vector>")
    return "\n".join(out) + "\n"


def notification_layers():
    white = solid("#FFFFFF")
    return [
        {"d": RING, "fill": white, "evenOdd": True},
        {"d": FINIAL, "fill": white},
        {"d": FLAME, "fill": white},
        {"d": STAND, "fill": white},
    ]


def notification_drawable():
    left, top, side = NOTIFICATION_BOX
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!--",
        "    The phi lamp alone, for the status bar: Android draws it in one colour.",
        "    Generated by tools/launcher_icon.py: edit it there, not here.",
        "-->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        f'    android:viewportWidth="{fmt(side)}"',
        f'    android:viewportHeight="{fmt(side)}">',
        f'    <group android:translateX="{fmt(-left)}" android:translateY="{fmt(-top)}">',
    ]
    for layer in notification_layers():
        out += vd_path(layer, 8)
    out += ["    </group>", "</vector>"]
    return "\n".join(out) + "\n"


# ---- SVG preview ----------------------------------------------------------------------------------

_ids = [0]


def svg_layers(layers, defs):
    out = []
    for layer in layers:
        if "group" in layer:
            _ids[0] += 1
            cid = f"c{_ids[0]}"
            defs.append(f'<clipPath id="{cid}"><path d="{layer["clip"]}" clip-rule="nonzero"/></clipPath>')
            out.append(f'<g clip-path="url(#{cid})">' + "".join(svg_layers(layer["group"], defs)) + "</g>")
            continue
        rule = ' fill-rule="evenodd"' if layer.get("evenOdd") else ""
        if "stroke" in layer:
            _, color, alpha, width = layer["stroke"]
            out.append(f'<path d="{layer["d"]}" fill="none" stroke="{color}" stroke-opacity="{alpha}" stroke-width="{width}" stroke-linecap="round"/>')
            continue
        kind, a, b = layer["fill"]
        if kind == "solid":
            out.append(f'<path d="{layer["d"]}" fill="{a}" fill-opacity="{b}"{rule}/>')
            continue
        _ids[0] += 1
        gid = f"g{_ids[0]}"
        stops = "".join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{al}"/>' for o, c, al in b)
        if kind == "linear":
            x1, y1, x2, y2 = a
            defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{stops}</linearGradient>')
        else:
            cx, cy, r = a
            defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">{stops}</radialGradient>')
        out.append(f'<path d="{layer["d"]}" fill="url(#{gid})"{rule}/>')
    return out


def svg_icon(size, layers, background, mask="circle", tint=None):
    defs = []
    body = "".join(svg_layers(layers, defs))
    _ids[0] += 1
    mid = f"m{_ids[0]}"
    if mask == "circle":
        defs.append(f'<clipPath id="{mid}"><circle cx="54" cy="54" r="36"/></clipPath>')
    else:  # a squircle-ish rounded square over the 72dp visible area
        defs.append(f'<clipPath id="{mid}"><rect x="18" y="18" width="72" height="72" rx="22"/></clipPath>')
    if tint:
        _ids[0] += 1
        fid = f"f{_ids[0]}"
        defs.append(f'<filter id="{fid}"><feFlood flood-color="{tint}"/><feComposite in2="SourceAlpha" operator="in"/></filter>')
        body = f'<g filter="url(#{fid})">{body}</g>'
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="18 18 72 72">'
        f"<defs>{''.join(defs)}</defs>"
        f'<g clip-path="url(#{mid})"><rect x="0" y="0" width="108" height="108" fill="{background}"/>{body}</g></svg>'
    )


def svg_notification_icon(size, background):
    defs = []
    left, top, side = NOTIFICATION_BOX
    body = "".join(svg_layers(notification_layers(), defs))
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="{left} {top} {side} {side}">'
        f"<defs>{''.join(defs)}</defs>"
        f'<rect x="{left}" y="{top}" width="{side}" height="{side}" fill="{background}"/>{body}</svg>'
    )


def preview():
    colour = colour_layers()
    mono = mono_layers()
    cells = [
        svg_icon(360, colour, BACKGROUND),
        svg_icon(200, colour, BACKGROUND, mask="squircle"),
        svg_icon(96, colour, BACKGROUND),
        svg_icon(56, colour, BACKGROUND),
        svg_icon(120, mono, "#D3E3FD", tint="#0B3A6E"),
        svg_icon(120, mono, "#1F2A3C", tint="#C2D7F5"),
        svg_notification_icon(24, "#7A5A00"),
        svg_notification_icon(72, "#7A5A00"),
    ]
    html = (
        "<!DOCTYPE html><html><body style='margin:0;background:#E9ECF1;font-family:sans-serif'>"
        "<div style='display:flex;flex-wrap:wrap;gap:24px;align-items:center;padding:24px'>"
        + "".join(cells)
        + "</div><div style='display:flex;gap:24px;align-items:center;padding:0 24px 24px;background:#202124'>"
        + "".join([svg_icon(360, colour, BACKGROUND), svg_icon(96, colour, BACKGROUND), svg_icon(56, colour, BACKGROUND)])
        + "</div></body></html>"
    )
    PREVIEW.write_text(html, encoding="utf-8", newline="\n")
    print(f"preview: {PREVIEW}")


def main():
    preview()
    if "--write" in sys.argv:
        # newline="\n": the repository's files are LF, and text mode on Windows would write CRLF.
        (OUT_RES / "drawable" / "ic_launcher_foreground.xml").write_text(
            vector_drawable(
                colour_layers(),
                "Fanos: the Greek letter phi (the first letter of φανός, lamp) drawn as a copper lamp. The ring\n"
                "is the lamp's glass; the stroke through it is the flame, carrying on down as the stand and foot.\n"
                "The flame casts a soft orange light; behind the lamp, faint and white, lies an open book.",
            ),
            encoding="utf-8",
            newline="\n",
        )
        (OUT_RES / "drawable" / "ic_launcher_monochrome.xml").write_text(
            vector_drawable(mono_layers(), "The phi lamp in one colour, for themed icons; the book behind it at low opacity."),
            encoding="utf-8",
            newline="\n",
        )
        OUT_NOTIFICATION.write_text(notification_drawable(), encoding="utf-8", newline="\n")
        colors = OUT_RES / "values" / "colors.xml"
        text = colors.read_text(encoding="utf-8")
        text = re.sub(r'(<color name="launcher_background">)#[0-9A-Fa-f]+(</color>)', rf"\g<1>#FF{BACKGROUND.lstrip('#').upper()}\g<2>", text)
        colors.write_text(text, encoding="utf-8", newline="\n")
        print("wrote drawables and background colour")


if __name__ == "__main__":
    main()
