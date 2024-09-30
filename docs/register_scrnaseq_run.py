"""register_scrnaseq_run.py.

This script tracks input and output of a nf-core/scrnaseq run in LaminDB.

Usage: python register_scrnaseq_run.py --input {input-dir} --output {output-dir}
"""

import subprocess
import argparse
import lamindb as ln
import json

from pathlib import Path

# get args from command line
parser = argparse.ArgumentParser()
parser.add_argument("--input", type=str, help="Nextflow run input folder name.")
parser.add_argument("--output", type=str, help="Nextflow run output folder name.")
args = parser.parse_args()

transform = ln.Transform(
    name="scrna-seq",
    version="2.7.1",
    type="pipeline",
    reference="https://github.com/nf-core/scrnaseq",
)
ln.context.track(transform=transform)
run = ln.context.run

# register input
input_af = ln.Artifact.from_dir(args.input, run=False)
ln.save(input_af)
run.input_artifacts.set(input_af)

# get the nextflow execution id from the log (first row)
nextflow_id = subprocess.getoutput(
    "nextflow log | tail -n 1 | awk -F '\t' '{print $6}'"
)
# optionally, tag the transform
ulabel = ln.ULabel(name="nextflow").save()
run.transform.ulabels.add(ulabel)
# track the execution report, set visibility to "hidden" to avoid cluttering the artifacts
report_artifact = ln.Artifact(
    next(Path(f"{args.output}/pipeline_info").glob("execution_report*")).absolute(),
    description=f"nextflow run execution report of {nextflow_id}",
    visibility=0,
    run=False,
).save()
run.report = report_artifact
run.reference = nextflow_id
run.reference_type = "nextflow_id"
run.save()
# optionally, track the pipeline parameters
with open(
    next(Path(f"{args.output}/pipeline_info").glob("params*")).absolute()
) as params_file:
    params = json.load(params_file)
ln.Param(name="params", dtype="dict").save()
run.params.add_values({"params": params})

# register output
multiqc_afs = ln.Artifact(f"{args.output}/multiqc", description="multiqc report").save()
ct_mtx_af = ln.Artifact(
    f"{args.output}/star/mtx_conversions/combined_filtered_matrix.h5ad",
    description="filtered count matrix",
).save()

ln.context.finish()
