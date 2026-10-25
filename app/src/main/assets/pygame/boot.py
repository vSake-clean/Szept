# Szept: start gry pygame w Pyodide.
# Gry pisane dla pygbag (pętla z "await asyncio.sleep(0)") działają bez zmian; zwykła pętla "while True"
# działa, gdy przeglądarka telefonu obsługuje JSPI (wtedy każda klatka oddaje sterowanie przeglądarce).
import asyncio
import os
import runpy
import sys
import time
import types

import js
import pygame

GAME_DIR = "/game"

# ---- moduł "szept": wynik gry trafia do tabeli wyników w aplikacji ----
szept = types.ModuleType("szept")
szept.__doc__ = "Połączenie gry z aplikacją Szept: szept.score(wynik, label='…') zapisuje wynik w tabeli."


def _score(value, label=""):
    try:
        js.szeptScore(float(value), str(label or ""))
    except Exception as e:  # zły wynik nie przerywa gry
        print("szept.score:", e)


szept.score = _score
szept.wynik = _score
szept.IN_SZEPT = True
sys.modules["szept"] = szept

# ---- tempo klatek ----
_state = {"async_at": 0.0, "min_frame": 0.0}
_orig_sleep = asyncio.sleep


async def _sleep(delay=0, result=None):
    if delay <= 0:
        _state["async_at"] = time.monotonic()
        await js.szeptFrame(_state["min_frame"])
        return result
    return await _orig_sleep(delay, result)


asyncio.sleep = _sleep


class _Clock:
    """Zegar bez aktywnego czekania — na klatkę czeka przeglądarka (asyncio.sleep / flip)."""

    def __init__(self):
        self._last = time.perf_counter()
        self._dt = 0
        self._fps = 0.0
        self._acc = []

    def tick(self, framerate=0):
        now = time.perf_counter()
        ms = (now - self._last) * 1000.0
        self._last = now
        self._dt = int(round(ms))
        _state["min_frame"] = 1000.0 / framerate if framerate and framerate > 0 else 0.0
        self._acc.append(ms)
        if len(self._acc) > 30:
            self._acc.pop(0)
        avg = sum(self._acc) / len(self._acc)
        self._fps = 1000.0 / avg if avg > 0 else 0.0
        return self._dt

    tick_busy_loop = tick

    def get_time(self):
        return self._dt

    def get_rawtime(self):
        return self._dt

    def get_fps(self):
        return self._fps


pygame.time.Clock = _Clock

# zwykła pętla bez "await": po każdej klatce oddajemy sterowanie przeglądarce (JSPI)
try:
    from pyodide.ffi import can_run_sync, run_sync
except ImportError:  # starsze Pyodide
    can_run_sync = None
    run_sync = None


def _wrap_present(fn):
    def present(*a, **kw):
        r = fn(*a, **kw)
        if run_sync is not None and time.monotonic() - _state["async_at"] > 0.25:
            try:
                if can_run_sync():
                    run_sync(js.szeptFrame(_state["min_frame"]))
            except Exception:
                pass
        return r
    return present


pygame.display.flip = _wrap_present(pygame.display.flip)
pygame.display.update = _wrap_present(pygame.display.update)

# ---- asyncio.run w przeglądarce: zadanie w pętli strony zamiast blokowania ----
_tasks = []


def _run(main, *, debug=None, **kw):
    t = asyncio.ensure_future(main)
    _tasks.append(t)
    return t


asyncio.run = _run

# ---- start ----
home = "/home/pyodide/" + str(SZEPT_GAME_ID)  # noqa: F821 (ustawiane przez runner.js)
os.makedirs(home, exist_ok=True)
os.environ["HOME"] = home
os.chdir(GAME_DIR)
sys.path.insert(0, GAME_DIR)
sys.argv = [os.path.join(GAME_DIR, "main.py")] + list(SZEPT_ARGS)  # noqa: F821

try:
    runpy.run_path(os.path.join(GAME_DIR, "main.py"), run_name="__main__")
    while _tasks:
        await _tasks.pop(0)  # noqa: F704 (kod uruchamiany przez runPythonAsync)
except SystemExit:
    pass
finally:
    try:
        pygame.quit()
    except Exception:
        pass
