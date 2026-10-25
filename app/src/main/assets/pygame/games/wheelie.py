#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WHEELIE SIMULATOR 2D
====================
Jednoplikowy symulator wheelie (jazdy na tylnym kole) w 2D – Python + pygame.

Pojazdy (wybór w GARAŻU):
  * Ścigacz 1000        – spalinowy, 6 biegów, sprzęgło, ~110 kW
  * Sur-Ron Light Bee X – elektryczny cross, moment od zera, bez sprzęgła
  * Pit bike 125        – mały spalinowy, 4 biegi, wheelie tylko ze sprzęgła
  * Kukirin G2 Pro, G2 Max, G3 Pro (2 silniki), G4, G4 Max (2 silniki)
                        – hulajnogi elektryczne, jedziesz na stojąco
  (parametry pojazdów są orientacyjne, dobrane pod rozgrywkę)

Fizyka (uproszczona, ale uczciwa):
  * pojazd obraca się wokół osi tylnego koła – to odwrócone wahadło,
  * silnik spalinowy z krzywą momentu, sprzęgłem z poślizgiem, biegami i ogranicznikiem,
  * silnik elektryczny: stały moment do prędkości bazowej, potem stała moc, tryby mocy,
  * przyczepność tylnej opony, opór powietrza, podmuchy wiatru, nierówności drogi,
  * balans i "szarpnięcie" ciałem kierowcy, przewrotka do tyłu i gleba po uderzeniu przodem.

Wheelie jest celowo TRUDNE.

Wymagania:  pip install pygame
Uruchom:    python wheelie_simulator.py

TELEFON / PC
  Zaraz po uruchomieniu wybierasz, na czym grasz:
  * TELEFON – sterowanie dotykiem: suwak gazu i przyciski na ekranie (działa kilka palców naraz).
              Plik uruchom w aplikacji z Pythonem i pygame (np. Pydroid 3 na Androidzie),
              telefon trzymaj poziomo.
  * PC      – klawiatura (opcjonalnie gaz myszką).
  Tryb zmienisz później w garażu. Flagi --telefon / --pc pomijają ekran wyboru.

STEROWANIE
  W / ↑        gaz (manetka sama wraca – dawkuj krótkimi wciśnięciami)
  S / ↓        tylny hamulec (opuszcza przód – ratunek przed przewrotką)
  A / ←        balans ciałem do tyłu (szybkie odchylenie szarpie przód w górę)
  D / →        balans ciałem do przodu
  SPACJA       sprzęgło (pojazdy spalinowe): trzymaj, wkręć obroty, puść = clutch-up
  E / Q        bieg w górę / w dół (spalinowe) albo tryb mocy (elektryczne)
  G / TAB      garaż – wybór pojazdu
  M            gaz myszką (pionowa pozycja kursora = manetka, precyzyjniej)
  T            zmiana trybu (ekran wyboru TELEFON / PC)
  1 2 3 4      poziom trudności: Łatwy / Normalny / Trudny / Hardkor
  R / ENTER    restart      P  pauza      H  pomoc      ESC  wyjście
