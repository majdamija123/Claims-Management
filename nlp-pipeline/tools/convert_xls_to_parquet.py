"""
Convert the raw .xls export to parquet, once.

Split out from step1 because the real export is ~400 MB — legacy .xls (the
Excel 97-2003 binary format) caps every sheet at 65 536 rows, so a file that
size means either a sheet close to that ceiling, or several sheets. `xlrd`
(the only engine that reads this format) has no streaming mode: it parses the
whole workbook into memory before pandas sees a single row, so this step is
slow and memory-hungry regardless of what pandas does with it afterwards.

Running it once and working from the parquet copy for every later step is
the point: parquet is columnar, keeps the dtypes, and loads in under a
second even at this size.

Usage:
    python tools/convert_xls_to_parquet.py
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import pandas as pd

import config


def main() -> None:
    if not config.RAW_EXCEL.exists():
        raise SystemExit(f"Not found: {config.RAW_EXCEL}\nPut the export there first.")

    size_mb = config.RAW_EXCEL.stat().st_size / 1e6
    print(f"Reading {config.RAW_EXCEL.name} ({size_mb:.0f} MB)")
    print("This can take several minutes on a file this size — xlrd loads the")
    print("whole workbook into memory before pandas sees anything. Don't interrupt it.\n")

    started = time.time()

    workbook = pd.ExcelFile(config.RAW_EXCEL, engine="xlrd")
    print(f"Sheets found: {workbook.sheet_names}")

    if len(workbook.sheet_names) == 1:
        df = workbook.parse(workbook.sheet_names[0])
    else:
        # Multiple sheets: legacy .xls caps a sheet at 65 536 rows, so an export
        # bigger than that is typically split across several sheets with the
        # same columns. Concatenated here rather than left for step 1 to
        # discover, so every later step sees one flat table.
        print("Multiple sheets — concatenating them (same columns assumed).")
        frames = []
        for name in workbook.sheet_names:
            sheet_df = workbook.parse(name)
            print(f"  {name}: {len(sheet_df):,} rows")
            frames.append(sheet_df)
        df = pd.concat(frames, ignore_index=True)

    elapsed = time.time() - started
    print(f"\nParsed {len(df):,} rows x {df.shape[1]} columns in {elapsed:.0f}s")

    if len(df) >= 65_000:
        print("(At or near the 65 536-row ceiling of the .xls format — this is")
        print(" almost certainly the complete export, not a truncated read.)")

    config.RAW_PARQUET.parent.mkdir(parents=True, exist_ok=True)
    df.to_parquet(config.RAW_PARQUET, index=False)

    print(f"\nWritten to {config.RAW_PARQUET}")
    print(f"  {config.RAW_PARQUET.stat().st_size / 1e6:.0f} MB "
          f"(vs {size_mb:.0f} MB for the .xls — parquet compresses column-wise)")
    print("\nEvery pipeline step from here reads the parquet, not the .xls.")


if __name__ == "__main__":
    main()
