"""WIIC's chest boards: one plain board per row count (3-6), shared by every config-laid-out menu.

Drawn as the open bronze wallet (pack/assets/wallet/textures/item/bronze_wallet.png): stitched leather
body, dark header with the wallet's green trim, a darker inner pocket behind the upper slots, a black
leather strap over the hidden "Inventory" label with the clasp tab on its right end, and the player
inventory as recessed leather pockets (Vault, Broker and Courier take items from it). Every board sits
on the house full-window geometry (shift -15, width 190), so the title text shift is -176.

    python tools/gui/boards.py            # previews into %TEMP%/wiic-gui
    python tools/gui/boards.py --write    # also the PNGs, tooltip style and glyphs/wiic.yml in mysterria-resources
"""
import os
import pathlib
import sys

sys.path.insert(0, os.path.expanduser(r"~\.claude\skills\custom-gui\scripts"))
from guikit import *

WRITE = '--write' in sys.argv
RESOURCES = pathlib.Path(__file__).resolve().parents[3] / 'mysterria-resources'
PACK = str(RESOURCES / 'pack')
OUT = os.environ.get('OUT', os.path.join(os.environ.get('TEMP', '.'), 'wiic-gui'))
NS = 'wiic'
SHIFT, WIDTH = -15, 190
TEXT = '#f0cc55'

LEATHER = ramp('#1e0e11', '#3c1d23', '#512c2f', '#633a3a', '#835656', '#a47676')
TRIM = ramp('#0b2416', '#25523b', '#20653c', '#20653c', '#14a02e', '#3fc35a')
# the client draws "Inventory" at (8, top + 3) in 0x404040 with no shadow; a strap of that exact colour hides it
STRAP = ramp('#141414', '#262626', '#333333', '#404040', '#565656', '#6e6e6e')
SNAP = ramp('#3a3a3a', '#555555', '#8b8b8b', '#acacac', '#c6c6c6', '#ffffff')
TOOLTIP_BG = (30, 14, 17, 240)


def grain(g, r, rp, base, k=.03):
    """Leather grain: sparse one-step lighter flecks over a flat fill."""
    x, y, w, h = r
    g.rect(r, rp[base])
    for j in range(h):
        for i in range(w):
            if g.rng.random() < k:
                g.px(x + i, y + j, rp[base + 1])


def stitch_h(g, x0, x1, y, c):
    for x in range(x0, x1 + 1):
        if x % 3 != 2:
            g.px(x, y, c)


def stitch_v(g, x, y0, y1, c):
    for y in range(y0, y1 + 1):
        if y % 3 != 2:
            g.px(x, y, c)


def body(g, b):
    x, y, w, h = b
    grain(g, b, LEATHER, B)
    g.border(b, LEATHER[O])
    g.bevel(b, LEATHER)
    stitch_h(g, x + 3, x + w - 4, y + 3, LEATHER[H])
    stitch_h(g, x + 3, x + w - 4, y + h - 4, LEATHER[L])
    stitch_v(g, x + 3, y + 3, y + h - 4, LEATHER[L])
    stitch_v(g, x + w - 4, y + 3, y + h - 4, LEATHER[L])
    inner = (x + 6, y + 6, w - 12, h - 12)
    g.rect(grow(inner, 1), LEATHER[D])
    grain(g, inner, LEATHER, B)
    return inner


def header(g, x, w):
    grain(g, (x, 5, w, 10), LEATHER, D, k=.05)
    g.rect((x, 15, w, 1), TRIM[S])
    for i in range(w):
        if g.rng.random() < .3:
            g.px(x + i, 15, TRIM[L])
    g.rect((x, 16, w, 1), TRIM[D])


def pocket(g, x, w, top):
    r = (x + 2, 17, w - 4, top - 17)
    grain(g, r, LEATHER, D, k=.04)
    g.rect((r[0], 17, 1, r[3]), LEATHER[O])
    g.rect((r[0] + r[2] - 1, 17, 1, r[3]), LEATHER[L])
    g.rect((r[0], top, r[2], 1), LEATHER[L])
    stitch_v(g, x + 4, 18, top - 2, LEATHER[L])
    stitch_v(g, x + w - 5, 18, top - 2, LEATHER[L])


def strap(g, x, w, top):
    g.rect((x, top + 1, w, 1), STRAP[L])
    g.rect((x, top + 2, w, 10), STRAP[B])
    stitch_h(g, x + 1, x + w - 2, top + 2, STRAP[H])
    stitch_h(g, x + 1, x + w - 2, top + 11, STRAP[S])
    g.rect((x, top + 12, w, 1), LEATHER[D])