"""

import asyncio
import json
import math
import os
import random
import sys
from bisect import bisect_left

try:
    import pygame
except ImportError:
    print("Ten symulator wymaga biblioteki pygame:\n    pip install pygame")
    sys.exit(1)

try:
    import szept                 # dostępny tylko w aplikacji Szept (tabela wyników)
except ImportError:
    szept = None

# ---------------------------------------------------------------------------
# Ekran i stałe fizyczne
# ---------------------------------------------------------------------------
W, H = 1280, 720
FPS = 60
PHYS_DT = 1.0 / 300.0          # krok fizyki (300 Hz)
BG_P = 3000                    # okres (px) powtarzalnego tła – tło rysowane raz, potem tylko przesuwane
CKEY = (255, 0, 255)           # kolor przezroczysty w gotowych obrazkach

G = 9.81
RHO = 1.2
RAD2RPM = 60.0 / (2.0 * math.pi)
RIDER_MASS = 75.0

# ts  – tempo obrotu pojazdu (mniej = wszystko dzieje się wolniej, łatwiej zdążyć zareagować)
# wc  – kontrola wheelie: gdy przód leci za wysoko (powyżej równowagi + wc_margin stopni),
#       elektronika ucina gaz (jak w prawdziwych motocyklach); im więcej gazu, tym niżej
#       trzyma przód (wc_drop stopni przy pełnym gazie) – dzięki temu da się przyspieszać.
#       Tylny hamulec dokłada tylko awaryjnie, gdy przód miałby przelecieć za punkt równowagi.
# auto_shift – automatyczna zmiana biegu przy czerwonym polu obrotomierza
# pull – siła szarpnięcia ciałem (pomaga podnieść przód słabszym pojazdom)
DIFFS = {
    1: dict(name="ŁATWY", bumps=0.2, wind=0.3, gusts=0.0, show_bp=True, slam=99.0, damp=70.0,
            lag=0.04, thr_up=1.6, thr_dn=10.0, ts=0.5, wc=True, wc_margin=-2.0, wc_drop=8.0, wc_brake=1.0, pull=2.2, auto_shift=True),
    2: dict(name="NORMALNY", bumps=0.45, wind=0.5, gusts=0.4, show_bp=True, slam=6.5, damp=45.0,
            lag=0.05, thr_up=1.8, thr_dn=10.0, ts=0.7, wc=True, wc_margin=7.0, wc_drop=16.0, wc_brake=0.8, pull=1.4, auto_shift=True),
    3: dict(name="TRUDNY", bumps=1.0, wind=1.0, gusts=1.0, show_bp=True, slam=4.2, damp=8.0,
            lag=0.08, thr_up=2.4, thr_dn=8.0, ts=1.0, wc=False, wc_margin=0.0, wc_drop=0.0, wc_brake=0.0, pull=1.0, auto_shift=False),
    4: dict(name="HARDKOR", bumps=1.6, wind=1.7, gusts=1.7, show_bp=False, slam=3.4, damp=0.0,
            lag=0.12, thr_up=3.0, thr_dn=7.0, ts=1.0, wc=False, wc_margin=0.0, wc_drop=0.0, wc_brake=0.0, pull=1.0,
            auto_shift=False),
}
SAVE_VERSION = 2                     # w wersji 1 były tylko 3 poziomy (Normalny/Trudny/Hardkor)

SAVE_FILE = os.path.join(os.path.expanduser("~"), ".wheelie_simulator.json")

# ---------------------------------------------------------------------------
# Kolory
# ---------------------------------------------------------------------------
C_DARK = (38, 38, 44)
C_DARK2 = (60, 60, 68)
C_CHROME = (175, 178, 188)
C_TIRE = (22, 22, 24)
C_SUIT = (30, 32, 40)
C_SUIT_FAR = (16, 17, 22)
C_HELMET = (238, 238, 240)
C_VISOR = (35, 45, 70)
C_WHITE = (245, 245, 245)
C_TEXT = (240, 240, 240)
C_SHADOW = (0, 0, 0)
C_GOOD = (70, 210, 90)
C_WARN = (250, 190, 40)
C_BAD = (235, 60, 50)
C_GOLD = (235, 175, 25)


# ---------------------------------------------------------------------------
# Narzędzia
# ---------------------------------------------------------------------------
def clamp(v, lo, hi):
    return lo if v < lo else hi if v > hi else v


def interp(table, x):
    if x <= table[0][0]:
        return table[0][1]
    for (x0, y0), (x1, y1) in zip(table, table[1:]):
        if x <= x1:
            return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    return table[-1][1]


def rot(p, a):
    c, s = math.cos(a), math.sin(a)
    return (p[0] * c - p[1] * s, p[0] * s + p[1] * c)


def hrand(k, salt=0):
    """Deterministyczny pseudolosowy float [0,1) z liczby całkowitej."""
    x = (k * 374761393 + salt * 668265263 + 1442695041) & 0xFFFFFFFF
    x = ((x ^ (x >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((x ^ (x >> 16)) & 0xFFFFFFFF) / 4294967296.0


def ik2(p0, p2, l1, l2, bend):
    """Dwuczłonowa kinematyka odwrotna – zwraca położenie stawu (łokieć/kolano)."""
    dx, dy = p2[0] - p0[0], p2[1] - p0[1]
    d = clamp(math.hypot(dx, dy), 1e-4, l1 + l2 - 1e-4)
    a = math.atan2(dy, dx)
    b = math.acos(clamp((l1 * l1 + d * d - l2 * l2) / (2 * l1 * d), -1.0, 1.0))
    ang = a + bend * b
    return (p0[0] + l1 * math.cos(ang), p0[1] + l1 * math.sin(ang))


def convex_hull(points):
    pts = sorted(set(points))
    if len(pts) < 3:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def scaled(pts, s):
    return [(p[0] * s, p[1] * s) for p in pts]


# ---------------------------------------------------------------------------
# Kształty pojazdów (oś tylnego koła = (0,0), x do przodu, y w górę) [m]
# ---------------------------------------------------------------------------
# --- ścigacz ---
SP_SWINGARM = [(0.0, 0.035), (0.60, 0.21), (0.62, 0.13), (0.0, -0.035)]
SP_EXHAUST = [(0.60, 0.10), (0.25, 0.14), (-0.06, 0.30), (-0.20, 0.30), (-0.22, 0.22), (-0.04, 0.20), (0.22, 0.06)]
SP_ENGINE = [(0.50, 0.12), (0.62, 0.04), (0.92, 0.04), (1.02, 0.20), (1.00, 0.42), (0.62, 0.46), (0.48, 0.32)]
SP_FAIR_LOW = [(0.62, 0.46), (0.98, 0.42), (1.12, 0.46), (1.08, 0.26), (0.98, 0.10), (0.84, 0.08), (0.96, 0.30),
               (0.70, 0.40)]
SP_TAIL = [(0.14, 0.60), (0.52, 0.58), (0.56, 0.50), (0.30, 0.44), (-0.05, 0.40), (-0.40, 0.42),
           (-0.43, 0.47), (-0.10, 0.56)]
SP_SEAT = [(0.14, 0.60), (0.52, 0.58), (0.50, 0.65), (0.22, 0.67), (0.06, 0.63)]
SP_TANK = [(0.50, 0.58), (0.60, 0.72), (0.86, 0.80), (1.00, 0.74), (1.00, 0.54), (0.62, 0.46)]
SP_FAIRING = [(0.98, 0.42), (1.12, 0.46), (1.28, 0.62), (1.34, 0.76), (1.22, 0.92), (1.08, 0.96), (1.00, 0.80),
              (1.00, 0.54)]
SP_SCREEN = [(1.08, 0.96), (1.22, 0.92), (1.12, 1.06), (1.00, 1.06)]
SP_FENDER = [(1.22, 0.32), (1.40, 0.38), (1.62, 0.30), (1.64, 0.33), (1.42, 0.43), (1.20, 0.36)]
SP_FORK_TOP = (1.07, 0.86)
SP_BODY = [SP_EXHAUST, SP_ENGINE, SP_TAIL, SP_SEAT, SP_TANK, SP_FAIRING, SP_SCREEN]

# --- cross / pit bike (wymiary Sur-Rona, pit bike jest przeskalowany) ---
DT_SWINGARM = [(0.0, 0.03), (0.56, 0.20), (0.58, 0.14), (0.0, -0.03)]
DT_BATTERY = [(0.58, 0.16), (0.92, 0.22), (0.98, 0.60), (0.64, 0.50)]
DT_ENGINE = [(0.52, 0.06), (0.84, 0.06), (0.90, 0.30), (0.80, 0.50), (0.66, 0.50), (0.56, 0.32)]
DT_PIPE = [(0.84, 0.42), (0.97, 0.30), (0.94, 0.15), (0.70, 0.11), (0.45, 0.28), (0.22, 0.38)]
DT_SILENCER = [(0.26, 0.40), (-0.12, 0.52), (-0.15, 0.44), (0.22, 0.31)]
DT_SEAT = [(0.04, 0.58), (0.66, 0.60), (0.72, 0.66), (0.62, 0.70), (0.04, 0.64)]
DT_REAR = [(0.10, 0.50), (0.56, 0.50), (0.60, 0.58), (0.04, 0.58), (-0.42, 0.64), (-0.44, 0.60), (-0.10, 0.50)]
DT_SHROUD = [(0.62, 0.54), (0.98, 0.62), (1.04, 0.76), (0.84, 0.72), (0.66, 0.62)]
DT_PLATE = [(1.00, 0.58), (1.10, 0.90), (1.18, 0.88), (1.12, 0.58)]
DT_FENDER = [(1.10, 0.44), (1.30, 0.48), (1.54, 0.42), (1.52, 0.46), (1.30, 0.53), (1.08, 0.48)]
DT_FRAME = [((1.02, 0.74), (0.62, 0.50)), ((0.62, 0.50), (0.58, 0.14)), ((0.62, 0.50), (0.06, 0.54)),
            ((0.58, 0.14), (0.90, 0.22)), ((0.90, 0.22), (1.02, 0.74))]
DT_FORK_TOP = (1.03, 0.82)
DT_FORK_MID = (1.14, 0.44)
DT_GRIP = (0.98, 0.92)
DT_BODY = [DT_SEAT, DT_REAR, DT_SHROUD, DT_PLATE, DT_BATTERY, [DT_FORK_TOP, DT_GRIP]]

# --- hulajnoga ---
SC_DECK = [(0.06, -0.01), (0.80, -0.01), (0.86, 0.03), (0.82, 0.08), (0.06, 0.08), (0.02, 0.04)]
SC_TAPE = [(0.10, 0.065), (0.78, 0.065), (0.78, 0.08), (0.10, 0.08)]
SC_REAR_FENDER = [(-0.19, 0.08), (-0.12, 0.18), (0.02, 0.20), (0.12, 0.12), (0.08, 0.10), (0.01, 0.16),
                  (-0.11, 0.15), (-0.16, 0.07)]
SC_NECK = [(0.78, 0.00), (0.90, 0.16), (0.96, 0.26), (0.90, 0.30), (0.82, 0.18), (0.76, 0.06)]
SC_FRONT_FENDER = [(0.86, 0.15), (0.98, 0.20), (1.12, 0.14), (1.10, 0.17), (0.98, 0.23), (0.85, 0.18)]
SC_DISPLAY = [(0.79, 1.00), (0.90, 1.02), (0.90, 1.07), (0.79, 1.05)]
SC_STEM_BOT = (0.93, 0.26)
SC_STEM_TOP = (0.85, 1.02)
SC_GRIP = (0.84, 1.04)
SC_BODY = [SC_DECK, SC_REAR_FENDER, SC_NECK, SC_DISPLAY, [SC_STEM_TOP]]


# ---------------------------------------------------------------------------
# Pojazdy
# ---------------------------------------------------------------------------
class Vehicle:
    """Parametry pojazdu. Wszystkie jednostki SI, kąty w stopniach na wejściu."""

    def __init__(self, **kw):
        kw.setdefault("front_t", 0.0)            # przedni silnik w piaście (hulajnogi 2×)
        kw.setdefault("front_p", 0.0)
        kw.setdefault("knobby", False)
        self.__dict__.update(kw)
        self.electric = self.drive == "ev"
        if self.electric:
            self.modes = tuple((m[0], m[1], m[2] if len(m) > 2 else False) for m in self.modes)
        self.brake_max = self.brake_g * self.mass * G
        self.max_angle = math.radians(self.max_angle_deg)
        body = {"sport": SP_BODY, "dirt": DT_BODY, "scooter": SC_BODY}[self.style]
        pts = [(p[0] * self.scale, p[1] * self.scale) for poly in body for p in poly]
        hull = convex_hull(pts)
        self.n_body = len(hull)
        for i in range(6):
            a = i * math.pi / 3
            hull.append((self.wheel_r * math.cos(a), self.wheel_r * math.sin(a)))
            hull.append((self.wheelbase + self.wheel_r * math.cos(a), self.wheel_r * math.sin(a)))
        self.hull = hull

    def ratio(self, gear):
        return self.ratio_ev if self.electric else self.final * self.gears[gear]

    @property
    def n_gears(self):
        return 1 if self.electric else len(self.gears) - 1

    @property
    def peak_kw(self):
        if self.electric:
            return (self.p_max + self.front_p) / 1000.0
        return max(t * r / RAD2RPM for r, t in self.curve if r <= self.limit) / 1000.0

    @property
    def vmax_kmh(self):
        if self.electric:
            w = self.max_rpm / RAD2RPM
        else:
            w = self.limit / RAD2RPM
        return w / self.ratio(self.n_gears) * self.wheel_r * 3.6


def _tail_angle(tail, r):
    """Kąt, przy którym zadupek dotyka płaskiego asfaltu."""
    a = 0.5
    while a < 2.0 and rot(tail, a)[1] >= -r:
        a += 0.001
    return math.degrees(a)


VEHICLES = [
    Vehicle(
        vid="moto", name="Ścigacz 1000", style="sport", scale=1.0, rating=5,
        desc=["Litrowy ścigacz. Na 1. biegu wystarczy odkręcić gaz,",
              "wyżej potrzebne sprzęgło. Mały zapas kąta przed przewrotką."],
        colors=dict(main=(214, 38, 42), hi=(245, 90, 80), accent=(214, 38, 42), rim=C_GOLD),
        mass=245.0, wheel_r=0.31, wheelbase=1.42, com_a=0.62, com_h=0.36, gyr=0.33,
        lean_shift=0.08, lean_drop=0.02, pull=30.0, tail=(-0.40, 0.42),
        max_angle_deg=_tail_angle((-0.40, 0.42), 0.31),
        brake_g=0.55, cda=0.42, cda_up=0.30, crr=0.018, mu=1.15, susp=0.14, slam_mult=1.0, start_kmh=22.0,
        loop_msg="LOOP OUT – przewrotka do tyłu!",
        drive="ice", gears=(0.0, 2.60, 1.95, 1.60, 1.38, 1.24, 1.13), final=1.65 * 2.75,
        i_engine=0.025, clutch_cap=190.0, idle=1300.0, stall_rpm=450.0, redline=11000.0,
        limit=11800.0, resume=11300.0, gauge=13000.0, fric0=4.0, fric1=0.0012,
        curve=((0, 0), (1000, 42), (2500, 58), (4000, 70), (6000, 86), (8000, 104), (9500, 110),
               (10500, 104), (11500, 92), (13000, 70)),
        hip=(0.30, 0.72), hip_shift=0.09, hip_drop=0.0, torso_deg=40.0, torso_lean=14.0, torso_len=0.56,
        upright=0.55, grip=(1.00, 0.88), peg=(0.38, 0.22), peg2=None,
        thigh=0.45, shin=0.47, upper=0.31, fore=0.32),
    Vehicle(
        vid="surron", name="Sur-Ron Light Bee X", style="dirt", scale=1.0, rating=3,
        desc=["Elektryczny cross. Pełny moment od zera i brak sprzęgła –",
              "przód skacze od samego gazu. Duży zapas kąta, ale gaz jest nerwowy."],
        colors=dict(main=(236, 238, 242), hi=(40, 120, 220), accent=(40, 120, 220), frame=(150, 155, 165),
                    battery=(34, 36, 40), rim=(30, 30, 34)),
        mass=57.0 + RIDER_MASS, wheel_r=0.33, wheelbase=1.26, com_a=0.52, com_h=0.50, gyr=0.30,
        lean_shift=0.12, lean_drop=0.03, pull=45.0, tail=(-0.44, 0.60), max_angle_deg=80.0,
        brake_g=0.60, cda=0.55, cda_up=0.25, crr=0.022, mu=1.0, susp=0.08, slam_mult=1.2, start_kmh=15.0,
        loop_msg="LOOP OUT – Sur-Ron poleciał na plecy!",
        drive="ev", ratio_ev=8.9, i_engine=0.003, t_max=34.0, p_max=6000.0, max_rpm=5400.0, regen=3.0,
        modes=(("ECO", 0.55), ("SPORT", 1.0)), gauge=6000.0,
        hip=(0.36, 0.72), hip_shift=0.11, hip_drop=0.02, torso_deg=24.0, torso_lean=16.0, torso_len=0.56,
        upright=0.6, grip=DT_GRIP, peg=(0.50, 0.08), peg2=None,
        thigh=0.45, shin=0.46, upper=0.31, fore=0.32),
    Vehicle(
        vid="pit", name="Pit bike 125", style="dirt", scale=0.84, rating=3,
        desc=["Mały spalinowy pit bike, 4 biegi. Za słaby na wheelie z samego",
              "gazu – wkręć obroty na sprzęgle (SPACJA) i puść."],
        colors=dict(main=(245, 120, 20), hi=(30, 30, 34), accent=(245, 120, 20), frame=(40, 40, 46),
                    rim=(30, 30, 34)),
        mass=70.0 + RIDER_MASS, wheel_r=0.33 * 0.84, wheelbase=1.26 * 0.84, com_a=0.46, com_h=0.44, gyr=0.28,
        lean_shift=0.11, lean_drop=0.03, pull=35.0, tail=(-0.37, 0.50), max_angle_deg=80.0,
        brake_g=0.50, cda=0.50, cda_up=0.25, crr=0.022, mu=1.0, susp=0.12, slam_mult=1.1, start_kmh=15.0,
        loop_msg="LOOP OUT – przewrotka do tyłu!",
        drive="ice", gears=(0.0, 2.83, 1.78, 1.38, 1.12), final=3.35 * 3.0,
        i_engine=0.004, clutch_cap=16.0, idle=1500.0, stall_rpm=500.0, redline=9000.0,
        limit=9500.0, resume=9100.0, gauge=11000.0, fric0=0.4, fric1=0.00012,
        curve=((0, 0), (1000, 5.0), (3000, 7.0), (5000, 8.2), (6500, 8.6), (8000, 8.0), (9500, 6.5),
               (11000, 4.0)),
        hip=(0.30, 0.62), hip_shift=0.10, hip_drop=0.02, torso_deg=30.0, torso_lean=16.0, torso_len=0.56,
        upright=0.6, grip=(DT_GRIP[0] * 0.84, DT_GRIP[1] * 0.84), peg=(0.42, 0.07), peg2=None,
        thigh=0.45, shin=0.46, upper=0.31, fore=0.32),
]


def kukirin(vid, name, rating, desc, accent, s, mass, t_max, p_max, vmax_kmh, modes,
            front_t=0.0, front_p=0.0, knobby=False, susp=0.30, slam_mult=0.9, max_angle=52.0):
    """Hulajnoga Kukirin – geometria G4 przeskalowana o s (koła, podest, kierownica)."""
    r = 0.14 * s
    return Vehicle(
        vid=vid, name=name, style="scooter", scale=s, rating=rating, desc=desc,
        colors=dict(main=(28, 28, 32), hi=accent, accent=accent, stem=(70, 72, 80), rim=(50, 50, 56)),
        mass=mass + RIDER_MASS, wheel_r=r, wheelbase=0.98 * s, com_a=0.42 * s, com_h=0.08 * s + 0.78, gyr=0.30,
        lean_shift=0.20, lean_drop=0.05, pull=30.0, tail=(-0.19 * s, 0.08 * s), max_angle_deg=max_angle,
        brake_g=0.50, cda=0.55, cda_up=0.15, crr=0.025, mu=0.9, susp=susp, slam_mult=slam_mult, start_kmh=12.0,
        loop_msg="Spadłeś z hulajnogi do tyłu!",
        drive="ev", ratio_ev=1.0, i_engine=0.02 * s * s, t_max=t_max, p_max=p_max,
        max_rpm=vmax_kmh / 3.6 / r * RAD2RPM, regen=0.07 * t_max, modes=modes, gauge=p_max + front_p,
        front_t=front_t, front_p=front_p, knobby=knobby,
        hip=(0.38 * s, 0.08 * s + 0.85), hip_shift=0.20, hip_drop=0.06, torso_deg=12.0, torso_lean=12.0,
        torso_len=0.58, upright=0.9, grip=(SC_GRIP[0] * s, SC_GRIP[1] * s),
        peg=(0.56 * s, 0.09 * s), peg2=(0.26 * s, 0.09 * s),
        thigh=0.45, shin=0.47, upper=0.31, fore=0.32)


MODES_3 = (("1", 0.45), ("2", 0.7), ("3", 1.0))
MODES_DUAL = (("ECO", 0.5, False), ("1×", 1.0, False), ("2×", 1.0, True))

VEHICLES += [
    kukirin("kukirin_g2pro", "Kukirin G2 Pro", 5,
            ["Lekka hulajnoga ~600 W. Za słaba, żeby podnieść przód gazem –",
             "wszystko robisz ciałem (odchylenie) i tylko przy małej prędkości."],
            (60, 140, 230), 0.92, 21.0, 32.0, 900.0, 45.0, MODES_3),
    kukirin("kukirin_g2max", "Kukirin G2 Max", 4,
            ["Mocniejsza G2 (~1000 W). Przód idzie w górę z odchyleniem",
             "i gazem, ale małe koła łapią każdą dziurę."],
            (220, 50, 50), 0.92, 24.0, 42.0, 1500.0, 55.0, MODES_3, susp=0.34),
    kukirin("kukirin_g3pro", "Kukirin G3 Pro", 4,
            ["Terenowa, dwa silniki 2×1200 W. Na 2× przód podskakuje, ale w powietrzu",
             "ciągnie tylko tylny silnik – do wheelie zwykle lepszy tryb 1×."],
            (90, 200, 60), 0.97, 35.0, 45.0, 1600.0, 65.0, MODES_DUAL,
            front_t=45.0, front_p=1600.0, knobby=True, susp=0.22),
    kukirin("kukirin", "Kukirin G4", 4,
            ["Hulajnoga elektryczna ~2000 W – jedziesz na stojąco. Środek masy",
             "wysoko, równowaga nisko, a przód podnosisz głównie ciałem (odchylenie)."],
            (240, 120, 30), 1.0, 34.0, 70.0, 2400.0, 72.0, MODES_3),
    kukirin("kukirin_g4max", "Kukirin G4 Max", 4,
            ["Najmocniejsza: 2×1600 W, do ~80 km/h. Na 2× wyrywa przód,",
             "ale jest ciężka i twardo ląduje. Tryb 1× daje spokojniejszy gaz."],
            (240, 200, 40), 1.0, 41.0, 70.0, 2200.0, 80.0, MODES_DUAL,
            front_t=70.0, front_p=2200.0, slam_mult=0.85),
]
VEH_BY_ID = {v.vid: v for v in VEHICLES}


# ---------------------------------------------------------------------------
# Droga
# ---------------------------------------------------------------------------
class Road:
    HA, LA = 1.4, 55.0
    HB, LB = 0.45, 19.0

    def __init__(self, seed, bump_scale):
        self.rng = random.Random(seed)
        self.scale = bump_scale
        self.p1 = self.rng.uniform(0, 2 * math.pi)
        self.p2 = self.rng.uniform(0, 2 * math.pi)
        self.cx = []
        self.bumps = []          # (środek, amplituda, sigma, rodzaj)
        self.gen_to = 30.0       # pierwsze metry bez nierówności
        self._grid = {}
        self.ensure(0.0)

    def ensure(self, x):
        while self.gen_to < x + 400.0:
            self.gen_to += self.rng.uniform(5.0, 24.0)
            r = self.rng.random()
            if r < 0.10:                                   # próg zwalniający
                amp, sig, kind = 0.085, 0.75, 1
            elif r < 0.33:                                 # dziura
                amp, sig, kind = -self.rng.uniform(0.02, 0.05), self.rng.uniform(0.15, 0.35), 2
            else:                                          # garb / łata asfaltu
                amp, sig, kind = self.rng.uniform(0.012, 0.055), self.rng.uniform(0.10, 0.40), 0
            self.cx.append(self.gen_to)
            self.bumps.append((self.gen_to, amp * self.scale, sig, kind))

    def sample(self, x):
        """Zwraca (wysokość, nachylenie dy/dx, krzywizna d2y/dx2)."""
        a1 = x / self.LA + self.p1
        a2 = x / self.LB + self.p2
        y = self.HA * math.sin(a1) + self.HB * math.sin(a2)
        dy = self.HA / self.LA * math.cos(a1) + self.HB / self.LB * math.cos(a2)
        d2 = -self.HA / self.LA ** 2 * math.sin(a1) - self.HB / self.LB ** 2 * math.sin(a2)
        i = bisect_left(self.cx, x - 3.2)
        n = len(self.cx)
        while i < n and self.cx[i] < x + 3.2:
            c, amp, sig, _ = self.bumps[i]
            u = (x - c) / sig
            if -4.0 < u < 4.0:
                e = amp * math.exp(-0.5 * u * u)
                y += e
                dy += -e * u / sig
                d2 += e * (u * u - 1.0) / (sig * sig)
            i += 1
        return y, dy, d2

    def height(self, x):
        return self.sample(x)[0]

    GRID = 0.2

    def grid_height(self, i):
        """Wysokość w punkcie i*GRID – liczona raz i zapamiętana (do rysowania)."""
        h = self._grid.get(i)
        if h is None:
            h = self._grid[i] = self.sample(i * self.GRID)[0]
            if len(self._grid) > 6000:
                self._grid = {k: v for k, v in self._grid.items() if abs(k - i) < 1500}
        return h


# ---------------------------------------------------------------------------
# Wiatr
# ---------------------------------------------------------------------------
class Wind:
    """Prędkość wiatru wzdłuż drogi: + w plecy, - w twarz."""

    def __init__(self, strength, gusts, rng):
        self.k = strength
        self.gk = gusts
        self.rng = rng
        self.mean = -1.5 * strength
        self.base = self.mean
        self.gust = 0.0
        self.g_peak = 0.0
        self.g_dur = 1.0
        self.g_t = -1.0
        self.next_gust = rng.uniform(6.0, 12.0)
        self.announce = False

    def update(self, dt):
        r = self.rng
        self.base += (self.mean - self.base) * dt / 2.5 + 1.8 * self.k * math.sqrt(dt) * r.gauss(0, 1)
        self.announce = False
        if self.g_t >= 0.0:
            self.g_t += dt
            if self.g_t >= self.g_dur:
                self.g_t = -1.0
                self.gust = 0.0
            else:
                self.gust = self.g_peak * math.sin(math.pi * self.g_t / self.g_dur) ** 2
        elif self.gk > 0:
            self.next_gust -= dt
            if self.next_gust <= 0:
                sign = -1.0 if r.random() < 0.75 else 1.0
                self.g_peak = sign * r.uniform(5.0, 12.0) * self.gk
                self.g_dur = r.uniform(0.7, 2.0)
                self.g_t = 0.0
                self.next_gust = r.uniform(5.0, 13.0) / max(0.5, self.gk)
                self.announce = True

    @property
    def speed(self):
        return self.base + self.gust


# ---------------------------------------------------------------------------
# Pojazd w ruchu
# ---------------------------------------------------------------------------
class Bike:
    def __init__(self, road, diff, veh):
        self.road = road
        self.diff = diff
        self.V = veh
        self.reset()

    def reset(self):
        V = self.V
        self.x = 2.0
        self.v = V.start_kmh / 3.6
        self.gear = 1
        self.mode = len(V.modes) - 1 if V.electric else 0
        self.we = self.v / V.wheel_r * self.ratio()   # prędkość kątowa wału silnika [rad/s]
        self.psi = self.base_pitch(self.x)            # pochylenie pojazdu względem poziomu
        self.base_prev = self.psi
        self.omega = 0.0
        self.throttle = 0.0
        self.brake = 0.0
        self.clutch = 1.0                             # 1 = sprzęgło puszczone (załączone)
        self.lean = 0.0
        self.lean_rate = 0.0
        self.tq = 0.0
        self.tq_f = 0.0
        self.ax = 0.0
        self.power = 0.0
        self.limiter = False
        self.regen = False
        self.wc_active = False
        self.wc_cut_t = 0.0
        self.brake_eff = 0.0
        self.stalled = False
        self.restart_t = 0.0
        self.shift_t = 0.0
        self.shift_dir = 0
        self.wheelspin = False
        self.clutch_slip = False
        self.in_wheelie = False
        self.w_dist = 0.0
        self.w_time = 0.0
        self.w_max = 0.0
        self.rear_rot = 0.0
        self.front_rot = 0.0
        self.front_spin = self.v / V.wheel_r
        self.front_down = True

    # -- geometria ---------------------------------------------------------
    def ratio(self, gear=None):
        return self.V.ratio(self.gear if gear is None else gear)

    def com(self):
        V = self.V
        return V.com_a + V.lean_shift * self.lean, V.com_h - V.lean_drop * abs(self.lean)

    def base_pitch(self, x):
        """Pochylenie, przy którym przednie koło leży na drodze."""
        wb = self.V.wheelbase
        return math.atan2(self.road.height(x + wb) - self.road.height(x), wb)

    def balance_angle(self):
        a, h = self.com()
        return math.pi / 2 - math.atan2(h, a)

    @property
    def rpm(self):
        return self.we * RAD2RPM

    @property
    def axle(self):
        return self.x, self.road.height(self.x) + self.V.wheel_r

    # -- sterowanie ---------------------------------------------------------
    def controls(self, dt, thr_key, brk_key, lean_dir, clutch_key, mouse_thr):
        d = self.diff
        if mouse_thr is not None:
            diff = mouse_thr - self.throttle
            self.throttle += clamp(diff, -d['thr_dn'] * dt, d['thr_up'] * 2.0 * dt)
        elif thr_key:
            self.throttle = min(1.0, self.throttle + d['thr_up'] * dt)
        else:
            self.throttle = max(0.0, self.throttle - d['thr_dn'] * dt)
        if brk_key:
            self.brake = min(1.0, self.brake + 6.0 * dt)
        else:
            self.brake = max(0.0, self.brake - 8.0 * dt)
        old = self.lean
        self.lean += clamp(lean_dir - self.lean, -2.5 * dt, 2.5 * dt)
        self.lean_rate = (self.lean - old) / dt if dt > 0 else 0.0
        if self.V.electric:
            self.clutch = 1.0
        elif clutch_key:
            self.clutch = max(0.0, self.clutch - 9.0 * dt)
        else:
            self.clutch = min(1.0, self.clutch + dt / 0.30)

    def shift(self, d):
        V = self.V
        if V.electric:
            nm = clamp(self.mode + d, 0, len(V.modes) - 1)
            if nm == self.mode:
                return None, None
            self.mode = nm
            return None, "Tryb mocy: " + V.modes[nm][0]
        ng = self.gear + d
        if ng < 1 or ng > V.n_gears:
            return None, None
        new_rpm = self.v / V.wheel_r * self.ratio(ng) * RAD2RPM
        if d < 0 and new_rpm > V.limit + 500:
            return "Za wysokie obroty na redukcję!", None
        self.gear = ng
        self.shift_t = 0.08
        self.shift_dir = d
        return None, None

    def auto_gear(self, dt):
        """Automatyczna skrzynia: wyższy bieg przy czerwonym polu albo gdy kontrola wheelie
        musi ciągle ucinać gaz (za duża siła na niskim biegu – dobry kierowca wrzuciłby wyższy)."""
        V = self.V
        if V.electric or self.shift_t > 0 or self.gear >= V.n_gears:
            return
        if self.wc_active and self.throttle > 0.5:
            self.wc_cut_t += dt
        else:
            self.wc_cut_t = max(0.0, self.wc_cut_t - 0.5 * dt)
        if self.rpm > V.redline * 0.97 or (self.gear == 1 and self.wc_cut_t > 0.3 and self.rpm > V.redline * 0.5):
            self.wc_cut_t = 0.0
            self.shift(+1)

    # -- krok fizyki --------------------------------------------------------
    def step(self, dt, wind):
        ev = []
        road = self.road
        d = self.diff
        V = self.V
        R = V.wheel_r
        M = V.mass
        ratio = self.ratio()
        rpm = self.we * RAD2RPM

        # --- kontrola wheelie (poziomy ŁATWY / NORMALNY) ---
        thr_in, brk_in = self.throttle, self.brake
        self.wc_active = False
        if d['wc'] and self.psi - self.base_prev > 0.02:
            beta0 = math.atan(road.sample(self.x)[1])
            rel = self.psi - beta0
            bp = self.balance_angle()
            limit = bp + math.radians(d['wc_margin'] - d['wc_drop'] * self.throttle)
            limit = min(limit, V.max_angle - math.radians(4))
            # o ile przód jeszcze "doleci", zanim obrót wyhamuje (grawitacja + ewentualnie hamulec)
            a_c0, h_c0 = self.com()
            iy0 = M * (a_c0 * a_c0 + h_c0 * h_c0 + V.gyr * V.gyr)
            alpha_b = max(0.5, V.brake_max * max(0.5, d['wc_brake']) * (R + h_c0) / iy0 * d['ts'])
            alpha_g = max(0.0, M * G * math.hypot(a_c0, h_c0)
                          * math.cos(self.psi + math.atan2(h_c0, a_c0))) / iy0 * d['ts']
            w2 = self.omega * self.omega if self.omega > 0 else 0.0
            excess = rel + w2 / (2.0 * (alpha_g + 0.4 * alpha_b)) - limit
            if excess > 0.0:                                             # za wysoko: przytnij gaz
                self.wc_active = True
                thr_in *= clamp(1.0 - excess / 0.05, 0.0, 1.0)
            over = rel + w2 / (2.0 * (alpha_g + alpha_b)) - max(limit, bp) - 0.03
            if over > 0.0:                                               # poleci za równowagę: hamulec
                self.wc_active = True
                brk_in = max(brk_in, clamp(over / 0.08, 0.0, 1.0) * d['wc_brake'])
        self.brake_eff = brk_in

        # --- napęd ---
        if V.electric:
            mult = V.modes[self.mode][1]
            avail = min(V.t_max * mult, V.p_max * mult / max(self.we, 1e-3))
            if rpm > V.max_rpm * 0.94:                     # ogranicznik prędkości sterownika
                avail *= clamp((V.max_rpm - rpm) / (V.max_rpm * 0.06), 0.0, 1.0)
            self.limiter = rpm > V.max_rpm * 0.97
            target = thr_in * avail
            self.regen = thr_in < 0.02 and self.we > 3.0
            if self.regen:
                target = -V.regen
            lag = d['lag'] * 0.4                           # elektryk reaguje natychmiast
            fric = 0.0
        else:
            if rpm > V.limit:
                self.limiter = True
            elif rpm < V.resume:
                self.limiter = False
            thr = thr_in
            if self.stalled:
                target = 0.0
            else:
                if rpm < V.idle + 150:                     # regulator biegu jałowego
                    thr = max(thr, clamp((V.idle + 150 - rpm) / 900.0, 0.0, 0.4))
                target = thr * interp(V.curve, rpm)
                if self.limiter or (self.shift_t > 0 and self.shift_dir > 0):
                    target = 0.0
            lag = d['lag']
            fric = (V.fric0 + V.fric1 * rpm) if self.we > 0.5 else 0.0
        self.tq += (target - self.tq) * min(1.0, dt / lag)
        we_t = max(0.0, self.we + (self.tq - fric) / V.i_engine * dt)

        # --- siły wzdłuż drogi ---
        y, dy, d2y = road.sample(self.x)
        beta = math.atan(dy)
        cb = math.cos(beta)
        v = self.v
        a_c, h_c = self.com()
        L = math.hypot(a_c, h_c)
        ang = self.psi + math.atan2(h_c, a_c)
        s_rel = math.sin(ang - beta)
        c_rel = math.cos(ang - beta)
        v_air = v - wind
        cda = V.cda + V.cda_up * max(0.0, math.sin(self.psi - beta))
        D = 0.5 * RHO * cda * v_air * abs(v_air)
        a_n = clamp(V.susp * v * v * d2y, -0.8 * G, 3.0 * G)   # szarpnięcie pionowe z nierówności

        # --- przyczepność tylnej opony ---
        if self.psi - self.base_prev > 0.01:
            frac = 1.0
        else:
            frac = clamp((V.wheelbase - a_c) / V.wheelbase + self.ax * (R + h_c) / (G * V.wheelbase), 0.15, 1.0)
        N = M * (G * cb + a_n) * frac
        trac = V.mu * max(N, 0.0)

        # --- przedni silnik (hulajnogi 2×): ciągnie tylko, gdy przednie koło dotyka asfaltu ---
        F_f = 0.0
        if V.front_t > 0 and V.modes[self.mode][2] and self.front_down:
            mult = V.modes[self.mode][1]
            avail_f = min(V.front_t * mult, V.front_p * mult / max(v / R, 1e-3))
            if rpm > V.max_rpm * 0.94:
                avail_f *= clamp((V.max_rpm - rpm) / (V.max_rpm * 0.06), 0.0, 1.0)
            self.tq_f += (thr_in * avail_f - self.tq_f) * min(1.0, dt / lag)
            F_f = clamp(self.tq_f / R, 0.0, V.mu * max(0.0, M * (G * cb + a_n) * (1.0 - frac)))
        else:
            self.tq_f = 0.0

        F_ext = F_f - D - M * G * math.sin(beta) - M * a_n * dy
        if v > 0.05:
            F_ext -= V.crr * M * G
            Fb = brk_in * V.brake_max
        else:
            Fb = 0.0
        F_ext -= Fb
        v_t = v + F_ext / M * dt

        # --- sprzęgło / przekładnia (więz z tarciem) ---
        ww = max(v_t, 0.0) / R * ratio
        if V.electric:
            cap_c = 1e9                                    # napęd bezpośredni
        elif self.shift_t > 0:
            # quickshifter / auto-blip: wał dopasowuje obroty, napęd chwilowo rozłączony
            we_t += (ww - we_t) * min(1.0, dt / 0.03)
            cap_c = 0.0
        else:
            cap_c = V.clutch_cap * self.clutch ** 2
        cap_t = trac * R / ratio
        cap = min(cap_c, cap_t)
        k = dt * (1.0 / V.i_engine + ratio * ratio / (M * R * R))
        Treq = (we_t - ww) / k
        Tc = clamp(Treq, -cap, cap)
        self.wheelspin = Treq > cap_t and cap_t < cap_c
        self.clutch_slip = (not self.wheelspin) and abs(Treq) > cap and abs(we_t - ww) > 8.0
        self.we = max(0.0, we_t - Tc / V.i_engine * dt)
        self.power = max(0.0, self.tq) * self.we + F_f * v
        Fd = Tc * ratio / R
        v_new = max(0.0, v_t + Fd / M * dt)
        self.ax = (v_new - v) / dt
        self.v = v_new
        self.x += v_new * cb * dt
        if self.shift_t > 0:
            self.shift_t -= dt

        # --- obrót wokół osi tylnego koła ---
        Iy = M * (L * L + V.gyr * V.gyr)
        tau = (Fd + F_f - Fb) * R                     # reakcja napędu / hamulca na ramę
        tau += M * self.ax * L * s_rel                # przyspieszenie "wciska" środek masy do tyłu
        tau -= M * G * L * math.cos(ang)              # grawitacja
        tau -= M * a_n * L * c_rel                    # szarpnięcia z nierówności
        tau += D * (0.25 * L * max(0.0, s_rel) + 0.06)  # wiatr w podwozie
        tau -= d['damp'] * self.omega                 # tłumienie (zawieszenie, opona)
        # szarpnięcie ciałem: odchylenie do tyłu podrywa przód (na łatwych poziomach mocniej),
        # powrót do przodu lekko go dociska
        tau_pull = -V.pull * self.lean_rate * (d['pull'] if self.lean_rate < 0 else d['ts'])
        self.omega += (tau * d['ts'] + tau_pull) / Iy * dt
        self.psi += self.omega * dt

        # --- przednie koło / ziemia ---
        base = self.base_pitch(self.x)
        base_rate = (base - self.base_prev) / dt
        self.base_prev = base
        lift = self.psi - base
        if lift <= 0.0:
            rel_w = self.omega - base_rate
            self.psi = base
            self.omega = base_rate
            if self.in_wheelie:
                self.in_wheelie = False
                ev.append(('land', self.w_dist, self.w_time, -rel_w, self.w_max))
            lift = 0.0
        self.front_down = lift < 0.004
        if not self.in_wheelie and lift > 0.035:
            self.in_wheelie = True
            self.w_dist = self.w_time = self.w_max = 0.0
            ev.append(('lift',))
        if self.in_wheelie:
            self.w_dist += v_new * dt
            self.w_time += dt
            self.w_max = max(self.w_max, self.psi - beta)

        # --- przewrotka ---
        if self.psi - beta > V.max_angle:
            ev.append(('loop',))

        # --- zgaśnięcie / odpalenie (tylko spalinowe) ---
        if not V.electric:
            rpm = self.we * RAD2RPM
            if not self.stalled and rpm < V.stall_rpm and self.clutch > 0.6:
                self.stalled = True
                ev.append(('stall',))
            elif self.stalled:
                if self.clutch > 0.9 and rpm > V.idle + 200:      # odpalenie "na pych"
                    self.stalled = False
                    ev.append(('restart',))
                elif self.clutch < 0.1:
                    self.restart_t += dt
                    if self.restart_t > 0.6:
                        self.stalled = False
                        self.restart_t = 0.0
                        self.we = V.idle / RAD2RPM
                        ev.append(('restart',))
                else:
                    self.restart_t = 0.0

        # --- wizualne obroty kół ---
        self.rear_rot += (self.we / ratio if self.wheelspin else v_new / R) * dt
        if self.front_down:
            self.front_spin = v_new / R
        else:
            self.front_spin *= (1.0 - 0.12 * dt)
        self.front_rot += self.front_spin * dt
        return ev


# ---------------------------------------------------------------------------
# Wrak po glebie (prosta bryła sztywna + kierowca jako "szmaciana lalka")
# ---------------------------------------------------------------------------
RIDER_EXTREMES = ((0.0, 0.0), (0.09, 0.78), (0.30, -0.70), (0.50, 0.30))   # biodro, głowa, stopa, dłoń


class Wreck:
    def __init__(self, bike, kind):
        V = bike.V
        self.V = V
        self.road = bike.road
        ax, ay = bike.axle
        com = rot((V.com_a, V.com_h * 0.7), bike.psi)
        self.com_local = (V.com_a, V.com_h * 0.7)
        self.cx, self.cy = ax + com[0], ay + com[1]
        self.ang = bike.psi
        self.vx = bike.v - bike.omega * com[1]
        self.vy = bike.omega * com[0]
        self.om = bike.omega
        self.m = max(20.0, V.mass - RIDER_MASS)
        self.I = self.m * (0.30 * V.wheelbase) ** 2
        self.hull = [(p[0] - self.com_local[0], p[1] - self.com_local[1]) for p in V.hull]
        self.n_body = V.n_body
        self.rear_rot = bike.rear_rot
        self.front_rot = bike.front_rot
        self.wheel_spin = bike.v / V.wheel_r
        hip = rot(V.hip, bike.psi)
        self.rx, self.ry = ax + hip[0], ay + hip[1]
        self.rvx = bike.v - bike.omega * hip[1]
        self.rvy = bike.omega * hip[0]
        if kind == 'loop':
            self.rvx -= 1.5
            self.rvy += 1.5
            self.rom = 4.0
        else:                                   # uderzenie przodem: przewrotka przez kierownicę
            self.vy += 4.5
            self.om = -6.5
            self.rvx += 1.0
            self.rvy += 3.5
            self.rom = -7.0
        self.rang = bike.psi
        self.t = 0.0
        self.sparks = []

    def bike_point(self, p):
        r = rot((p[0] - self.com_local[0], p[1] - self.com_local[1]), self.ang)
        return self.cx + r[0], self.cy + r[1]

    def update(self, dt):
        self.t += dt
        self.sparks = []
        steps = max(1, int(dt / 0.002))
        h = dt / steps
        for _ in range(steps):
            # --- pojazd ---
            self.vy -= G * h
            self.cx += self.vx * h
            self.cy += self.vy * h
            self.ang += self.om * h
            touching = wheel = False
            for idx, p in enumerate(self.hull):
                rx, ry = rot(p, self.ang)
                px, py = self.cx + rx, self.cy + ry
                gy = self.road.height(px)
                if py < gy:
                    body = idx < self.n_body                 # owiewka/rama trze, koła szorują
                    mu = 0.55 if body else 0.30
                    touching = touching or body
                    wheel = wheel or not body
                    self.cy += (gy - py)
                    vpx = self.vx - self.om * ry
                    vpy = self.vy + self.om * rx
                    if vpy < 0:
                        jn = -(1.25) * vpy / (1.0 / self.m + rx * rx / self.I)
                        self.vy += jn / self.m
                        self.om += rx * jn / self.I
                        jt = -vpx / (1.0 / self.m + ry * ry / self.I)
                        jt = clamp(jt, -mu * jn, mu * jn)
                        self.vx += jt / self.m
                        self.om -= ry * jt / self.I
                    if body and abs(vpx) > 2.5:
                        self.sparks.append((px, gy, vpx))
            if touching:                                      # tarcie plastiku i metalu o asfalt
                fr = 0.45 * G * h
                self.vx = self.vx - fr if self.vx > fr else (self.vx + fr if self.vx < -fr else 0.0)
                self.om *= (1.0 - 1.5 * h)
            elif wheel:                                       # przewrócony pojazd szoruje kołami
                fr = 0.30 * G * h
                self.vx = self.vx - fr if self.vx > fr else (self.vx + fr if self.vx < -fr else 0.0)
            self.om *= (1.0 - 0.4 * h)
            # --- kierowca ---
            self.rvy -= G * h
            self.rx += self.rvx * h
            self.ry += self.rvy * h
            self.rang += self.rom * h
            low = min(rot(q, self.rang)[1] for q in RIDER_EXTREMES) - 0.12
            gy = self.road.height(self.rx) - low
            if self.ry < gy:
                self.ry = gy
                if self.rvy < 0:
                    self.rvy = -self.rvy * 0.2
            if self.ry < gy + 0.3:                       # turlanie i ślizg po asfalcie
                fr = 0.9 * G * h
                self.rvx = self.rvx - fr if self.rvx > fr else (self.rvx + fr if self.rvx < -fr else 0.0)
                self.rom += (clamp(-self.rvx / 1.2, -6.0, 6.0) - self.rom) * 0.01
                if abs(self.rvx) < 2.5:                   # leży na asfalcie
                    flat = math.pi / 2 + math.pi * round((self.rang - math.pi / 2) / math.pi)
                    self.rang += (flat - self.rang) * min(1.0, 4.0 * h)
                    self.rom *= (1.0 - 6.0 * h)
            self.rom *= (1.0 - 0.3 * h)
        self.wheel_spin *= (1.0 - 0.6 * dt)
        self.rear_rot += self.wheel_spin * dt
        self.front_rot += self.wheel_spin * dt


# ---------------------------------------------------------------------------
# Kierowca
# ---------------------------------------------------------------------------
def rider_pose(V, lean, psi):
    """Stawy kierowcy w układzie pojazdu. Przy wheelie tułów wraca do pionu."""
    hip = (V.hip[0] + V.hip_shift * lean, V.hip[1] - V.hip_drop * abs(lean))
    t = math.radians(V.torso_deg + V.torso_lean * lean) + V.upright * max(0.0, psi)
    sh = (hip[0] + V.torso_len * math.sin(t), hip[1] + V.torso_len * math.cos(t))
    head = (sh[0] + 0.19 * math.sin(t * 0.8) + 0.03, sh[1] + 0.19 * math.cos(t * 0.8))
    elbow = ik2(sh, V.grip, V.upper, V.fore, -1)
    knee = ik2(hip, V.peg, V.thigh, V.shin, 1)
    pose = dict(hip=hip, sh=sh, head=head, elbow=elbow, hand=V.grip, knee=knee,
                foot=V.peg, toe=(V.peg[0] + 0.13, V.peg[1] - 0.01), face=rot((1.0, 0.0), -0.75 * psi))
    if V.peg2:
        pose['knee2'] = ik2(hip, V.peg2, V.thigh, V.shin, 1)
        pose['foot2'] = V.peg2
        pose['toe2'] = (V.peg2[0] + 0.13, V.peg2[1] - 0.01)
    return pose


def tumble_pose(t):
    """Pozycja "szmacianej lalki" (układ: biodro = (0,0))."""
    w = math.sin(t * 7.0) * 0.15
    return dict(hip=(0, 0), sh=(0.05, 0.56), head=(0.09, 0.78),
                elbow=(0.30, 0.45 + w), hand=(0.50, 0.30 + w),
                knee=(0.35, -0.25 - w), foot=(0.30, -0.70 - w), toe=(0.43, -0.72 - w), face=(1.0, 0.2))


# ---------------------------------------------------------------------------
# Platforma: telefon / przeglądarka
# ---------------------------------------------------------------------------
IS_WEB = sys.platform == "emscripten"            # pygbag (przeglądarka, także na telefonie)
IS_ANDROID = (hasattr(sys, "getandroidapilevel") or "ANDROID_ROOT" in os.environ
              or "ANDROID_ARGUMENT" in os.environ)
FINGERDOWN = getattr(pygame, "FINGERDOWN", -101)
FINGERUP = getattr(pygame, "FINGERUP", -102)
FINGERMOTION = getattr(pygame, "FINGERMOTION", -103)
PAUSE_EVENTS = {getattr(pygame, n, -200 - i) for i, n in
                enumerate(("APP_WILLENTERBACKGROUND", "APP_DIDENTERBACKGROUND", "WINDOWFOCUSLOST"))}
K_AC_BACK = getattr(pygame, "K_AC_BACK", -300)


# ---------------------------------------------------------------------------
# Gra
# ---------------------------------------------------------------------------
class Game:
    def __init__(self, mobile=None):
        if IS_ANDROID:
            os.environ.setdefault("SDL_IOS_ORIENTATIONS", "LandscapeLeft LandscapeRight")
        pygame.init()
        pygame.display.set_caption("Wheelie Simulator 2D")
        self._setup_display()
        self.clock = pygame.time.Clock()
        self.f_small = self._font(16)
        self.f_med = self._font(22, True)
        self.f_big = self._font(40, True)
        self.f_huge = self._font(72, True)
        missing = self.f_small.metrics("")
        ok = self.f_small.metrics("←") != missing
        self.ar = dict(up="↑", down="↓", left="←", right="→") if ok else dict(up="^", down="v", left="<", right=">")
        self._cache_txt = {}             # gotowe napisy (renderowanie tekstu jest drogie)
        self._cache_panel = {}
        self._cache_btn = {}
        self._cache_gauge = {}
        self._cache_shadow = {}
        self.sky = self._make_sky().convert()
        self._bg_far, self._bg_near = self._make_bg_strips()
        self.best = self._load_best()
        self.diff_id = 2                 # NORMALNY
        self.veh_idx = 0
        self.mobile = bool(IS_ANDROID) if mobile is None else mobile
        self.choose_sel = 0 if self.mobile else 1        # podświetlony wybór na ekranie startowym
        self.mouse_mode = False
        self.show_help = False
        self.paused = False
        self._shk = (0.0, 0.0)
        self._sc = 120.0
        self.garage_t = 0.0
        self.zones = []                  # klikalne/dotykowe przyciski z ostatniej klatki
        self.touches = {}                # id palca -> [pozycja, kontrolka]
        self.last_finger = -10000
        # SDL przy SCALED sam przelicza dotyk na obszar gry (bez czarnych pasów);
        # przy ręcznym skalowaniu dotyk jest względem całego ekranu. Autokalibracja niżej.
        self.finger_logical = self.window is None
        self._smoke = {}
        self.new_run()
        self.state = 'choose' if mobile is None else 'garage'

    # -- ekran ----------------------------------------------------------------
    def _setup_display(self):
        scaled = getattr(pygame, "SCALED", 0)
        self.window = None                       # != None => ręczne skalowanie bufora
        if IS_WEB:
            options = [(0, False)]
        elif IS_ANDROID:
            options = [(scaled | pygame.FULLSCREEN, False), (pygame.FULLSCREEN, True)]
        else:
            options = [(scaled | getattr(pygame, "RESIZABLE", 0), False), (0, False)]
        for flags, manual in options:
            try:
                if manual:
                    self.window = pygame.display.set_mode((0, 0), flags)
                    self.screen = pygame.Surface((W, H))
                else:
                    self.screen = pygame.display.set_mode((W, H), flags)
                return
            except pygame.error:
                continue
        self.screen = pygame.display.set_mode((W, H))

    def _window_size(self):
        if self.window is not None:
            return self.window.get_size()
        try:
            return pygame.display.get_window_size()
        except (AttributeError, pygame.error):
            return W, H

    def _letterbox(self):
        ww, wh = self._window_size()
        s = min(ww / W, wh / H) or 1.0
        return s, (ww - W * s) / 2, (wh - H * s) / 2

    def _present(self):
        if self.window is not None:
            s, ox, oy = self._letterbox()
            img = pygame.transform.scale(self.screen, (int(W * s), int(H * s)))
            self.window.fill((0, 0, 0))
            self.window.blit(img, (ox, oy))
        pygame.display.flip()

    def _map_finger(self, fx, fy, logical):
        if logical:                                    # współrzędne już względem obszaru gry
            return fx * W, fy * H
        ww, wh = self._window_size()                   # względem całego ekranu – odejmij czarne pasy
        s, ox, oy = self._letterbox()
        return ((fx * ww - ox) / s, (fy * wh - oy) / s)

    def _finger_pos(self, e):
        return self._map_finger(e.x, e.y, self.finger_logical)

    def _calibrate_touch(self, events):
        """System tworzy z pierwszego palca też zdarzenie myszy w poprawnych współrzędnych gry –
        porównujemy je z dotykiem i wybieramy przeliczenie, które się zgadza."""
        fd = next((e for e in events if e.type == FINGERDOWN), None)
        md = next((e for e in events if e.type == pygame.MOUSEBUTTONDOWN and getattr(e, "touch", False)), None)
        if fd is None or md is None:
            return
        mx, my = self._mouse_pos(md.pos)
        ax, ay = self._map_finger(fd.x, fd.y, True)
        bx, by = self._map_finger(fd.x, fd.y, False)
        da = math.hypot(ax - mx, ay - my)
        db = math.hypot(bx - mx, by - my)
        if abs(da - db) > 10:
            self.finger_logical = da < db

    def _mouse_pos(self, pos):
        if self.window is None:
            return pos
        s, ox, oy = self._letterbox()
        return ((pos[0] - ox) / s, (pos[1] - oy) / s)

    @property
    def anchor_x(self):
        return W * (0.50 if self.mobile else 0.40)

    # -- zasoby ---------------------------------------------------------------
    @staticmethod
    def _font(size, bold=False):
        if not IS_WEB:
            for name in ("dejavusans", "arial", "liberationsans", "verdana", "segoeui", "roboto"):
                try:
                    path = pygame.font.match_font(name, bold=bold)
                    if path:
                        return pygame.font.Font(path, size)
                except Exception:          # brak fc-list (Android), dziwne czcionki itp.
                    pass
        return pygame.font.Font(None, int(size * 1.35))

    @staticmethod
    def _make_sky():
        surf = pygame.Surface((W, H))
        top, bot = (62, 120, 196), (206, 226, 240)
        for yy in range(H):
            t = min(1.0, yy / (H * 0.75))
            c = tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3))
            pygame.draw.line(surf, c, (0, yy), (W, yy))
        pygame.draw.circle(surf, (255, 246, 210), (880, 215), 46)
        pygame.draw.circle(surf, (255, 252, 235), (880, 215), 38)
        return surf

    @staticmethod
    def _make_bg_strips():
        """Góry, wzgórza i drzewa rysowane RAZ do długich pasków powtarzających się co BG_P pikseli."""
        tw = BG_P + W
        rle = getattr(pygame, "RLEACCEL", 0)

        def ridge(surf, base, amp, cycles, top, color):
            pts = [(0, surf.get_height())]
            for sx in range(0, tw + 8, 8):
                u = sx * 2 * math.pi * cycles / BG_P
                yy = base - amp * (0.55 * math.sin(u) + 0.3 * math.sin(2 * u + 1.7) + 0.15 * math.sin(5 * u + 0.3))
                pts.append((sx, yy - top))
            pts.append((tw, surf.get_height()))
            pygame.draw.polygon(surf, color, pts)

        far = pygame.Surface((tw, 300)).convert()          # wiersze ekranu 200..500
        far.fill(CKEY)
        ridge(far, 380, 120, 1, 200, (150, 170, 196))
        near = pygame.Surface((tw, 160)).convert()         # wiersze ekranu 330..490
        near.fill(CKEY)
        ridge(near, 430, 80, 2, 330, (118, 146, 128))
        gy = 140
        pygame.draw.rect(near, (96, 140, 84), (0, gy, tw, 160 - gy))
        slot = 150
        nslots = BG_P // slot
        for k in range(-1, tw // slot + 2):
            kk = k % nslots
            r = hrand(kk, 3)
            if r < 0.45:
                continue
            sx = k * slot + hrand(kk, 5) * 100
            hgt = 50 + hrand(kk, 9) * 60
            pygame.draw.rect(near, (90, 66, 44), (sx - 4, gy - hgt * 0.45, 8, hgt * 0.45 + 4))
            c = (52 + int(hrand(kk, 11) * 30), 104 + int(hrand(kk, 13) * 30), 60)
            if r < 0.75:
                pygame.draw.polygon(near, c, [(sx, gy - hgt * 1.2), (sx - hgt * 0.35, gy - hgt * 0.3),
                                              (sx + hgt * 0.35, gy - hgt * 0.3)])
            else:
                pygame.draw.circle(near, c, (int(sx), int(gy - hgt * 0.7)), int(hgt * 0.38))
        far.set_colorkey(CKEY, rle)
        near.set_colorkey(CKEY, rle)
        return far, near

    @staticmethod
    def _load_best():
        try:
            with open(SAVE_FILE, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            out = {}
            old = data.pop("_v", 1) < SAVE_VERSION
            for k, v in data.items():
                k = str(k)
                if k.isdigit():                       # bardzo stary format (tylko ścigacz)
                    k = "moto:" + k
                if old and ":" in k:                  # stare poziomy 1-3 to teraz 2-4
                    vid, lvl = k.rsplit(":", 1)
                    if lvl.isdigit():
                        k = "%s:%d" % (vid, int(lvl) + 1)
                out[k] = float(v)
            return out
        except Exception:
            return {}

    def _save_best(self):
        try:
            with open(SAVE_FILE, "w", encoding="utf-8") as fh:
                data = dict(self.best)
                data["_v"] = SAVE_VERSION
                json.dump(data, fh)
        except Exception:
            pass

    # -- stan gry -------------------------------------------------------------
    @property
    def diff(self):
        return DIFFS[self.diff_id]

    @property
    def veh(self):
        return VEHICLES[self.veh_idx]

    def best_key(self, veh=None, diff_id=None):
        return "%s:%d" % ((veh or self.veh).vid, diff_id or self.diff_id)

    def new_run(self):
        seed = random.randrange(1 << 30)
        self.road = Road(seed, self.diff['bumps'])
        self.wind = Wind(self.diff['wind'], self.diff['gusts'], random.Random(seed + 7))
        self.bike = Bike(self.road, self.diff, self.veh)
        self.state = 'ride'
        self.paused = False
        self.touches = {}
        self.wreck = None
        self.crash_reason = ""
        self.crash_wheelie = 0.0
        self.acc = 0.0
        self.cam_x = self.bike.x
        self.cam_off = 0.8
        self.cam_y = self.road.height(self.bike.x)
        self.scale = 120.0
        self.particles = []
        self.shake = 0.0
        self.msgs = []
        self.run_best = 0.0
        self.last = None

    def to_garage(self):
        self.state = 'garage'
        self.paused = False
        self.touches = {}
        self.garage_t = 0.0

    def msg(self, text, color=C_TEXT, ttl=2.0):
        self.msgs.append([text, color, ttl, ttl])
        self.msgs = self.msgs[-3:]

    def crash(self, kind):
        if self.state != 'ride':
            return
        b = self.bike
        self.crash_wheelie = b.w_dist if b.in_wheelie else 0.0   # nie liczy się – trzeba wylądować
        self.state = 'crash'
        self.touches = {}
        self.wreck = Wreck(b, kind)
        self.shake = 1.0
        if kind == 'loop':
            self.crash_reason = self.veh.loop_msg
        else:
            self.crash_reason = "Za mocne uderzenie przodem – gleba!"

    def _record(self, dist):
        key = self.best_key()
        if dist > self.best.get(key, 0.0) and dist >= 2.0:
            self.best[key] = dist
            self._save_best()
            return True
        return False

    # -- pętla ----------------------------------------------------------------
    def frame(self):
        """Jedna klatka gry. Zwraca False, gdy trzeba zakończyć."""
        dt = min(self.clock.tick(FPS) / 1000.0, 0.1)     # przy słabym FPS gra nie zwalnia (do 10 kl./s)
        if not self.handle_events():
            return False
        if self.state in ('garage', 'choose'):
            self.garage_t += dt
        elif not (self.show_help or self.paused):
            self.update(dt)
        self.draw()
        self._present()
        return True

    def run(self):
        while self.frame():
            pass
        pygame.quit()

    # -- zdarzenia ------------------------------------------------------------
    def handle_events(self):
        now = pygame.time.get_ticks()
        events = pygame.event.get()
        self._calibrate_touch(events)
        for e in events:
            t = e.type
            if t == pygame.QUIT:
                return False
            if t in PAUSE_EVENTS:                       # telefon: aplikacja w tle
                if self.state == 'ride':
                    self.paused = True
                self.touches = {}
            elif t == pygame.KEYDOWN:
                if not self._key(e.key):
                    return False
            elif t == FINGERDOWN:
                self.last_finger = now
                self._touch_down(("f", e.finger_id), self._finger_pos(e))
            elif t == FINGERMOTION:
                self.last_finger = now
                self._touch_move(("f", e.finger_id), self._finger_pos(e))
            elif t == FINGERUP:
                self.last_finger = now
                self.touches.pop(("f", e.finger_id), None)
            elif t in (pygame.MOUSEBUTTONDOWN, pygame.MOUSEBUTTONUP, pygame.MOUSEMOTION):
                # dotyk generuje też "sztuczną" mysz – ignorujemy ją, żeby nie liczyć dwa razy
                if getattr(e, "touch", False) or now - self.last_finger < 800:
                    continue
                pos = self._mouse_pos(e.pos)
                if t == pygame.MOUSEBUTTONDOWN and e.button == 1:
                    self._touch_down("mouse", pos)
                elif t == pygame.MOUSEMOTION and self.state == 'choose':      # podświetlenie pod kursorem
                    for rect, action in self.zones:
                        if rect.collidepoint(pos) and action in ('choose_phone', 'choose_pc'):
                            self.choose_sel = 0 if action == 'choose_phone' else 1
                elif t == pygame.MOUSEMOTION and "mouse" in self.touches:
                    self._touch_move("mouse", pos)
                elif t == pygame.MOUSEBUTTONUP and e.button == 1:
                    self.touches.pop("mouse", None)
        return True

    def _key(self, k):
        if self.show_help:
            self.show_help = False
            return True
        if k in (pygame.K_1, pygame.K_2, pygame.K_3, pygame.K_4):
            self._set_diff(k - pygame.K_0)
            return True
        if k in (pygame.K_h, pygame.K_F1):
            self.show_help = True
            return True
        if self.state == 'choose':
            if k in (pygame.K_ESCAPE, K_AC_BACK):
                return False
            if k in (pygame.K_LEFT, pygame.K_a, pygame.K_UP):
                self.choose_sel = 0
            elif k in (pygame.K_RIGHT, pygame.K_d, pygame.K_DOWN):
                self.choose_sel = 1
            elif k == pygame.K_t:
                self._action('choose_phone')
            elif k == pygame.K_p:
                self._action('choose_pc')
            elif k in (pygame.K_RETURN, pygame.K_KP_ENTER, pygame.K_SPACE):
                self._action('choose_phone' if self.choose_sel == 0 else 'choose_pc')
            return True
        if k == pygame.K_t:
            self._action('toggle_mobile')
            return True
        if self.state == 'garage':
            if k in (pygame.K_ESCAPE, K_AC_BACK):
                return False
            if k in (pygame.K_LEFT, pygame.K_a):
                self._action('prev')
            elif k in (pygame.K_RIGHT, pygame.K_d):
                self._action('next')
            elif k in (pygame.K_RETURN, pygame.K_KP_ENTER, pygame.K_SPACE):
                self._action('start')
            return True
        if k == pygame.K_ESCAPE:
            return False
        if k in (pygame.K_g, pygame.K_TAB, K_AC_BACK):
            self.to_garage()
        elif k == pygame.K_p:
            self.paused = not self.paused
        elif k in (pygame.K_r, pygame.K_RETURN, pygame.K_KP_ENTER):
            self.new_run()
        elif k == pygame.K_m:
            self.mouse_mode = not self.mouse_mode
            self.msg("Gaz: " + ("MYSZ (pozycja w pionie)" if self.mouse_mode else "KLAWIATURA"), C_WARN)
        elif self.state == 'ride' and k in (pygame.K_e, pygame.K_PAGEUP):
            self._shift(+1)
        elif self.state == 'ride' and k in (pygame.K_q, pygame.K_PAGEDOWN):
            self._shift(-1)
        return True

    def _set_diff(self, d):
        self.diff_id = d
        if self.state in ('ride', 'crash'):
            self.new_run()
            self.msg("Poziom: " + self.diff['name'], C_WARN)

    def _action(self, a):
        if a == 'prev':
            self.veh_idx = (self.veh_idx - 1) % len(VEHICLES)
            self.garage_t = 0.0
        elif a == 'next':
            self.veh_idx = (self.veh_idx + 1) % len(VEHICLES)
            self.garage_t = 0.0
        elif a == 'start':
            self.new_run()
            self.msg(self.veh.name + " – powodzenia!", C_WARN, 2.0)
        elif a.startswith('diff'):
            self._set_diff(int(a[4:]))
        elif a in ('choose_phone', 'choose_pc'):
            self.mobile = a == 'choose_phone'
            self.choose_sel = 0 if self.mobile else 1
            self.to_garage()
            self.msgs = []
        elif a == 'toggle_mobile':                       # powrót do ekranu wyboru TELEFON / PC
            self.state = 'choose'
            self.paused = False
            self.touches = {}
        elif a == 'help':
            self.show_help = True
        elif a == 'pause':
            self.paused = not self.paused
        elif a == 'restart':
            self.new_run()
        elif a == 'garage':
            self.to_garage()
        elif a == 'gear_up':
            self._shift(+1)
        elif a == 'gear_down':
            self._shift(-1)

    # -- dotyk ------------------------------------------------------------------
    def _ctl_rects(self):
        r = {'gas': pygame.Rect(W - 170, H - 480, 140, 450),
             'brake': pygame.Rect(W - 340, H - 160, 150, 130),
             'back': pygame.Rect(30, H - 160, 140, 130),
             'fwd': pygame.Rect(190, H - 160, 140, 130)}
        if not self.veh.electric:
            r['clutch'] = pygame.Rect(30, H - 320, 140, 140)
        return r

    def _ctl_hit_rects(self):
        """Strefy dotyku: trochę większe niż przyciski, a przy krawędzi sięgają do brzegu ekranu
        (kciuk często leży na samej krawędzi / na czarnym pasie)."""
        R = self._ctl_rects()
        m = 8
        hit = {}
        for name, r in R.items():
            left, top, right, bottom = r.left - m, r.top - m, r.right + m, r.bottom + m
            if name in ('back', 'clutch'):
                left = -200
            if name == 'gas':
                right = W + 200
            if name in ('gas', 'brake', 'back', 'fwd'):
                bottom = H + 200
            hit[name] = pygame.Rect(left, top, right - left, bottom - top)
        return hit

    def _hit_ctl(self, pos):
        if not (self.mobile and self.state == 'ride'):
            return None
        for name, rect in self._ctl_hit_rects().items():
            if rect.collidepoint(pos):
                return name
        return None

    def _hit_zone(self, pos):
        for rect, action in reversed(self.zones):
            if rect.collidepoint(pos):
                return action
        for rect, action in reversed(self.zones):     # drugie podejście: lekki margines na palec
            if rect.inflate(8, 8).collidepoint(pos):
                return action
        return None

    def _touch_down(self, tid, pos):
        if self.show_help:
            self.show_help = False
            return
        if self.paused:                                # w pauzie działają przyciski u góry,
            action = self._hit_zone(pos)               # a dotknięcie w innym miejscu wznawia grę
            if action in ('restart', 'garage', 'help', 'pause'):
                self._action(action)
                if action == 'pause':
                    self.paused = False
            else:
                self.paused = False
            return
        ctl = self._hit_ctl(pos)
        if ctl:
            self.touches[tid] = [pos, ctl]
            return
        action = self._hit_zone(pos)
        if action:
            self._action(action)
            return
        self.touches[tid] = [pos, None]

    def _touch_move(self, tid, pos):
        t = self.touches.get(tid)
        if t is None:
            return
        t[0] = pos
        if t[1] != 'gas':                       # suwak gazu trzyma palec, przyciski można "przesuwać"
            t[1] = self._hit_ctl(pos)

    def _touch_input(self):
        thr, brk, lean, clutch = None, False, 0.0, False
        g = self._ctl_rects()['gas']
        for pos, ctl in self.touches.values():
            if ctl == 'gas':
                v = clamp((g.bottom - 24 - pos[1]) / (g.height - 48), 0.0, 1.0)
                thr = v if thr is None else max(thr, v)
            elif ctl == 'brake':
                brk = True
            elif ctl == 'back':
                lean -= 1.0
            elif ctl == 'fwd':
                lean += 1.0
            elif ctl == 'clutch':
                clutch = True
        return thr, brk, clamp(lean, -1.0, 1.0), clutch

    def _shift(self, d):
        err, info = self.bike.shift(d)
        if err:
            self.msg(err, C_BAD, 1.2)
        elif info:
            self.msg(info, C_WARN, 1.0)

    # -- aktualizacja -----------------------------------------------------------
    def update(self, dt):
        if self.state not in ('ride', 'crash'):
            return
        for m in self.msgs:
            m[2] -= dt
        self.msgs = [m for m in self.msgs if m[2] > 0]
        self.shake = max(0.0, self.shake - dt * 2.5)

        if self.state == 'ride':
            keys = pygame.key.get_pressed()
            thr = keys[pygame.K_w] or keys[pygame.K_UP]
            brk = keys[pygame.K_s] or keys[pygame.K_DOWN]
            lean = 0.0
            if keys[pygame.K_a] or keys[pygame.K_LEFT]:
                lean -= 1.0
            if keys[pygame.K_d] or keys[pygame.K_RIGHT]:
                lean += 1.0
            clutch = keys[pygame.K_SPACE]
            mthr = None
            if self.mobile:
                tthr, tbrk, tlean, tclutch = self._touch_input()
                mthr = tthr
                brk = brk or tbrk
                lean = clamp(lean + tlean, -1.0, 1.0)
                clutch = clutch or tclutch
            elif self.mouse_mode:
                my = self._mouse_pos(pygame.mouse.get_pos())[1]
                mthr = clamp((H * 0.85 - my) / (H * 0.7), 0.0, 1.0)
            b = self.bike
            b.controls(dt, thr, brk, lean, clutch, mthr)
            if self.diff['auto_shift'] and not clutch:
                b.auto_gear(dt)                               # automat (ŁATWY / NORMALNY)
            self.wind.update(dt)
            if self.wind.announce:
                self.msg("PODMUCH WIATRU!" if self.wind.g_peak < 0 else "Wiatr w plecy!", C_WARN, 1.5)
            self.acc += dt
            while self.acc >= PHYS_DT and self.state == 'ride':
                self.acc -= PHYS_DT
                for ev in b.step(PHYS_DT, self.wind.speed):
                    self._event(ev)
            self.road.ensure(b.x)
            self._ride_particles()
            focus_x, focus_y = b.x + 0.7, self.road.height(b.x)
            speed = b.v
        else:
            wr = self.wreck
            wr.update(dt)
            for (px, py, vpx) in wr.sparks[:4]:
                if random.random() < 0.5:
                    self.particles.append([px, py + 0.02, vpx * 0.6 + random.uniform(-2, 2),
                                           random.uniform(0.5, 3.0), 0.35, 0.35, 'spark'])
            focus_x = (wr.cx + wr.rx) / 2
            focus_y = self.road.height(focus_x)
            speed = max(0.0, wr.vx)

        # kamera
        target_scale = 122.0 - clamp(speed * 0.9, 0.0, 45.0)
        if self.state == 'crash':
            target_scale = clamp(W * 0.6 / max(1.0, abs(self.wreck.rx - self.wreck.cx)), 30.0, 100.0)
            off_target = (self.anchor_x - W / 2) / self.scale
        else:
            off_target = 0.6 + speed * 0.035
        self.scale += (target_scale - self.scale) * min(1.0, dt * 1.5)
        self.cam_off += (off_target - self.cam_off) * min(1.0, dt * 2.0)
        self.cam_x = focus_x + self.cam_off
        self.cam_y += (focus_y - self.cam_y) * min(1.0, dt * 4.0)

        # cząsteczki
        alive = []
        for p in self.particles:
            p[4] -= dt
            if p[4] <= 0:
                continue
            if p[6] == 'spark':
                p[3] -= G * dt
            else:
                p[3] += 0.4 * dt
                p[2] *= (1.0 - 1.5 * dt)
            p[0] += p[2] * dt
            p[1] += p[3] * dt
            alive.append(p)
        self.particles = alive[-(150 if self.mobile else 400):]

    def _event(self, ev):
        b = self.bike
        kind = ev[0]
        if kind == 'land':
            _, dist, tm, impact, wmax = ev
            if impact > self.diff['slam'] * self.veh.slam_mult:
                b.in_wheelie, b.w_dist = True, dist
                self.crash('slam')
                return
            if impact > 2.4 * self.veh.slam_mult:
                self.shake = max(self.shake, 0.5)
                self.msg("Twarde lądowanie!", C_WARN, 1.2)
            if dist >= 2.0:
                self.last = (dist, tm, math.degrees(wmax))
                if dist > self.run_best:
                    self.run_best = dist
                if szept is not None:            # wynik do tabeli w Szepcie (poza Szeptem nic nie robi)
                    szept.score(round(dist, 1), label="%s · %s" % (self.veh.name, self.diff['name'].capitalize()))
                if self._record(dist):
                    self.msg("NOWY REKORD! %.1f m" % dist, C_GOOD, 3.0)
                else:
                    self.msg("Wheelie: %.1f m  (%.1f s)" % (dist, tm), C_TEXT, 2.0)
            fx = b.x + self.veh.wheelbase
            fy = self.road.height(fx)
            for _ in range(int(4 + impact * 4)):
                self.particles.append([fx + random.uniform(-0.2, 0.2), fy + 0.05, random.uniform(-2, 1) + b.v * 0.3,
                                       random.uniform(0.2, 1.0), 0.6, 0.6, 'dust'])
        elif kind == 'loop':
            self.crash('loop')
        elif kind == 'stall':
            if self.mobile:
                self.msg("SILNIK ZGASŁ! Przytrzymaj SPRZĘGŁO, aby odpalić.", C_BAD, 3.0)
            else:
                self.msg("SILNIK ZGASŁ! Przytrzymaj SPACJĘ, aby odpalić.", C_BAD, 3.0)
        elif kind == 'restart':
            self.msg("Silnik odpalony", C_GOOD, 1.0)

    def _ride_particles(self):
        b = self.bike
        if b.wheelspin and b.v < 60:
            for _ in range(1 if self.mobile else 2):
                self.particles.append([b.x - 0.2 + random.uniform(-0.1, 0.1),
                                       self.road.height(b.x) + 0.05,
                                       b.v * 0.25 - random.uniform(1.0, 3.0), random.uniform(0.2, 0.9),
                                       1.4, 1.4, 'smoke'])

    # -- rysowanie ------------------------------------------------------------
    def draw(self):
        scr = self.screen
        self.zones = []
        if self.state == 'choose':
            self._draw_choose()
        elif self.state == 'garage':
            self._draw_garage()
        else:
            self._shk = (random.uniform(-1, 1) * 9 * self.shake, random.uniform(-1, 1) * 9 * self.shake)
            scr.blit(self.sky, (0, 0))
            self._draw_background()
            self._draw_road()
            self._draw_particles(back=True)
            if self.state == 'ride':
                self._draw_bike_ride()
            else:
                self._draw_wreck()
            self._draw_particles(back=False)
            self._draw_hud()
            if self.mobile and self.state == 'ride':
                self._draw_touch_controls()
            if self.state == 'crash' and self.wreck.t > 1.2:
                self._draw_crash_panel()
            if self.paused:
                self._panel((0, 0, W, H), 120)
                self._center_text("PAUZA", self.f_huge, C_TEXT, H * 0.4)
                self._center_text("Dotknij ekranu, aby wznowić" if self.mobile else "P – wznów", self.f_med,
                                  C_TEXT, H * 0.4 + 60)
        if self.show_help:
            self._draw_help()
        ww, wh = self._window_size()
        if self.mobile and wh > ww:
            self._panel((0, 0, W, H), 225)
            self._center_text("Obróć telefon poziomo", self.f_huge, C_WARN, H * 0.45)

    # przyciski -------------------------------------------------------------------
    def _button(self, rect, label, action=None, pressed=False, font=None, color=(255, 255, 255), alpha=70):
        rect = pygame.Rect(rect)
        font = font or self.f_med
        key = (rect.w, rect.h, pressed, alpha, color, label, id(font))
        surf = self._cache_btn.get(key)
        if surf is None:                                  # tło + obwódka + napis rysowane raz
            surf = pygame.Surface(rect.size, pygame.SRCALPHA)
            fill = (255, 255, 255, 150) if pressed else (20, 24, 32, alpha + 60)
            try:
                pygame.draw.rect(surf, fill, surf.get_rect(), border_radius=18)
                pygame.draw.rect(surf, color + (220,), surf.get_rect(), 3, border_radius=18)
            except TypeError:                             # bardzo stary pygame
                pygame.draw.rect(surf, fill, surf.get_rect())
                pygame.draw.rect(surf, color + (220,), surf.get_rect(), 3)
            if label:
                img = self._text_surf(label, font, (20, 20, 24) if pressed else color, not pressed)
                w, h = img.get_size()
                if not pressed:
                    w, h = w - 2, h - 2
                surf.blit(img, (rect.w // 2 - w // 2, rect.h // 2 - h // 2))
            if len(self._cache_btn) > 300:
                self._cache_btn.clear()
            self._cache_btn[key] = surf
        self.screen.blit(surf, rect)
        if action:
            self.zones.append((rect, action))
        return rect

    def _tri(self, cx, cy, size, direction, color):
        d = 1 if direction > 0 else -1
        pts = [(cx + d * size, cy), (cx - d * size * 0.6, cy - size * 0.8), (cx - d * size * 0.6, cy + size * 0.8)]
        pygame.draw.polygon(self.screen, color, pts)

    def _star(self, cx, cy, r, filled, color):
        pts = []
        for i in range(10):
            a = -math.pi / 2 + i * math.pi / 5
            rr = r if i % 2 == 0 else r * 0.45
            pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr))
        pygame.draw.polygon(self.screen, color, pts, 0 if filled else 2)

    def _draw_touch_controls(self):
        b = self.bike
        R = self._ctl_rects()
        active = {ctl for _, ctl in self.touches.values() if ctl}
        # suwak gazu
        g = R['gas']
        self._button(g, None, pressed=False)
        fill_h = int((g.height - 48) * b.throttle)
        pygame.draw.rect(self.screen, (90, 200, 90), (g.x + 18, g.bottom - 24 - fill_h, g.width - 36, fill_h))
        pygame.draw.rect(self.screen, (220, 220, 220), (g.x + 18, g.y + 24, g.width - 36, g.height - 48), 2)
        self._text("GAZ", self.f_med, C_TEXT, (g.centerx, g.y - 6), "midbottom")
        for pos, ctl in self.touches.values():
            if ctl == 'gas':
                y = clamp(pos[1], g.y + 24, g.bottom - 24)
                pygame.draw.line(self.screen, C_WARN, (g.x + 6, y), (g.right - 6, y), 5)
        self._button(R['brake'], "HAMULEC", pressed='brake' in active, color=(255, 150, 140))
        for name, label, d in (('back', "TYŁ", -1), ('fwd', "PRZÓD", 1)):
            r = self._button(R[name], None, pressed=name in active)
            col = (20, 20, 24) if name in active else C_TEXT
            self._tri(r.centerx, r.centery - 18, 20, d, col)
            self._text(label, self.f_med, col, (r.centerx, r.centery + 26), "center", shadow=name not in active)
        if 'clutch' in R:
            self._button(R['clutch'], "SPRZĘGŁO", pressed='clutch' in active, font=self.f_small,
                         color=(150, 200, 255))
        word = "TRYB" if b.V.electric else "BIEG"
        shift_now = (not b.V.electric and b.gear < b.V.n_gears and b.rpm > b.V.redline * 0.95
                     and int(pygame.time.get_ticks() / 150) % 2 == 0)
        self._button((190, H - 320, 140, 64), word + " +", 'gear_up', font=self.f_small,
                     color=C_WARN if shift_now else (255, 255, 255))
        self._button((190, H - 246, 140, 64), word + " -", 'gear_down', font=self.f_small)
        self._button((12, 180, 90, 52), "II", 'pause')
        self._button((110, 180, 90, 52), "R", 'restart')
        self._button((208, 180, 120, 52), "GARAŻ", 'garage', font=self.f_small)
        self._button((336, 180, 60, 52), "?", 'help')

    # tło ------------------------------------------------------------------------
    def _draw_background(self, cam_x=None, cam_y=None):
        scr = self.screen
        cam_px = (self.cam_x if cam_x is None else cam_x) * 100.0
        vy = (self.cam_y if cam_y is None else cam_y) * 12.0
        off = int(cam_px * 0.04) % BG_P
        scr.blit(self._bg_far, (0, int(200 + vy * 0.16)), (off, 0, W, 300))
        off = int(cam_px * 0.25) % BG_P
        ny = int(330 + vy * 0.4)
        scr.blit(self._bg_near, (0, ny), (off, 0, W, 160))
        if ny + 160 < H and self.state != 'ride' and self.state != 'crash':
            scr.fill((96, 140, 84), (0, ny + 160, W, H - ny - 160))

    def _draw_road(self):
        scr = self.screen
        s = self.scale
        x0 = self.cam_x - (self.anchor_x + 40) / s
        x1 = self.cam_x + (W - self.anchor_x + 40) / s
        step = 2 if self.mobile else 1                    # co ile oczek siatki (0.2 m) brać punkt
        g = Road.GRID
        i0 = int(math.floor(x0 / g)) - 1
        i1 = int(math.ceil(x1 / g)) + 1
        surf = []
        for i in range(i0, i1 + step, step):
            surf.append(self._p(i * g, self.road.grid_height(i)))
        dyf, dye = 0.9 * s, 0.12 * s
        far = [(px, py - dyf) for px, py in surf]
        edge = [(px, py + dye) for px, py in surf]
        pygame.draw.polygon(scr, (88, 130, 76), far + [(W + 50, far[-1][1] - 30), (-50, far[0][1] - 30)])
        pygame.draw.polygon(scr, (64, 64, 70), surf + far[::-1])
        pygame.draw.polygon(scr, (110, 82, 58), edge + [(W + 50, H + 50), (-50, H + 50)])
        pygame.draw.polygon(scr, (176, 176, 180), surf + edge[::-1])
        pygame.draw.lines(scr, (40, 40, 44), False, surf, 3)
        pygame.draw.lines(scr, (200, 200, 200), False, far, 2)
        dash = 4.0
        for k in range(int(math.floor(x0 / dash)), int(x1 / dash) + 1):
            a = k * dash
            b = a + dash * 0.5
            pa = self._p(a, self.road.height(a) + 0.42)
            pb = self._p(b, self.road.height(b) + 0.42)
            t = 0.06 * s
            pygame.draw.polygon(scr, (230, 230, 220), [pa, pb, (pb[0], pb[1] - t), (pa[0], pa[1] - t)])
        i = bisect_left(self.road.cx, x0 - 2)
        while i < len(self.road.cx) and self.road.cx[i] < x1 + 2:
            c, amp, sig, kind = self.road.bumps[i]
            pa = self._p(c - sig * 1.6, self.road.height(c - sig * 1.6))
            pb = self._p(c + sig * 1.6, self.road.height(c + sig * 1.6))
            col = (235, 200, 40) if kind == 1 else ((30, 30, 32) if kind == 2 else (50, 50, 56))
            pygame.draw.line(scr, col, pa, pb, 4 if kind == 1 else 3)
            if kind == 1:
                for j in range(3):
                    xx = c - sig + j * sig
                    yy = self.road.height(xx)
                    pygame.draw.line(scr, (20, 20, 20), self._p(xx - 0.08, yy + 0.02),
                                     self._p(xx + 0.08, yy + 0.02), 3)
            i += 1
        for k in range(int(math.floor(x0 / 25.0)), int(x1 / 25.0) + 1):
            xx = k * 25.0
            yy = self.road.height(xx)
            bx, by = self._p(xx, yy + 0.9)
            tx, ty = self._p(xx, yy + 1.9)
            pygame.draw.line(scr, (235, 235, 235), (bx, by), (tx, ty), max(2, int(s * 0.05)))
            pygame.draw.line(scr, (210, 40, 40), (tx, ty + s * 0.1), (tx, ty + s * 0.25), max(2, int(s * 0.05)))
            if k % 4 == 0 and k >= 0:
                lbl = self.f_small.render("%d m" % int(xx), True, (20, 20, 20))
                rect = lbl.get_rect(midbottom=(tx, ty - 2))
                pygame.draw.rect(scr, (240, 240, 240), rect.inflate(10, 6))
                pygame.draw.rect(scr, (40, 40, 40), rect.inflate(10, 6), 1)
                scr.blit(lbl, rect)
        if x0 < 0 < x1:
            yy = self.road.height(0)
            pygame.draw.line(scr, C_WHITE, self._p(0, yy), self._p(0, yy + 0.9), 5)

    def _p(self, x, y):
        return ((x - self.cam_x) * self.scale + self.anchor_x + self._shk[0],
                H * 0.70 - (y - self.cam_y) * self.scale + self._shk[1])

    # pojazdy ------------------------------------------------------------------
    def _world_transform(self, ox, oy, ang):
        c, s = math.cos(ang), math.sin(ang)
        sc = self.scale
        cx0 = self.anchor_x + self._shk[0]
        cy0 = H * 0.70 + self._shk[1]
        cam_x, cam_y = self.cam_x, self.cam_y
        self._sc = sc

        def T(p):
            wx = ox + p[0] * c - p[1] * s
            wy = oy + p[0] * s + p[1] * c
            return ((wx - cam_x) * sc + cx0, cy0 - (wy - cam_y) * sc)
        return T

    def _limb(self, a, b, w, col):
        """Gruba linia z zaokrąglonymi końcami (pygame źle rysuje grube skośne linie)."""
        dx, dy = b[0] - a[0], b[1] - a[1]
        ln = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / ln * w / 2, dx / ln * w / 2
        pygame.draw.polygon(self.screen, col, [(a[0] + nx, a[1] + ny), (b[0] + nx, b[1] + ny),
                                               (b[0] - nx, b[1] - ny), (a[0] - nx, a[1] - ny)])
        pygame.draw.circle(self.screen, col, a, w / 2)
        pygame.draw.circle(self.screen, col, b, w / 2)

    def _poly(self, T, pts, col, outline=None):
        sp = [T(p) for p in pts]
        pygame.draw.polygon(self.screen, col, sp)
        if outline:
            pygame.draw.polygon(self.screen, outline, sp, 2)

    def _draw_wheel(self, T, center, r_m, rot_angle, ang, kind, rim_col):
        scr = self.screen
        cx, cy = T(center)
        r = r_m * self._sc
        a0 = -rot_angle + ang
        if kind == 'sport':
            pygame.draw.circle(scr, C_TIRE, (cx, cy), r)
            pygame.draw.circle(scr, (55, 55, 60), (cx, cy), r * 0.80)
            pygame.draw.circle(scr, rim_col, (cx, cy), r * 0.74, max(2, int(r * 0.07)))
            pygame.draw.circle(scr, C_CHROME, (cx, cy), r * 0.45, max(2, int(r * 0.09)))
            for i in range(5):
                a = a0 + i * 2 * math.pi / 5
                p1 = (cx + math.cos(a) * r * 0.12, cy - math.sin(a) * r * 0.12)
                for da in (0.18, -0.18):
                    p2 = (cx + math.cos(a + da) * r * 0.72, cy - math.sin(a + da) * r * 0.72)
                    pygame.draw.line(scr, rim_col, p1, p2, max(2, int(r * 0.07)))
            pygame.draw.circle(scr, C_DARK2, (cx, cy), r * 0.13)
        elif kind == 'wire':
            pygame.draw.circle(scr, C_TIRE, (cx, cy), r * 0.94)
            for i in range(20):                                   # klocki opony terenowej
                a = a0 + i * 2 * math.pi / 20
                pygame.draw.circle(scr, C_TIRE, (cx + math.cos(a) * r * 0.93, cy - math.sin(a) * r * 0.93),
                                   max(1.5, r * 0.08))
            pygame.draw.circle(scr, (70, 72, 78), (cx, cy), r * 0.76)
            pygame.draw.circle(scr, rim_col, (cx, cy), r * 0.76, max(2, int(r * 0.06)))
            for i in range(14):
                a = a0 + i * 2 * math.pi / 14
                pygame.draw.line(scr, (175, 178, 186), (cx + math.cos(a) * r * 0.12, cy - math.sin(a) * r * 0.12),
                                 (cx + math.cos(a + 0.3) * r * 0.72, cy - math.sin(a + 0.3) * r * 0.72), 1)
            pygame.draw.circle(scr, C_CHROME, (cx, cy), r * 0.34, max(1, int(r * 0.05)))
            pygame.draw.circle(scr, C_DARK2, (cx, cy), r * 0.13)
        else:                                                    # silnik w piaście (hulajnoga)
            pygame.draw.circle(scr, C_TIRE, (cx, cy), r)
            if kind == 'hubk':                                   # terenowe opony
                for i in range(14):
                    a = a0 + i * 2 * math.pi / 14
                    pygame.draw.circle(scr, C_TIRE, (cx + math.cos(a) * r, cy - math.sin(a) * r), max(1.5, r * 0.11))
            pygame.draw.circle(scr, rim_col, (cx, cy), r * 0.74)
            pygame.draw.circle(scr, (120, 122, 130), (cx, cy), r * 0.52)
            for i in range(5):
                a = a0 + i * 2 * math.pi / 5
                pygame.draw.circle(scr, (60, 60, 66), (cx + math.cos(a) * r * 0.32, cy - math.sin(a) * r * 0.32),
                                   max(1.5, r * 0.08))
            pygame.draw.circle(scr, (200, 70, 40), (cx, cy), r * 0.62, max(1, int(r * 0.05)))
            pygame.draw.circle(scr, C_DARK, (cx, cy), r * 0.12)

    def _draw_vehicle(self, T, V, ang, rear_rot, front_rot, pose=None, brake=0.0):
        if V.style == 'sport':
            self._draw_sport(T, V, ang, rear_rot, front_rot, brake)
        elif V.style == 'dirt':
            self._draw_dirt(T, V, ang, rear_rot, front_rot, brake)
        else:
            self._draw_scooter(T, V, ang, rear_rot, front_rot, brake)
        if pose is not None:
            self._draw_rider(T, pose, V.colors['accent'])

    def _brake_glow(self, T, V, brake):
        if brake > 0.3:
            pygame.draw.circle(self.screen, (255, 120, 40), T((0, 0)), V.wheel_r * self._sc * 0.42, 2)

    def _taillight(self, T, p, brake):
        pygame.draw.circle(self.screen, (255, 40, 30) if brake > 0.05 else (150, 20, 20), T(p),
                           max(2, self._sc * (0.035 if brake > 0.05 else 0.025)))

    def _draw_sport(self, T, V, ang, rear_rot, front_rot, brake):
        scr = self.screen
        sc = self._sc
        col, hi = V.colors['main'], V.colors['hi']
        front = (V.wheelbase, 0.0)
        self._draw_wheel(T, (0.0, 0.0), V.wheel_r, rear_rot, ang, 'sport', V.colors['rim'])
        self._brake_glow(T, V, brake)
        self._poly(T, SP_SWINGARM, C_DARK2, C_DARK)
        self._poly(T, SP_EXHAUST, C_CHROME, (110, 110, 118))
        self._poly(T, SP_ENGINE, C_DARK, (20, 20, 22))
        self._draw_wheel(T, front, V.wheel_r, front_rot, ang, 'sport', V.colors['rim'])
        self._limb(T(front), T(SP_FORK_TOP), max(3, sc * 0.07), C_CHROME)
        self._limb(T((1.32, 0.38)), T(SP_FORK_TOP), max(3, sc * 0.08), C_GOLD)
        self._poly(T, SP_FENDER, col, C_DARK)
        self._poly(T, SP_TAIL, col, C_DARK)
        self._poly(T, SP_SEAT, (30, 30, 30))
        self._poly(T, SP_FAIR_LOW, col, C_DARK)
        self._poly(T, SP_TANK, col, C_DARK)
        self._limb(T((0.62, 0.70)), T((0.92, 0.76)), max(2, sc * 0.04), hi)
        self._poly(T, SP_FAIRING, col, C_DARK)
        self._poly(T, SP_SCREEN, (120, 150, 180), (60, 70, 90))
        pygame.draw.circle(scr, (255, 245, 200), T((1.30, 0.74)), max(2, sc * 0.035))
        self._limb(T((1.03, 0.60)), T((1.24, 0.70)), max(2, sc * 0.03), C_WHITE)
        self._limb(T((0.20, 0.55)), T((-0.30, 0.50)), max(2, sc * 0.025), C_WHITE)
        self._taillight(T, (-0.40, 0.445), brake)

    def _draw_dirt(self, T0, V, ang, rear_rot, front_rot, brake):
        scr = self.screen
        s = V.scale
        sc = self._sc * s
        cl = V.colors

        def T(p):
            return T0((p[0] * s, p[1] * s))
        front = (1.26, 0.0)
        self._draw_wheel(T0, (0.0, 0.0), V.wheel_r, rear_rot, ang, 'wire', cl['rim'])
        self._brake_glow(T0, V, brake)
        self._poly(T, DT_SWINGARM, cl['frame'], C_DARK)
        self._limb(T((0.22, 0.10)), T((0.56, 0.48)), max(2, sc * 0.05), (230, 60, 40))     # amortyzator
        for i in range(5):
            a = 0.25 + i * 0.12
            p = (0.22 + (0.56 - 0.22) * a, 0.10 + (0.48 - 0.10) * a)
            self._limb(T((p[0] - 0.04, p[1] + 0.03)), T((p[0] + 0.04, p[1] - 0.03)), max(1, sc * 0.018),
                       (250, 210, 60))
        if V.electric:
            self._poly(T, DT_BATTERY, cl['battery'], (10, 10, 12))
            self._limb(T((0.70, 0.40)), T((0.90, 0.46)), max(2, sc * 0.03), cl['hi'])
            pygame.draw.circle(scr, (70, 72, 80), T((0.64, 0.13)), max(3, sc * 0.085))
            pygame.draw.circle(scr, (30, 30, 34), T((0.64, 0.13)), max(2, sc * 0.04))
        else:
            self._poly(T, DT_ENGINE, (110, 112, 120), (50, 50, 56))
            for i in range(4):
                y = 0.30 + i * 0.045
                self._limb(T((0.62, y)), T((0.86, y + 0.02)), max(1, sc * 0.012), (70, 72, 80))
            for a, b in zip(DT_PIPE, DT_PIPE[1:]):
                self._limb(T(a), T(b), max(2, sc * 0.045), C_CHROME)
            self._poly(T, DT_SILENCER, (60, 62, 70), (20, 20, 24))
        for a, b in DT_FRAME:
            self._limb(T(a), T(b), max(2, sc * 0.045), cl['frame'])
        self._draw_wheel(T0, (V.wheelbase, 0.0), V.wheel_r, front_rot, ang, 'wire', cl['rim'])
        self._limb(T(front), T(DT_FORK_MID), max(3, sc * 0.065), C_GOLD)
        self._limb(T(DT_FORK_MID), T(DT_FORK_TOP), max(3, sc * 0.075), (30, 30, 34))
        self._poly(T, DT_FENDER, cl['main'], C_DARK)
        self._poly(T, DT_REAR, cl['main'], C_DARK)
        self._poly(T, DT_SEAT, (26, 26, 28))
        self._poly(T, DT_SHROUD, cl['main'], C_DARK)
        self._limb(T((0.68, 0.60)), T((0.98, 0.68)), max(2, sc * 0.03), cl['hi'])
        self._poly(T, DT_PLATE, cl['main'], C_DARK)
        pygame.draw.circle(scr, (255, 245, 200), T((1.10, 0.74)), max(2, sc * 0.04))
        self._limb(T(DT_FORK_TOP), T(DT_GRIP), max(2, sc * 0.035), (30, 30, 34))
        self._taillight(T, (-0.43, 0.615), brake)

    def _draw_scooter(self, T0, V, ang, rear_rot, front_rot, brake):
        scr = self.screen
        s = V.scale
        sc = self._sc * s
        cl = V.colors

        def T(p):
            return T0((p[0] * s, p[1] * s))
        front = (0.98, 0.0)
        wk = 'hubk' if V.knobby else 'hub'
        self._draw_wheel(T0, (0.0, 0.0), V.wheel_r, rear_rot, ang, wk, cl['rim'])
        self._brake_glow(T0, V, brake)
        self._limb(T((0.0, 0.0)), T((0.14, 0.03)), max(2, sc * 0.05), cl['stem'])
        self._limb(T((0.04, 0.09)), T((0.14, 0.03)), max(2, sc * 0.035), cl['hi'])          # sprężyna
        self._poly(T, SC_REAR_FENDER, cl['main'], (10, 10, 12))
        self._poly(T, SC_DECK, cl['main'], (10, 10, 12))
        self._poly(T, SC_TAPE, (60, 60, 64))
        self._limb(T((0.12, 0.02)), T((0.74, 0.02)), max(1, sc * 0.015), cl['hi'])
        self._poly(T, SC_NECK, cl['stem'], (20, 20, 24))
        self._draw_wheel(T0, (V.wheelbase, 0.0), V.wheel_r, front_rot, ang, wk, cl['rim'])
        self._limb(T(front), T(SC_STEM_BOT), max(3, sc * 0.05), cl['stem'])
        self._limb(T((0.97, 0.06)), T((0.94, 0.20)), max(2, sc * 0.035), cl['hi'])           # sprężyna
        self._poly(T, SC_FRONT_FENDER, cl['main'], (10, 10, 12))
        self._limb(T(SC_STEM_BOT), T(SC_STEM_TOP), max(3, sc * 0.055), cl['stem'])
        self._limb(T((0.92, 0.40)), T((0.88, 0.75)), max(1, sc * 0.015), cl['hi'])
        self._poly(T, SC_DISPLAY, (20, 20, 24))
        pygame.draw.circle(scr, (255, 245, 200), T((0.91, 0.56)), max(2, sc * 0.03))
        self._taillight(T, (-0.17, 0.10), brake)

    def _draw_rider(self, T, P, accent):
        scr = self.screen
        sc = self._sc
        hip, sh, head = T(P['hip']), T(P['sh']), T(P['head'])
        knee, foot, toe = T(P['knee']), T(P['foot']), T(P['toe'])
        elbow, hand = T(P['elbow']), T(P['hand'])
        if 'knee2' in P:                                   # dalsza noga (na hulajnodze)
            k2, f2, t2 = T(P['knee2']), T(P['foot2']), T(P['toe2'])
            self._limb(hip, k2, sc * 0.14, C_SUIT_FAR)
            self._limb(k2, f2, sc * 0.115, C_SUIT_FAR)
            self._limb(f2, t2, sc * 0.08, (8, 8, 10))
        self._limb(hip, knee, sc * 0.15, C_SUIT)
        self._limb(knee, foot, sc * 0.12, C_SUIT)
        self._limb(foot, toe, sc * 0.08, (12, 12, 14))
        pygame.draw.circle(scr, accent, knee, sc * 0.045)
        self._limb(hip, sh, sc * 0.25, C_SUIT)
        dx, dy = sh[0] - hip[0], sh[1] - hip[1]
        ln = math.hypot(dx, dy) or 1.0
        bx, by = dy / ln * sc * 0.09, -dx / ln * sc * 0.09     # w stronę pleców
        o, f = T((0, 0)), T((1, 0))
        if bx * (o[0] - f[0]) + by * (o[1] - f[1]) < 0:
            bx, by = -bx, -by
        self._limb((hip[0] + dx * 0.35 + bx, hip[1] + dy * 0.35 + by),
                   (hip[0] + dx * 0.85 + bx, hip[1] + dy * 0.85 + by), sc * 0.07, accent)
        hr = sc * 0.15
        self._limb(sh, head, sc * 0.08, C_SUIT)
        pygame.draw.circle(scr, C_HELMET, head, hr)
        f1 = T((P['head'][0] + P['face'][0], P['head'][1] + P['face'][1]))
        fa = math.atan2(f1[1] - head[1], f1[0] - head[0])
        vis = [(head[0] + math.cos(fa + a) * hr * 0.97, head[1] + math.sin(fa + a) * hr * 0.97)
               for a in [i * 0.12 - 0.85 for i in range(13)]]
        vis.append((head[0] + math.cos(fa) * hr * 0.25, head[1] + math.sin(fa) * hr * 0.25))
        pygame.draw.polygon(scr, C_VISOR, vis)
        stripe = [(head[0] + math.cos(fa + math.pi + a) * hr * 0.98, head[1] + math.sin(fa + math.pi + a) * hr * 0.98)
                  for a in [i * 0.15 - 0.9 for i in range(13)]]
        pygame.draw.lines(scr, accent, False, stripe, max(2, int(hr * 0.22)))
        self._limb(sh, elbow, sc * 0.10, C_SUIT)
        self._limb(elbow, hand, sc * 0.085, C_SUIT)
        pygame.draw.circle(scr, (12, 12, 14), hand, sc * 0.05)

    def _draw_bike_ride(self):
        b = self.bike
        V = b.V
        ax, ay = b.axle
        gy = self.road.height(b.x + V.wheelbase / 2)
        l, t = self._p(b.x - 0.4, gy)
        r, _ = self._p(b.x + V.wheelbase + 0.4, gy)
        wsh = max(1, int(r - l))
        sh = self._cache_shadow.get(wsh)
        if sh is None:
            sh = pygame.Surface((wsh, 10), pygame.SRCALPHA)
            pygame.draw.ellipse(sh, (0, 0, 0, 70), sh.get_rect())
            if len(self._cache_shadow) > 200:
                self._cache_shadow.clear()
            self._cache_shadow[wsh] = sh
        self.screen.blit(sh, (l, t - 5))
        T = self._world_transform(ax, ay, b.psi)
        self._draw_vehicle(T, V, b.psi, b.rear_rot, b.front_rot, rider_pose(V, b.lean, b.psi - b.base_prev),
                           brake=b.brake_eff)

    def _draw_wreck(self):
        wr = self.wreck
        ax, ay = wr.bike_point((0.0, 0.0))
        T = self._world_transform(ax, ay, wr.ang)
        self._draw_vehicle(T, wr.V, wr.ang, wr.rear_rot, wr.front_rot)
        RT = self._world_transform(wr.rx, wr.ry, wr.rang)
        self._draw_rider(RT, tumble_pose(wr.t), wr.V.colors['accent'])

    def _draw_particles(self, back):
        scr = self.screen
        for p in self.particles:
            kind = p[6]
            if back != (kind == 'smoke'):
                continue
            sx, sy = self._p(p[0], p[1])
            life = p[4] / p[5]
            if kind == 'smoke':
                rad = int((1.0 - life) * self.scale * 0.6 + 6)
                key = (rad // 3, int(life * 8))
                surf = self._smoke.get(key)
                if surf is None:                      # bufor gotowych kółek dymu (szybciej na telefonie)
                    r2 = key[0] * 3 + 1
                    surf = pygame.Surface((r2 * 2, r2 * 2), pygame.SRCALPHA)
                    pygame.draw.circle(surf, (210, 210, 210, int(110 * (key[1] + 0.5) / 8)), (r2, r2), r2)
                    if len(self._smoke) < 800:
                        self._smoke[key] = surf
                scr.blit(surf, (sx - surf.get_width() / 2, sy - surf.get_height() / 2))
            elif kind == 'dust':
                pygame.draw.circle(scr, (150, 130, 100), (sx, sy), max(1, int(4 * life + 1)))
            else:
                ex, ey = self._p(p[0] - p[2] * 0.02, p[1] - p[3] * 0.02)
                pygame.draw.line(scr, (255, 220, 90), (sx, sy), (ex, ey), 2)

    # HUD ------------------------------------------------------------------------
    def _text_surf(self, txt, font, col, shadow):
        key = (txt, id(font), col, shadow)
        surf = self._cache_txt.get(key)
        if surf is None:
            img = font.render(txt, True, col)
            if shadow:
                sh = font.render(txt, True, C_SHADOW)
                surf = pygame.Surface((img.get_width() + 2, img.get_height() + 2), pygame.SRCALPHA)
                surf.blit(sh, (2, 2))
                surf.blit(img, (0, 0))
            else:
                surf = img
            if len(self._cache_txt) > 800:
                self._cache_txt.clear()
            self._cache_txt[key] = surf
        return surf

    def _text(self, txt, font, col, pos, anchor="topleft", shadow=True):
        surf = self._text_surf(txt, font, col, shadow)
        w, h = surf.get_size()
        rect = pygame.Rect(0, 0, w - 2, h - 2) if shadow else pygame.Rect(0, 0, w, h)
        setattr(rect, anchor, pos)
        self.screen.blit(surf, rect.topleft)
        return rect

    def _center_text(self, txt, font, col, y):
        self._text(txt, font, col, (W / 2, y), "center")

    def _panel(self, rect, alpha=150):
        key = (int(rect[2]), int(rect[3]), alpha)
        surf = self._cache_panel.get(key)
        if surf is None:                                  # przezroczystość całej powierzchni = szybki blit
            surf = pygame.Surface(key[:2]).convert()
            surf.fill((10, 12, 18))
            surf.set_alpha(alpha, getattr(pygame, "RLEACCEL", 0))
            self._cache_panel[key] = surf
        self.screen.blit(surf, (rect[0], rect[1]))

    def _bar(self, x, y, w, h, frac, col, label, value_txt=None):
        scr = self.screen
        pygame.draw.rect(scr, (40, 40, 48), (x, y, w, h))
        pygame.draw.rect(scr, col, (x, y, int(w * clamp(frac, 0, 1)), h))
        pygame.draw.rect(scr, (200, 200, 200), (x, y, w, h), 1)
        self._text(label, self.f_small, C_TEXT, (x - 8, y + h / 2), "midright", shadow=False)
        if value_txt:
            self._text(value_txt, self.f_small, C_TEXT, (x + w + 8, y + h / 2), "midleft", shadow=False)

    def _draw_hud(self):
        scr = self.screen
        b = self.bike
        V = b.V
        riding = self.state == 'ride'
        mob = self.mobile

        # --- lewy panel: prędkość, obroty/moc (+ sterowanie na PC) ---
        self._panel((12, 12, 430, 160 if mob else 232))
        kmh = b.v * 3.6 if riding else max(0.0, self.wreck.vx) * 3.6
        self._text("%d" % round(kmh), self.f_huge, C_TEXT, (170, 6), "topright")
        self._text("km/h", self.f_med, C_TEXT, (178, 52))
        if V.electric:
            self._text("TRYB", self.f_small, C_TEXT, (262, 20))
            self._text(V.modes[b.mode][0], self.f_big, C_WARN, (262, 38))
        else:
            self._text("BIEG", self.f_small, C_TEXT, (262, 20))
            if self.diff['auto_shift']:
                self._text("AUTO", self.f_small, (120, 200, 250), (262, 40), shadow=False)
            self._text(str(b.gear), self.f_huge, C_WARN if b.shift_t > 0 else C_TEXT, (315, 6))
        x, y, w, h = 26, 96, 352, 18
        pygame.draw.rect(scr, (40, 40, 48), (x, y, w, h))
        if V.electric:
            frac = b.power / V.gauge
            col = C_BAD if b.limiter else (90, 170, 250)
            pygame.draw.rect(scr, col, (x, y + 3, int(w * clamp(frac, 0, 1)), h - 6))
        else:
            rpm = b.rpm
            rl = int(w * V.redline / V.gauge)
            pygame.draw.rect(scr, (110, 30, 30), (x + rl, y, w - rl, h))
            col = C_BAD if b.limiter else (C_WARN if rpm > V.redline * 0.92 else C_GOOD)
            pygame.draw.rect(scr, col, (x, y + 3, int(w * clamp(rpm / V.gauge, 0, 1)), h - 6))
        pygame.draw.rect(scr, (200, 200, 200), (x, y, w, h), 1)
        scr.blit(self._scale_surface(V, w), (x - 20, y + h))
        status = []
        if (riding and not V.electric and not self.diff['auto_shift'] and b.gear < V.n_gears
                and b.rpm > V.redline * 0.95 and int(pygame.time.get_ticks() / 150) % 2 == 0):
            status.append(("ZMIEŃ BIEG!", C_WARN))
        if riding and b.limiter:
            status.append(("MAKS. PRĘDKOŚĆ" if V.electric else "LIMITER", C_BAD))
        if riding and b.stalled:
            status.append(("ZGASŁ", C_BAD))
        if riding and b.wheelspin:
            status.append(("BUKSUJE", C_WARN))
        if riding and b.clutch_slip and not V.electric:
            status.append(("SPRZĘGŁO ŚLIZGA", C_WARN))
        if riding and b.wc_active:
            status.append(("KONTROLA WHEELIE", (120, 200, 250)))
        if V.electric and b.regen and riding:
            status.append(("REKUPERACJA", (120, 200, 250)))
        sx = 26
        for t, c in status:
            r = self._text(t, self.f_small, c, (sx, 140), shadow=False)
            sx = r.right + 12
        if not status:
            info = ("MOC %4.1f kW" % (b.power / 1000)) if V.electric else ("%5d obr/min" % int(b.rpm))
            self._text(info, self.f_small, (190, 190, 190), (26, 140), shadow=False)
        if not mob:
            bx = 130
            self._bar(bx, 166, 200, 12, b.throttle, (90, 200, 90), "GAZ", "%3d%%" % (b.throttle * 100))
            self._bar(bx, 184, 200, 12, b.brake, (230, 80, 60), "HAMULEC")
            if V.electric:
                self._text("SPRZĘGŁO", self.f_small, C_TEXT, (bx - 8, 208), "midright", shadow=False)
                self._text("brak – napęd elektryczny", self.f_small, (150, 150, 150), (bx, 208), "midleft",
                           shadow=False)
            else:
                self._bar(bx, 202, 200, 12, 1.0 - b.clutch, (90, 160, 230), "SPRZĘGŁO")
            yb = 220
            pygame.draw.rect(scr, (40, 40, 48), (bx, yb, 200, 12))
            cxm = bx + 100
            lx = cxm + int(b.lean * 100)
            pygame.draw.rect(scr, (220, 200, 90), (min(cxm, lx), yb, abs(lx - cxm) + 1, 12))
            pygame.draw.line(scr, (200, 200, 200), (cxm, yb - 2), (cxm, yb + 14), 1)
            pygame.draw.rect(scr, (200, 200, 200), (bx, yb, 200, 12), 1)
            self._text("BALANS", self.f_small, C_TEXT, (bx - 8, yb + 6), "midright", shadow=False)
            self._text("tył / przód", self.f_small, (170, 170, 170), (bx + 208, yb + 6), "midleft", shadow=False)

        # --- prawy panel: wyniki ---
        px = W - 332
        self._panel((px, 12, 320, 172))
        self._text(V.name, self.f_small, C_TEXT, (px + 14, 18), shadow=False)
        self._text("POZIOM: " + self.diff['name'], self.f_small, C_WARN, (px + 14, 38), shadow=False)
        cur = b.w_dist if (riding and b.in_wheelie) else 0.0
        self._text("WHEELIE", self.f_small, C_TEXT, (px + 14, 62), shadow=False)
        self._text("%.1f m" % cur, self.f_big, C_GOOD if cur > 0 else (150, 150, 150), (px + 14, 78), shadow=False)
        self._text("%.1f s" % (b.w_time if riding and b.in_wheelie else 0.0), self.f_med, C_TEXT,
                   (px + 306, 92), "topright", shadow=False)
        best = self.best.get(self.best_key(), 0.0)
        self._text("Rekord:  %.1f m" % best, self.f_small, C_TEXT, (px + 14, 132), shadow=False)
        if self.last:
            self._text("Ostatnie: %.1f m, maks. %d°" % (self.last[0], self.last[2]), self.f_small,
                       (190, 190, 190), (px + 14, 154), shadow=False)

        # --- wiatr ---
        ws = self.wind.speed
        wx = W / 2
        self._panel((wx - 110, 12, 220, 46))
        self._text("WIATR %4.1f m/s" % abs(ws), self.f_small, C_TEXT, (wx, 22), "midtop", shadow=False)
        self._text("%d FPS" % round(self.clock.get_fps()), self.f_small, (150, 150, 160), (wx + 104, 54),
                   "bottomright", shadow=False)
        alen = clamp(abs(ws) * 7, 4, 90)
        dirn = 1 if ws > 0 else -1
        ay = 48
        col = C_BAD if ws < -6 else (C_WARN if ws < -3 else (170, 200, 230))
        pygame.draw.line(scr, col, (wx - dirn * alen / 2, ay), (wx + dirn * alen / 2, ay), 3)
        tip = wx + dirn * alen / 2
        pygame.draw.polygon(scr, col, [(tip, ay), (tip - dirn * 8, ay - 5), (tip - dirn * 8, ay + 5)])

        self._draw_angle_gauge()

        if riding and b.in_wheelie and b.w_dist > 1.0:
            self._text("%.1f m" % b.w_dist, self.f_big, C_TEXT, (W / 2, 96), "midtop")

        if self.mouse_mode and riding and not mob:
            my = self._mouse_pos(pygame.mouse.get_pos())[1]
            pygame.draw.rect(scr, (40, 40, 48), (W - 30, H * 0.15, 10, H * 0.7))
            fy = H * 0.85 - b.throttle * H * 0.7
            pygame.draw.rect(scr, (90, 200, 90), (W - 30, fy, 10, H * 0.85 - fy))
            pygame.draw.line(scr, C_WARN, (W - 40, my), (W - 14, my), 2)

        y = 150
        for text, col, ttl, tot in self.msgs:
            a = clamp(ttl / 0.4, 0.0, 1.0)
            img = self._text_surf(text, self.f_med, col, True)
            img.set_alpha(int(255 * a))
            scr.blit(img, img.get_rect(midtop=(W / 2, y)))
            img.set_alpha(255)
            y += 32

        if not mob:
            a = self.ar
            if V.electric:
                hint = ("W/%s gaz   S/%s hamulec   A/D balans   E/Q tryb mocy   G garaż   M mysz   R restart   "
                        "H pomoc" % (a['up'], a['down']))
            else:
                hint = ("W/%s gaz   S/%s hamulec   A/D balans   SPACJA sprzęgło   E/Q bieg   G garaż   M mysz   "
                        "R restart   H pomoc" % (a['up'], a['down']))
            self._text(hint, self.f_small, (230, 230, 230), (W / 2, H - 14), "midbottom")

    def _scale_surface(self, V, w):
        """Podziałka obrotomierza / mocy – rysowana raz dla danego pojazdu."""
        key = ('scale', V.vid, w)
        surf = self._cache_gauge.get(key)
        if surf is None:
            surf = pygame.Surface((w + 60, 30), pygame.SRCALPHA)     # wygładzony tekst => prawdziwa alfa
            saved, self.screen = self.screen, surf
            x, y = 20, 0
            if V.electric:
                for kk in range(0, 11):
                    tx = x + int(w * kk / 10)
                    pygame.draw.line(surf, (220, 220, 220), (tx, y), (tx, y + (6 if kk % 5 == 0 else 3)), 1)
                self._text("0", self.f_small, (200, 200, 200), (x, y + 6), "midtop", shadow=False)
                self._text("%.1f kW" % (V.gauge / 1000), self.f_small, (200, 200, 200), (x + w, y + 6), "midtop",
                           shadow=False)
            else:
                for kk in range(0, int(V.gauge / 1000) + 1):
                    tx = x + int(w * kk * 1000 / V.gauge)
                    pygame.draw.line(surf, (220, 220, 220), (tx, y), (tx, y + (6 if kk % 2 == 0 else 3)), 1)
                    if kk % 2 == 0:
                        self._text(str(kk), self.f_small, (200, 200, 200), (tx, y + 6), "midtop", shadow=False)
            self.screen = saved
            self._cache_gauge[key] = surf
        return surf

    def _draw_angle_gauge(self):
        scr = self.screen
        b = self.bike
        V = b.V
        if self.mobile:
            cx, cy, R = W / 2 + 30, H - 16, 92
        else:
            cx, cy, R = W - 150, H - 46, 118
        self._panel((cx - R - 20, cy - R - 20, R + 160, R + 34))
        beta = math.atan(self.road.sample(b.x)[1])
        if self.state == 'ride':
            ang = b.psi - beta
        else:
            ang = (self.wreck.ang + math.pi) % (2 * math.pi) - math.pi

        def arc_pts(a0, a1, r0, r1, ox, oy):
            pts = []
            n = max(2, int(abs(a1 - a0) / 0.03))
            for i in range(n + 1):
                a = a0 + (a1 - a0) * i / n
                pts.append((ox + math.cos(a) * r1, oy - math.sin(a) * r1))
            for i in range(n, -1, -1):
                a = a0 + (a1 - a0) * i / n
                pts.append((ox + math.cos(a) * r0, oy - math.sin(a) * r0))
            return pts
        loop = V.max_angle
        key = (R, round(math.degrees(loop)))
        base = self._cache_gauge.get(key)
        if base is None:                                   # łuk, strefa przewrotki i podziałka – rysowane raz
            base = pygame.Surface((2 * R + 4, R + 4)).convert()
            base.fill(CKEY)
            o = R + 2
            pygame.draw.polygon(base, (60, 60, 70), arc_pts(0, math.radians(100), R - 16, R, o, o))
            pygame.draw.polygon(base, (170, 40, 40), arc_pts(loop - math.radians(6), math.radians(100), R - 16, R, o, o))
            for deg in range(0, 91, 15):
                a = math.radians(deg)
                pygame.draw.line(base, (210, 210, 210), (o + math.cos(a) * (R - 22), o - math.sin(a) * (R - 22)),
                                 (o + math.cos(a) * (R - 16), o - math.sin(a) * (R - 16)), 2)
            base.set_colorkey(CKEY, getattr(pygame, "RLEACCEL", 0))
            self._cache_gauge[key] = base
        scr.blit(base, (cx - R - 2, cy - R - 2))
        if self.diff['show_bp']:
            bp = b.balance_angle() - beta
            pygame.draw.polygon(scr, (60, 170, 80),
                                arc_pts(bp - math.radians(4), bp + math.radians(4), R - 16, R, cx, cy))
        a = clamp(ang, -0.1, math.radians(110))
        col = C_BAD if ang > loop - math.radians(6) else C_TEXT
        pygame.draw.line(scr, col, (cx, cy), (cx + math.cos(a) * (R - 6), cy - math.sin(a) * (R - 6)), 4)
        pygame.draw.circle(scr, col, (cx, cy), 6)
        self._text("%d°" % round(math.degrees(ang)) if self.state == 'ride' else "---", self.f_big, col,
                   (cx - R - 6, cy - R + 10), "topleft")
        self._text("KĄT", self.f_small, C_TEXT, (cx - R - 6, cy - R - 10), "topleft", shadow=False)
        if self.state == 'ride':
            self._text("%+4d°/s" % round(math.degrees(b.omega)), self.f_small, (200, 200, 200),
                       (cx - R - 6, cy - 22), "topleft", shadow=False)

    def _draw_crash_panel(self):
        r = pygame.Rect(0, 0, 680, 270)
        r.center = (W / 2, H * 0.42)
        self._panel(r, 200)
        self._text("GLEBA!", self.f_huge, C_BAD, (r.centerx, r.top + 10), "midtop")
        self._text(self.crash_reason, self.f_med, C_TEXT, (r.centerx, r.top + 94), "midtop")
        if self.crash_wheelie >= 2.0:
            txt = "Wheelie przed glebą: %.1f m – nie liczy się, trzeba wylądować!" % self.crash_wheelie
        else:
            txt = "Najlepsze wheelie w tym przejeździe: %.1f m" % self.run_best
        self._text(txt, self.f_small, C_TEXT, (r.centerx, r.top + 134), "midtop")
        self._text("Rekord (%s, %s): %.1f m" % (self.veh.name, self.diff['name'],
                                                 self.best.get(self.best_key(), 0.0)), self.f_small,
                   C_WARN, (r.centerx, r.top + 158), "midtop")
        again = "JESZCZE RAZ" if self.mobile else "JESZCZE RAZ (R)"
        gar = "GARAŻ" if self.mobile else "GARAŻ (G)"
        self._button((r.centerx - 290, r.bottom - 76, 300, 60), again, 'restart', color=C_GOOD)
        self._button((r.centerx + 30, r.bottom - 76, 260, 60), gar, 'garage')

    # ekran startowy: TELEFON czy PC ---------------------------------------------------
    def _draw_choose(self):
        scr = self.screen
        scr.blit(self.sky, (0, 0))
        self._shk = (0.0, 0.0)
        self._draw_background(cam_x=self.garage_t * 0.8, cam_y=0.0)
        pygame.draw.rect(scr, (64, 64, 70), (0, 560, W, H - 560))
        pygame.draw.rect(scr, (176, 176, 180), (0, 560, W, 8))
        for k in range(-1, 8):                                      # przesuwające się pasy na drodze
            x = k * 200 - (self.garage_t * 300) % 200
            pygame.draw.rect(scr, (230, 230, 220), (x, 630, 100, 8))
        self._panel((0, 0, W, H), 110)
        self._text("WHEELIE SIMULATOR 2D", self.f_huge, C_WARN, (W / 2, 28), "midtop")
        self._text("Na czym grasz?", self.f_med, C_TEXT, (W / 2, 130), "midtop")
        cards = ((0, pygame.Rect(150, 190, 440, 330), 'choose_phone', "TELEFON",
                  ["sterowanie dotykiem:", "suwak gazu i przyciski na ekranie", "trzymaj telefon poziomo"]),
                 (1, pygame.Rect(690, 190, 440, 330), 'choose_pc', "PC",
                  ["klawiatura: W A S D, spacja, E / Q", "opcjonalnie gaz myszką (M)", "okno można powiększać"]))
        for idx, rect, action, title, lines in cards:
            sel = idx == self.choose_sel
            col = C_WARN if sel else C_WHITE
            self._button(rect, None, action, color=col, alpha=110 if sel else 60)
            cx, iy = rect.centerx, rect.top + 95
            if idx == 0:                                            # ikona telefonu (poziomo)
                ph = pygame.Rect(0, 0, 210, 110)
                ph.center = (cx, iy)
                pygame.draw.rect(scr, (25, 25, 30), ph, border_radius=16)
                pygame.draw.rect(scr, col, ph, 3, border_radius=16)
                scrn = ph.inflate(-34, -18)
                pygame.draw.rect(scr, (70, 120, 190), scrn)
                pygame.draw.rect(scr, (90, 90, 96), (scrn.x, scrn.bottom - 22, scrn.w, 22))
                for bx in (scrn.x + 16, scrn.x + 42):
                    pygame.draw.circle(scr, C_WHITE, (bx, scrn.bottom - 14), 8, 2)
                pygame.draw.rect(scr, (90, 200, 90), (scrn.right - 22, scrn.y + 8, 12, scrn.h - 16))
                pygame.draw.circle(scr, (240, 220, 200), (scrn.right - 16, scrn.y + 30), 12)      # kciuk
            else:                                                   # ikona monitora z klawiaturą
                mon = pygame.Rect(0, 0, 190, 100)
                mon.center = (cx, iy - 26)
                pygame.draw.rect(scr, (25, 25, 30), mon, border_radius=6)
                pygame.draw.rect(scr, col, mon, 3, border_radius=6)
                pygame.draw.rect(scr, (70, 120, 190), mon.inflate(-18, -18))
                pygame.draw.rect(scr, col, (cx - 8, mon.bottom, 16, 14))
                kb = pygame.Rect(0, 0, 200, 26)
                kb.midtop = (cx, mon.bottom + 18)
                pygame.draw.rect(scr, (40, 40, 46), kb, border_radius=5)
                pygame.draw.rect(scr, col, kb, 2, border_radius=5)
                for i in range(9):
                    pygame.draw.rect(scr, (150, 150, 160), (kb.x + 8 + i * 21, kb.y + 6, 15, 14))
            self._text(title, self.f_big, col, (cx, rect.top + 170), "midtop")
            for i, line in enumerate(lines):
                self._text(line, self.f_small, (220, 220, 225), (cx, rect.top + 228 + i * 24), "midtop",
                           shadow=False)
        self._text("Kliknij albo dotknij wybraną opcję   (klawiatura: %s / %s i ENTER)"
                   % (self.ar['left'], self.ar['right']), self.f_small, C_TEXT, (W / 2, H - 20), "midbottom")

    # garaż ------------------------------------------------------------------------
    def _draw_garage(self):
        scr = self.screen
        scr.blit(self.sky, (0, 0))
        self._shk = (0.0, 0.0)
        self._draw_background(cam_x=self.garage_t * 0.3, cam_y=0.0)
        ground_y = 455
        pygame.draw.rect(scr, (64, 64, 70), (0, ground_y, W, H - ground_y))
        pygame.draw.rect(scr, (176, 176, 180), (0, ground_y, W, 8))
        self._panel((0, 0, W, H), 90)

        V = self.veh
        self._text("GARAŻ", self.f_big, C_WARN, (W / 2, 18), "midtop")
        self._text("wybierz pojazd  (%d / %d)" % (self.veh_idx + 1, len(VEHICLES)), self.f_small, C_TEXT,
                   (W / 2, 64), "midtop", shadow=False)

        # podgląd pojazdu z kierowcą (lekko "pompuje" przodem)
        pose0 = rider_pose(V, 0.0, 0.0)
        tall = pose0['head'][1] + 0.2 + V.wheel_r
        S = min(230.0, 300.0 / tall, 620.0 / (V.wheelbase + 1.0))
        lift = max(0.0, math.sin(self.garage_t * 1.6)) * 0.12
        ox = W / 2 - (V.wheelbase / 2) * S
        oy = ground_y - V.wheel_r * S
        c, s = math.cos(lift), math.sin(lift)
        self._sc = S

        def T(p):
            return (ox + (p[0] * c - p[1] * s) * S, oy - (p[0] * s + p[1] * c) * S)
        sh = pygame.Surface((int((V.wheelbase + 0.9) * S), 16), pygame.SRCALPHA)
        pygame.draw.ellipse(sh, (0, 0, 0, 90), sh.get_rect())
        scr.blit(sh, (ox - 0.45 * S, ground_y - 8))
        spin = self.garage_t * 3.0
        self._draw_vehicle(T, V, lift, spin, spin, rider_pose(V, 0.0, lift))

        # strzałki (klikalne / dotykowe)
        for dirn, rect, action in ((-1, pygame.Rect(20, 190, 110, 140), 'prev'),
                                   (1, pygame.Rect(W - 130, 190, 110, 140), 'next')):
            self._button(rect, None, action, alpha=30)
            self._tri(rect.centerx, rect.centery, 26, dirn, C_WHITE)
        n = len(VEHICLES)
        for i in range(n):
            col = C_WARN if i == self.veh_idx else (120, 120, 130)
            pygame.draw.circle(scr, col, (W / 2 + (i - (n - 1) / 2) * 22, 92), 6)

        self._button((20, 14, 300, 50), "Tryb: " + ("TELEFON" if self.mobile else "PC") + "  (zmień)",
                     'toggle_mobile', font=self.f_small)
        self._button((W - 84, 14, 64, 50), "?", 'help')
        self._button((W - 300, 372, 200, 66), "JEDŹ", 'start', color=C_GOOD)

        # karta pojazdu
        card = pygame.Rect(140, 470, W - 280, 214)
        self._panel(card, 190)
        self._text(V.name, self.f_big, C_TEXT, (card.left + 24, card.top + 10))
        self._text("trudność wheelie", self.f_small, C_WARN, (card.right - 150, card.top + 26), "topright",
                   shadow=False)
        for i in range(5):
            self._star(card.right - 132 + i * 25, card.top + 36, 10, i < V.rating, C_WARN)
        for i, line in enumerate(V.desc):
            self._text(line, self.f_small, (215, 215, 220), (card.left + 24, card.top + 62 + i * 21), shadow=False)
        if V.electric:
            motors = "2 silniki" if V.front_t > 0 else "1 silnik"
            drive = "elektryczny, %s, tryby: %s" % (motors, " / ".join(m[0] for m in V.modes))
        else:
            drive = "spalinowy, %d biegów, sprzęgło" % V.n_gears if V.n_gears > 4 else \
                "spalinowy, %d biegi, sprzęgło" % V.n_gears
        bp = math.degrees(math.pi / 2 - math.atan2(V.com_h, V.com_a))
        stats = [("Napęd", drive, 0, 0),
                 ("Moc", "%.1f kW" % V.peak_kw, 0, 1), ("Masa z kierowcą", "%d kg" % round(V.mass), 1, 1),
                 ("V maks.", "~%d km/h" % round(V.vmax_kmh), 2, 1),
                 ("Punkt równowagi", "%d°" % round(bp), 0, 2),
                 ("Przewrotka od", "%d°" % round(math.degrees(V.max_angle)), 1, 2)]
        for k, v, col, row in stats:
            col_x = card.left + 24 + col * 330
            row_y = card.top + 108 + row * 22
            self._text(k + ":", self.f_small, (160, 160, 170), (col_x, row_y), shadow=False)
            self._text(v, self.f_small, C_TEXT, (col_x + 150, row_y), shadow=False)
        recs = "   ".join("%s: %.1f m" % (DIFFS[d]['name'].capitalize(), self.best.get(self.best_key(V, d), 0.0))
                          for d in DIFFS)
        self._text("Rekordy –  " + recs, self.f_small, C_GOOD, (card.left + 24, card.top + 182), shadow=False)

        self._text("Poziom:", self.f_small, C_TEXT, (W / 2 - 370, 122), "midleft", shadow=False)
        xx = W / 2 - 290
        for d in DIFFS:
            sel = d == self.diff_id
            lbl = DIFFS[d]['name'] if self.mobile else "[%d] %s" % (d, DIFFS[d]['name'])
            r = self._button((xx, 102, 150, 40), lbl, 'diff%d' % d, pressed=sel, font=self.f_small)
            xx = r.right + 10
        if self.diff['wc']:
            self._text("kontrola", self.f_small, (120, 200, 250), (xx + 4, 111), "midleft", shadow=False)
            self._text("wheelie: WŁ.", self.f_small, (120, 200, 250), (xx + 4, 133), "midleft", shadow=False)
        if self.mobile:
            foot = "Dotknij strzałek, aby zmienić pojazd, potem JEDŹ"
        else:
            foot = ("%s / %s  pojazd     1-4  poziom     ENTER  jedź     T  zmień tryb     H  instrukcja     "
                    "ESC  wyjście" % (self.ar['left'], self.ar['right']))
        self._text(foot, self.f_small, C_TEXT, (W / 2, H - 14), "midbottom")

    def _draw_help(self):
        self._panel((0, 0, W, H), 238)
        S, M, B = self.f_small, self.f_med, self.f_big
        a = self.ar
        lines = [
            ("WHEELIE SIMULATOR 2D", B, C_WARN),
            ("", S, C_TEXT),
            ("Cel: utrzymaj przednie koło w powietrzu jak najdłużej – liczy się dystans jednego wheelie.", S, C_TEXT),
            ("Wheelie liczy się dopiero wtedy, gdy bezpiecznie opuścisz przód.", S, C_TEXT),
            ("Pojazd to odwrócone wahadło. Zielona strefa na kątomierzu = punkt równowagi.", S, C_TEXT),
            ("Za mało gazu – przód opada.  Za dużo – przewrotka do tyłu.  Za mocne uderzenie przodem = gleba.",
             S, C_TEXT),
            ("Poziomy ŁATWY i NORMALNY mają kontrolę wheelie: gdy przód leci za wysoko, ucina gaz i hamuje tył.",
             S, (120, 200, 250)),
            ("", S, C_TEXT),
        ]
        if self.mobile:
            lines += [
                ("STEROWANIE DOTYKIEM (telefon trzymaj poziomo)", M, C_WARN),
                ("Prawy kciuk: suwak GAZ – im wyżej palec, tym więcej gazu; puść, a gaz się zamknie.", S, C_TEXT),
                ("HAMULEC – tylny hamulec, szybko opuszcza przód.", S, C_TEXT),
                ("Lewy kciuk: TYŁ / PRZÓD – balans ciałem; szybkie TYŁ szarpie przód w górę.", S, C_TEXT),
                ("SPRZĘGŁO (spalinowe) – trzymaj, wkręć obroty suwakiem, puść = clutch-up.", S, C_TEXT),
                ("BIEG + / -  albo  TRYB + / -  (elektryczne: moc, a w Kukirinach 2× także drugi silnik).",
                 S, C_TEXT),
                ("Na górze: II pauza, R restart, GARAŻ, ? pomoc. Działa wielodotyk – gaz i balans naraz.", S, C_TEXT),
            ]
        else:
            lines += [
                ("STEROWANIE", M, C_WARN),
                ("W / %s   gaz (manetka sama wraca – dawkuj krótkimi wciśnięciami)" % a['up'], S, C_TEXT),
                ("S / %s   tylny hamulec – szybko opuszcza przód" % a['down'], S, C_TEXT),
                ("A / D   balans ciałem do tyłu / do przodu; szybkie odchylenie (A) szarpie przód w górę", S, C_TEXT),
                ("SPACJA  sprzęgło (spalinowe) – trzymaj, wkręć obroty, puść = clutch-up", S, C_TEXT),
                ("E / Q   bieg (spalinowe)  albo  tryb mocy (elektryczne; Kukirin 2× = drugi silnik)", S, C_TEXT),
                ("G / TAB  garaż     M  gaz myszką     T  zmiana trybu TELEFON / PC     1-4  poziom", S, C_TEXT),
                ("R restart    P pauza    H pomoc    ESC wyjście", S, C_TEXT),
            ]
        lines += [
            ("", S, C_TEXT),
            ("POJAZDY", M, C_WARN),
            ("Ścigacz – na 1. biegu wystarczy gaz, wyżej sprzęgło; przed ogranicznikiem zmień bieg.", S, C_TEXT),
            ("Sur-Ron – moment od zera, gaz bardzo nerwowy; tryb ECO łagodzi reakcję.  Pit bike – tylko ze sprzęgła.",
             S, C_TEXT),
            ("Kukiriny – na stojąco, równowaga ok. 25°; przód podnosisz odchyleniem i gazem. G2 Pro tylko ciałem.",
             S, C_TEXT),
            ("G3 Pro i G4 Max mają 2 silniki: na 2× przód wyskakuje, ale w powietrzu ciągnie tylko tył.", S, C_TEXT),
            ("", S, C_TEXT),
            ("Dotknij, aby zamknąć" if self.mobile else "Naciśnij dowolny klawisz", M, C_GOOD),
        ]
        y = 18
        for txt, font, col in lines:
            if txt:
                self._text(txt, font, col, (W / 2, y), "midtop")
            y += font.get_linesize() + (4 if font is not S else 1)


async def main_async(mobile=None):
    game = Game(mobile)
    while game.frame():
        await asyncio.sleep(0)          # oddaje sterowanie przeglądarce (pygbag); na PC nic nie zmienia
    pygame.quit()


def main():
    mobile = None
    if "--telefon" in sys.argv or "--mobile" in sys.argv:
        mobile = True
    elif "--pc" in sys.argv:
        mobile = False
    asyncio.run(main_async(mobile))


if __name__ == "__main__":
    main()
