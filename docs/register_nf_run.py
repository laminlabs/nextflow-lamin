import argparse
from subprocess import getoutput

import lamindb as ln

parser = argparse.ArgumentParser()
parser.add_argument("-p", "--pipeline-name", required=True)
parser.add_argument("-v", "--pipeline-version", required=True)
parser.add_argument("-r", "--pipeline-reference", required=True)
args = parser.parse_args()

transform = ln.Transform(
    name=args.pipeline_name,
    version=args.pipeline_version,
    type="pipeline",
    reference=args.pipeline_reference,
)
ln.track(transform=transform)
run = ln.dev.run_context.run

mcmicro_input = ln.Artifact.filter(key__startswith="exemplar-001")
input_paths = [input_fastq.stage() for input_fastq in mcmicro_input]

output = ln.Artifact(
    "exemplar-001/registration/exemplar-001.ome.tif", description="mcmicro"
)
output.save()

nextflow_id = getoutput(f"nextflow log | awk '/{run.id}/{{print $8}}'")
run.reference = nextflow_id
run.reference_type = "nextflow_id"
run.save()
