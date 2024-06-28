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
report = f"{args.name}-execution_report.html"
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

# get the nextflow execution id from the log (first row)
nextflow_id = subprocess.getoutput("nextflow log | awk 'NR==2{print $7}'")

# track the pipeline transform
transform = ln.Transform(
    name="MCMICRO exemplar",
    version="1.0.0",
    type="pipeline",
    reference="https://github.com/labsyspharm/mcmicro",
)
run = ln.track(transform=transform)
run.reference = nextflow_id
run.reference_type = "nextflow_id"
run.report = report
run.save()
# optionally, track the pipeline parameters
run.params.add_values({"name": args.name})
# optionally, sync with the git repository
ln.settings.sync_git_repo = "https://github.com/laminlabs/nextflow-lamin-usecases"

# register mcmicro input data
mcmicro_input = ln.Artifact.from_dir(args.name)
ln.save(mcmicro_input)

# optionally, track this python script as source code
ln.finish()
