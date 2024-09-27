"""mcmicro.py.

This script runs the MCMICRO pipeline and tracks output data in LaminDB.

Usage: python mcmicro.py output-folder
"""

import subprocess
import argparse
import lamindb as ln
import yaml

from pathlib import Path

# get args from command line
parser = argparse.ArgumentParser()
parser.add_argument("output", type=str, help="Nextflow run output folder name")
args = parser.parse_args()

transform = ln.Transform(
    name="MCMICRO",
    version="1.0.0",
    type="pipeline",
    reference="https://github.com/labsyspharm/mcmicro",
)
ln.context.track(transform=transform)
run = ln.context.run

# TEEEEEEEEST
mcmicro_input = ln.Artifact.get("laminlabs/lamindata").filter(
    description="exemplar-001"
)
mcmicro_input.load()

report = "mcmicro-execution_report.html"

# get the nextflow execution id from the log (first row)
nextflow_id = subprocess.getoutput(
    "nextflow log | tail -n 1 | awk -F '\t' '{print $6}'"
)
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
with open(f"{args.output}/qc/params.yml") as params_file:
    qc_params = yaml.safe_load(params_file)
ln.Param(name="qc_params", dtype="dict").save()
run.params.add_values({"qc_params": qc_params})

# register the output artifact
(Path(args.output) / "registration" / f"{Path(args.output).name}.ome.tif").rename(
    Path(args.output) / "registration" / "exemplar-001.ome.tif"
)
output = ln.Artifact.from_dir(f"{args.output}/registration")
ln.save(output)

ln.context.finish()
