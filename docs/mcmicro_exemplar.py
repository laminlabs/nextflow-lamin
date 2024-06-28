"""This script downloads the MCMICRO exemplar data and registers it in LaminDB.

Usage: python mcmicro_exemplar.py exemplar-001
"""

import lamindb as ln
import subprocess
import argparse


# get args from command line
parser = argparse.ArgumentParser()
parser.add_argument("name", type=str, help="Name of the example data.")
args = parser.parse_args()
if args.name not in ["exemplar-001", "exemplar-002"]:
    raise ValueError("Invalid name. Use 'exemplar-001' or 'exemplar-002'.")

# execute the nextflow pipeline to download example data
report = f"{args.name}_mcmicro-exemplar_execution_report.html"
subprocess.run(
    [
        "nextflow",
        "run",
        "labsyspharm/mcmicro/exemplar.nf",
        "--name",
        args.name,
        "-with-report",
        report,
    ]
)

# get the nextflow execution id from the log (last row, latest run is at the bottom)
nextflow_id = subprocess.getoutput(
    "nextflow log | tail -n 1 | awk -F '\t' '{print $6}'"
)

# track the pipeline transform
transform = ln.Transform(
    name="MCMICRO exemplar",
    version="1.0.0",
    type="pipeline",
    reference="https://github.com/labsyspharm/mcmicro",
)
run = ln.track(transform=transform)
# optionally, tag the transform
ulabel = ln.ULabel(name="nextflow").save()
run.transform.ulabels.add(ulabel)
# track the execution report, set visibility to "hidden" to avoid cluttering the artifacts
report_artifact = ln.Artifact(
    report, description=f"nextflow report of {nextflow_id}", visibility=0, run=False
).save()
run.report = report_artifact
run.reference = nextflow_id
run.reference_type = "nextflow_id"
run.save()
# optionally, track the pipeline parameters
ln.Param(name="name", dtype="str").save()
run.params.add_values({"name": args.name})

# register the downloaded folder
exemplar_dir = ln.Artifact(args.name, description=args.name)
exemplar_dir.save()

ln.finish()