def clasp(g, top):
    x, y, w, h = 156, top, 26, 13
    g.shade((x + 1, y + h, w - 1, 1), .6)
    g.rect((x, y, w, h), LEATHER[B])
    g.rect((x + 1, y, w - 1, 1), LEATHER[O])
    g.rect((x + 1, y + h - 1, w - 1, 1), LEATHER[O])
    g.rect((x, y + 1, 1, h - 2), LEATHER[O])
    g.rect((x + 1, y + 1, w - 1, 1), LEATHER[L])
    g.rect((x + 1, y + h - 2, w - 1, 1), LEATHER[D])
    stitch_h(g, x + 3, x + w - 1, y + 2, LEATHER[H])
    stitch_h(g, x + 3, x + w - 1, y + h - 3, LEATHER[L])
    stitch_v(g, x + 2, y + 3, y + h - 4, LEATHER[L])
    cx, cy = x + 8, y + 6
    g.rect((cx - 1, cy - 2, 3, 5), SNAP[B])
    g.rect((cx - 2, cy - 1, 5, 3), SNAP[B])
    g.rect((cx - 1, cy - 2, 2, 1), SNAP[H])
    g.px(cx - 2, cy - 1, SNAP[L])
    g.px(cx - 1, cy - 1, SNAP[H])
    g.rect((cx, cy + 2, 2, 1), SNAP[S])
    g.px(cx + 2, cy + 1, SNAP[S])


def inventory(g, x, w, rows):
    top = 17 + 18 * rows
    for slot in range(9 * rows, 9 * rows + 36):
        g.well(slot_rect(slot, rows), LEATHER)
    stitch_v(g, x + 3, top + 14, top + 90, LEATHER[L])
    stitch_v(g, x + w - 4, top + 14, top + 90, LEATHER[L])
    stitch_h(g, x + 5, x + w - 6, top + 68, LEATHER[L])
    g.rect((x + 5, top + 69, w - 10, 1), LEATHER[S])
    stitch_h(g, x + 5, x + w - 6, top + 90, LEATHER[L])


def board(rows):
    g = Gui(rows=rows, seed=17 + rows)
    b = full_board(rows)
    x, _, w, _ = body(g, b)
    top = 17 + 18 * rows
    header(g, x, w)
    pocket(g, x, w, top)
    strap(g, x, w, top)
    inventory(g, x, w, rows)
    clasp(g, top)
    g.outline()
    return g


def write_glyphs(entries):
    with open(RESOURCES / 'glyphs' / 'wiic.yml', 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(''.join(entries))


def run():
    os.makedirs(OUT, exist_ok=True)
    entries = []
    for rows in (3, 4, 5, 6):
        g = board(rows)
        tex = os.path.join(PACK, 'assets', NS, 'textures', 'gui') if WRITE else OUT
        geo = g.save(os.path.join(tex, f'board_{rows}.png'))
        assert (geo['shift'], geo['w'], geo['ascent']) == (SHIFT, WIDTH, 15), f'left the shared board: {geo}'
        entries.append(g.glyph_yml(f'wiic_board_{rows}', f'{NS}:gui/board_{rows}'))
        title = 'ᴜɴᴅᴇʀɢʀᴏᴜɴᴅ ᴍᴀʀᴋᴇᴛ'
        g.preview(os.path.join(OUT, f'board_{rows}_preview.png'),
                  {0: 'back', 9: 'item:diamond', 10: 'item:oak_log', 11: 'item:golden_apple'},
                  title=title, title_color=TEXT, title_cx=8 + (font().width(title) - 1) // 2,
                  hover=9, tooltip=['Diamond', '64 coppets'],
                  tooltip_style=(TOOLTIP_BG, LEATHER[H], LEATHER[S]))
    if WRITE:
        write_glyphs(entries)
        write_tooltip_style(NS, 'ledger', TOOLTIP_BG, LEATHER[H], LEATHER[S], pack=PACK)
        pad_search_icon()
    print(''.join(entries))


def pad_search_icon():
    """emporium's 12x12 magnifying glass, centred on a 16x16 canvas so it sits like the other 16x16 icons."""
    src = Image.open(os.path.join(PACK, 'assets', 'emporium', 'textures', 'item', 'icons', 'magnifying_glass.png')).convert('RGBA')
    icon = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    icon.alpha_composite(src, ((16 - src.width) // 2, (16 - src.height) // 2))
    write_item(NS, 'gui/search', icon, pack=PACK)


if __name__ == '__main__':
    run()
