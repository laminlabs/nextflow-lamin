from subprocess import getoutput

import lamindb as ln

transform = ln.Transform(
    name="mcmicro",
    version="1.0.0",
    type="pipeline",
    reference="https://github.com/labsyspharm/mcmicro",
)
ln.track(transform=transform)
run = ln.dev.run_context.run

mcmicro_input = ln.Artifact.filter(key__startswith="exemplar-001")
input_paths = [input_fastq.cache() for input_fastq in mcmicro_input]

output = ln.Artifact(
    "exemplar-001/registration/exemplar-001.ome.tif", description="mcmicro"
)
output.save()

nextflow_id = getoutput(f"nextflow log | awk '/{run.id}/{{print $8}}'")
run.reference = nextflow_id
run.reference_type = "nextflow_id"
run.save()
