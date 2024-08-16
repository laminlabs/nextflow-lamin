# mcmicro.py

"""This script runs the MCMICRO pipeline and tracks input/output data in LaminDB.

Usage: python mcmicro.py exemplar-001
"""

import subprocess
import argparse
import lamindb as ln
import yaml

# get args from command line
parser = argparse.ArgumentParser()
parser.add_argument("input", type=str, help="Input folder name.")
args = parser.parse_args()

transform = ln.Transform(
    name="MCMICRO",
    version="1.0.0",
    type="pipeline",
    reference="https://github.com/labsyspharm/mcmicro",
)
ln.context.track(transform=transform)
run = ln.context.run

# get the input data from LaminDB
mcmicro_input = ln.Artifact.filter(description=args.input).one()
input_dir = mcmicro_input.cache()

# execute the nextflow pipeline to download example data
report = f"{args.input}-mcmicro-execution_report.html"
subprocess.run(
    [
        "nextflow",
        "run",
        "https://github.com/labsyspharm/mcmicro",
        "--in",
        input_dir,
        "--start-at",
        "illumination",
        "--stop-at",
        "registration",
        "-with-report",
        report,
    ]
)

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
with open(f"{input_dir}/qc/params.yml") as params_file:
    qc_params = yaml.safe_load(params_file)
ln.Param(name="qc_params", dtype="dict").save()
run.params.add_values({"qc_params": qc_params})

# register the output artifact
output = ln.Artifact.from_dir(f"{input_dir}/registration")
ln.save(output)

ln.finish()
