from __future__ import annotations

import logging


class ColorLevelFormatter(logging.Formatter):
    COLORS = {
        logging.WARNING: "\033[33m",
        logging.ERROR: "\033[31m",
        logging.CRITICAL: "\033[31m",
    }
    RESET = "\033[0m"

    def format(self, record: logging.LogRecord) -> str:
        message = super().format(record)
        color = self.COLORS.get(record.levelno)
        if color is None:
            return message
        return f"{color}{message}{self.RESET}"


def configure_logging() -> None:
    root_logger = logging.getLogger()
    if getattr(root_logger, "_mianba_colored", False):
        return

    handler = logging.StreamHandler()
    handler.setFormatter(
        ColorLevelFormatter(
            "%(asctime)s %(levelname)s [%(name)s] %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S",
        )
    )

    root_logger.handlers.clear()
    root_logger.addHandler(handler)
    root_logger.setLevel(logging.INFO)
    root_logger._mianba_colored = True  # type: ignore[attr-defined]
