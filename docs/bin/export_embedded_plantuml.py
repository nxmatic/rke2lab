#!/usr/bin/env python3
"""Export embedded AsciiDoc PlantUML blocks to standalone diagram assets via Kroki.

Usage:
  python docs/bin/export_embedded_plantuml.py docs/rke2lab-authored-notes-import.adoc
  python docs/bin/export_embedded_plantuml.py docs/rke2lab-authored-notes-import.adoc --out docs/.generated/diagrams
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import urllib.error
import urllib.request

DIAGRAM_HEADER_RE = re.compile(r"^\[plantuml,([^,\]]+),svg\]\s*$")
DELIMITER = "----"
KROKI_BASE_ENDPOINT = "https://kroki.io/plantuml"
DEFAULT_REVIEW_DPI = 220


def parse_diagrams(asciidoc_path: pathlib.Path) -> list[tuple[str, str]]:
    lines = asciidoc_path.read_text(encoding="utf-8").splitlines()
    diagrams: list[tuple[str, str]] = []

    index = 0
    while index < len(lines):
        header_match = DIAGRAM_HEADER_RE.match(lines[index].strip())
        if not header_match:
            index += 1
            continue

        diagram_name = header_match.group(1).strip()
        index += 1

        while index < len(lines) and not lines[index].strip():
            index += 1

        if index >= len(lines) or lines[index].strip() != DELIMITER:
            raise ValueError(
                f"Expected '{DELIMITER}' after [plantuml,...] block for '{diagram_name}'"
            )

        index += 1
        body_start = index
        while index < len(lines) and lines[index].strip() != DELIMITER:
            index += 1

        if index >= len(lines):
            raise ValueError(
                f"Unterminated PlantUML block for '{diagram_name}' in {asciidoc_path}"
            )

        diagram_source = "\n".join(lines[body_start:index]).strip() + "\n"
        diagrams.append((diagram_name, diagram_source))

        index += 1

    return diagrams


def render_plantuml(plantuml_source: str, output_format: str) -> bytes:
    endpoint = f"{KROKI_BASE_ENDPOINT}/{output_format}"
    accept = "image/svg+xml,text/plain,*/*" if output_format == "svg" else "image/png,*/*"
    request = urllib.request.Request(
        endpoint,
        data=plantuml_source.encode("utf-8"),
        headers={
            "Content-Type": "text/plain; charset=utf-8",
            "Accept": accept,
            "User-Agent": "rke2lab-diagram-exporter/1.0 (+https://github.com/nxmatic/rke2lab)",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def apply_review_dpi(plantuml_source: str, review_dpi: int) -> str:
    start = "@startuml"
    index = plantuml_source.find(start)
    if index < 0:
        return plantuml_source

    insertion_index = index + len(start)
    return (
        plantuml_source[:insertion_index]
        + f"\nskinparam dpi {review_dpi}"
        + plantuml_source[insertion_index:]
    )


def export_diagrams(
    asciidoc_path: pathlib.Path, out_dir: pathlib.Path, review_dpi: int
) -> list[pathlib.Path]:
    diagrams = parse_diagrams(asciidoc_path)
    if not diagrams:
        return []

    out_dir.mkdir(parents=True, exist_ok=True)
    exported: list[pathlib.Path] = []

    for name, source in diagrams:
        svg_bytes = render_plantuml(source, "svg")
        review_png_bytes = render_plantuml(apply_review_dpi(source, review_dpi), "png")

        puml_path = out_dir / f"{name}.puml"
        svg_path = out_dir / f"{name}.svg"
        review_png_path = out_dir / f"{name}.review.png"

        puml_path.write_text(source, encoding="utf-8")
        svg_path.write_bytes(svg_bytes)
        review_png_path.write_bytes(review_png_bytes)

        exported.append(svg_path)
        exported.append(review_png_path)

    return exported


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Export embedded PlantUML AsciiDoc blocks to standalone SVG and review PNG files"
    )
    parser.add_argument(
        "asciidoc_file",
        type=pathlib.Path,
        help="Path to the AsciiDoc file containing [plantuml,...,svg] blocks",
    )
    parser.add_argument(
        "--out",
        type=pathlib.Path,
        default=pathlib.Path("docs/.generated/diagrams"),
        help="Output directory for .puml, .svg, and .review.png files (default: docs/.generated/diagrams)",
    )
    parser.add_argument(
        "--review-dpi",
        type=int,
        default=DEFAULT_REVIEW_DPI,
        help=f"DPI for generated .review.png images (default: {DEFAULT_REVIEW_DPI})",
    )
    args = parser.parse_args()

    asciidoc_path = args.asciidoc_file
    if not asciidoc_path.exists():
        print(f"error: file not found: {asciidoc_path}", file=sys.stderr)
        return 2

    try:
        exported = export_diagrams(asciidoc_path, args.out, args.review_dpi)
    except (ValueError, urllib.error.URLError, TimeoutError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    if not exported:
        print(f"No [plantuml,...,svg] blocks found in {asciidoc_path}")
        return 0

    print(f"Exported {len(exported)} diagram(s):")
    for path in exported:
        print(f"- {path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
