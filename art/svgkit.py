"""Shared bits for the generated diagrams in art/.

Both generators (gen-workflow-svg.py, gen-overview-svg.py) draw the same riders
in the same palette and animate the same way, so the palette, the glyphs and the
SMIL timing helpers live here.

Timing model: one loop of `Timeline.cycle` seconds; every animation runs
dur=cycle repeatCount="indefinite", with keyTimes carving out the window a given
element is visible. Windows are written in seconds on a `score`-second score,
which `rate` can stretch without rewriting a single window.
"""

FADE = 0.35                 # default fade-in/out, in score seconds

# ---------------------------------------------------------------- palette
BG = "#0e1116"
PANEL = "#161b23"
PANEL_EDGE = "#28303c"
TEXT = "#e6ebf2"
MUTED = "#8e99a8"
FAINT = "#5c6675"
A_COL = "#4fc3f7"           # rider A / "you"
B_COL = "#ffb74d"           # rider B / "your partner"
BLE = "#a78bfa"
OK = "#1db954"
BAD = "#e0352b"
YELLOW = "#ffc107"          # the data field's yellow zone (15-50 m)

SANS = "ui-sans-serif,-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif"
MONO = "ui-monospace,SFMono-Regular,Menlo,Consolas,'Liberation Mono',monospace"


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def txt(x, y, s, size=12, fill=TEXT, anchor="start", weight="400", mono=False,
        opacity=None, extra="", children=""):
    fam = MONO if mono else SANS
    op = f' opacity="{opacity}"' if opacity is not None else ""
    return (f'<text x="{x}" y="{y}" font-family="{fam}" font-size="{size}" fill="{fill}" '
            f'text-anchor="{anchor}" font-weight="{weight}"{op} {extra}>{esc(s)}{children}</text>')


# ---------------------------------------------------------------- timing
class Timeline:
    """Turns score-second windows into keyTimes on one repeating cycle."""

    def __init__(self, score, rate=1.0):
        self.score = float(score)
        self.cycle = float(score) * rate

    def kt(self, t):
        """Score seconds -> keyTimes fraction, clamped and rounded."""
        return f"{max(0.0, min(1.0, t / self.score)):.4f}"

    def fade(self, t0, t1, fade_in=FADE, fade_out=FADE):
        """<animate> that shows an element only between t0 and t1 of the loop."""
        times = ";".join(["0", self.kt(t0), self.kt(t0 + fade_in),
                          self.kt(t1), self.kt(t1 + fade_out), "1"])
        return (f'<animate attributeName="opacity" values="0;0;1;1;0;0" '
                f'keyTimes="{times}" calcMode="linear" dur="{self.cycle}s" repeatCount="indefinite"/>')

    def motion(self, path, t0, t1):
        """<animateMotion> traversing `path` between t0 and t1, parked at the start otherwise."""
        times = ";".join(["0", self.kt(t0), self.kt(t1), "1"])
        return (f'<animateMotion path="{path}" keyPoints="0;0;1;1" keyTimes="{times}" '
                f'calcMode="linear" dur="{self.cycle}s" repeatCount="indefinite"/>')

    def draw_on(self, length, t0, t1):
        """Stroke-reveal via dashoffset (element must carry stroke-dasharray=length)."""
        times = ";".join(["0", self.kt(t0), self.kt(t1), "1"])
        return (f'<animate attributeName="stroke-dashoffset" values="{length};{length};0;0" '
                f'keyTimes="{times}" calcMode="linear" dur="{self.cycle}s" repeatCount="indefinite"/>')


# ---------------------------------------------------------------- glyphs
def bt_rune(x, y, scale=1.0, color=BLE, width=2.4, opacity=1.0):
    """The Bluetooth rune, centred on (x, y); 14 x 26 units before scaling."""
    return (f'<g transform="translate({x},{y}) scale({scale})" stroke="{color}" fill="none" '
            f'stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round" opacity="{opacity}">'
            f'<path d="M-7,-6.5 L7,6.5 L0,13 L0,-13 L7,-6.5 L-7,6.5"/></g>')


def no_internet(x, y, scale=1.0, color=FAINT, width=2.0):
    """A globe with a slash through it — the transport never leaves the two devices."""
    return (f'<g transform="translate({x},{y}) scale({scale})" stroke="{color}" fill="none" '
            f'stroke-width="{width}" stroke-linecap="round">'
            f'<circle cx="0" cy="0" r="12"/>'
            f'<ellipse cx="0" cy="0" rx="5.5" ry="12"/>'
            f'<line x1="-12" y1="0" x2="12" y2="0"/>'
            f'<line x1="-13" y1="13" x2="13" y2="-13" stroke="{BAD}" stroke-width="{width+1.2}"/></g>')


def satellite(x, y, color=MUTED, fill="#1b2029", width=2.2):
    """Body, two solar panels and an antenna, centred on (x, y)."""
    return (f'<g transform="translate({x},{y})" stroke="{color}" fill="none" stroke-width="{width}" '
            f'stroke-linecap="round">'
            f'<rect x="-11" y="-12" width="22" height="24" rx="4" fill="{fill}"/>'
            f'<rect x="-34" y="-8" width="20" height="16" rx="2" fill="{fill}"/>'
            f'<rect x="14" y="-8" width="20" height="16" rx="2" fill="{fill}"/>'
            f'<line x1="-30" y1="-8" x2="-30" y2="8"/><line x1="-22" y1="-8" x2="-22" y2="8"/>'
            f'<line x1="22" y1="-8" x2="22" y2="8"/><line x1="30" y1="-8" x2="30" y2="8"/>'
            f'<line x1="0" y1="-12" x2="0" y2="-22"/><circle cx="0" cy="-25" r="3.5"/>'
            f'</g>')


def bike(x, y, color, ghost=False, spin=True):
    """Side view of a rider, facing right; (x, y) is the mid-point of the wheel hubs."""
    sw = 2.4 if ghost else 3
    dash = ' stroke-dasharray="6 5"' if ghost else ""
    op = 0.5 if ghost else 1.0
    g = [f'<g transform="translate({x},{y})" stroke="{color}" fill="none" stroke-width="{sw}" '
         f'stroke-linecap="round" stroke-linejoin="round" opacity="{op}">']
    for cx in (-24, 24):
        g.append(f'<circle cx="{cx}" cy="0" r="14"{dash}/>')
        if spin and not ghost:
            g.append(
                f'<g opacity="0.55"><line x1="{cx-10}" y1="0" x2="{cx+10}" y2="0"/>'
                f'<line x1="{cx}" y1="-10" x2="{cx}" y2="10"/>'
                f'<animateTransform attributeName="transform" type="rotate" '
                f'values="0 {cx} 0;360 {cx} 0" dur="0.9s" repeatCount="indefinite"/></g>')
    # frame: rear hub / bottom bracket / saddle / head tube / front hub
    g.append(f'<path d="M-24,0 L-4,2 M-4,2 L-16,-22 M-16,-22 L14,-20 M-4,2 L14,-20 '
             f'M-24,0 L-16,-22 M14,-20 L24,0"{dash}/>')
    g.append(f'<path d="M-21,-24 L-11,-24 M14,-20 L23,-25"{dash}/>')   # saddle, bar
    # rider
    g.append(f'<path d="M-9,-26 L7,-41 M7,-41 L23,-25 M-9,-26 L-4,2"{dash}/>')
    g.append(f'<circle cx="13" cy="-47" r="6.5"{dash}/>')
    g.append('</g>')
    return "".join(g)
