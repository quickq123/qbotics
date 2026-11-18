# The computers' time module (MicroPython's). It has less than the time module on your PC:
# no time.time() or time.monotonic(). For the time of day in the game, see the env module.

def sleep(seconds: float) -> None:
    """Waits this many seconds (decimals work: time.sleep(0.5))."""

def sleep_ms(ms: int) -> None:
    """Waits this many thousandths of a second."""

def sleep_us(us: int) -> None:
    """Waits this many millionths of a second."""

def ticks_ms() -> int:
    """A count of thousandths of a second, for timing: compare two with ticks_diff."""

def ticks_us() -> int:
    """A count of millionths of a second, for timing: compare two with ticks_diff."""

def ticks_cpu() -> int:
    """The finest count there is, for timing very short things."""

def ticks_add(ticks: int, delta: int) -> int:
    """A tick count moved on (or back) by delta."""

def ticks_diff(newer: int, older: int) -> int:
    """How far apart two tick counts are: time.ticks_diff(time.ticks_ms(), start)."""
