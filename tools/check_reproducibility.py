#!/usr/bin/env python3
"""Generate the dataset twice with the same arguments and compare row counts and checksums."""
import argparse, hashlib, json, subprocess, sys, tempfile
from datetime import datetime, timezone
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--loans", type=int, default=10000)
    p.add_argument("--seed", type=int, default=20261022)
    p.add_argument("--reference-date", default="2026-10-28")
    p.add_argument("--report", default="data/reproducibility_report.json")
    args = p.parse_args()
    script = Path(__file__).with_name("generate_synthetic_data.py")
    with tempfile.TemporaryDirectory() as temp:
        runs = [Path(temp) / "run-a", Path(temp) / "run-b"]
        for out in runs:
            subprocess.run([sys.executable, str(script), "--loans", str(args.loans), "--seed", str(args.seed),
                            "--reference-date", args.reference_date, "--out", str(out)],
                           check=True, stdout=subprocess.DEVNULL)
        files = [sorted(f.relative_to(r).as_posix() for f in r.rglob("*") if f.is_file()) for r in runs]
        counts = [json.loads((r / "manifest.json").read_text(encoding="utf-8"))["row_counts"] for r in runs]
        compared = {name: {"run_a": sha(runs[0] / name), "run_b": sha(runs[1] / name)} for name in files[0] if name in files[1]}
        differing = [n for n, h in compared.items() if h["run_a"] != h["run_b"]]
        passed = files[0] == files[1] and counts[0] == counts[1] and not differing
        report = {"status": "PASS" if passed else "FAIL", "checked_at_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
                  "arguments": {"loans": args.loans, "seed": args.seed, "reference_date": args.reference_date},
                  "file_lists_identical": files[0] == files[1], "row_counts_identical": counts[0] == counts[1],
                  "row_counts": counts[0], "files_compared": len(compared), "differing_files": differing,
                  "sha256": {n: h["run_a"] for n, h in compared.items()}}
    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"{report['status']}: {len(compared)} files compared, {len(differing)} differ, "
          f"row counts {'identical' if report['row_counts_identical'] else 'DIFFER'} -> {args.report}")
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
