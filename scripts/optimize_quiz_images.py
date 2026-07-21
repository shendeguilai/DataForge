#!/usr/bin/env python3
"""Build browser-sized WebP copies of the question-side OI cards."""

from __future__ import annotations

import argparse
import re
from pathlib import Path

from PIL import Image


FILE_PATTERN = re.compile(r"Keda_OI_Card_(J\d{3})_正面_x12\.png$")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--height", type=int, default=1400)
    parser.add_argument("--quality", type=int, default=86)
    args = parser.parse_args()

    cards = []
    for path in sorted(args.source.glob("*_正面_x12.png")):
        match = FILE_PATTERN.match(path.name)
        if match:
            cards.append((match.group(1), path))
    if len(cards) != 256:
        raise SystemExit(f"应找到 256 张正面题卡，实际找到 {len(cards)} 张")

    args.output.mkdir(parents=True, exist_ok=True)
    expected = set()
    for card_id, source in cards:
        destination = args.output / f"{card_id}.webp"
        expected.add(destination.name)
        with Image.open(source) as image:
            image = image.convert("RGBA")
            target_width = round(image.width * args.height / image.height)
            image = image.resize((target_width, args.height), Image.Resampling.LANCZOS)
            image.save(destination, "WEBP", quality=args.quality, method=4)
        print(f"{card_id}: {destination.stat().st_size} bytes")

    stale = [path for path in args.output.glob("*.webp") if path.name not in expected]
    if stale:
        raise SystemExit("输出目录存在非题库资源: " + ", ".join(path.name for path in stale))


if __name__ == "__main__":
    main()
