"""Loading the raw export - shared by the diagnostic and the cleaning pipeline."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pandas as pd

import config


def load_export() -> pd.DataFrame:
    """
    Read the raw export from its parquet copy, converting the .xls first if needed.

    Both step0 (diagnostic) and step1 (cleaning) call this, so the diagnostic is
    guaranteed to describe exactly the data the pipeline will process - not a
    stale or differently-loaded copy.
    """
    if not config.RAW_PARQUET.exists():
        if not config.RAW_EXCEL.exists():
            raise SystemExit(
                f"Neither {config.RAW_PARQUET.name} nor {config.RAW_EXCEL.name} found in "
                f"{config.DATA}.\nPut the export there, or generate a synthetic stand-in "
                f"with\n  python tools/make_demo_data.py"
            )
        print(f"{config.RAW_PARQUET.name} not found — converting the .xls first.\n"
              f"(On the full export this can take several minutes; see\n"
              f" tools/convert_xls_to_parquet.py if you want to run that step alone.)\n")
        subprocess.run(
            [sys.executable, str(Path(config.ROOT) / "tools" / "convert_xls_to_parquet.py")],
            check=True,
        )

    return pd.read_parquet(config.RAW_PARQUET)
