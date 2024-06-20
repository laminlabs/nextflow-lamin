from subprocess import getoutput

import lamindb as ln
import yaml

transform = ln.Transform(
    name="mcmicro",
    version="1.0.0",
    type="pipeline",
    reference="https://github.com/labsyspharm/mcmicro",
)
run = ln.track(transform=transform)

ln.settings.sync_git_repo = "https://github.com/laminlabs/nextflow-lamin-usecases"

ulabel = ln.ULabel(name="nextflow").save()
transform.ulabels.add(ulabel)

with open("exemplar-001/qc/params.yml") as params_file:
    qc_params = yaml.safe_load(params_file)

ln.Param(name="qc_params", dtype="dict").save()
run.params.add_values({"qc_params": qc_params})

mcmicro_input = ln.Artifact.filter(key__startswith="exemplar-001")
run.input_artifacts.set(mcmicro_input)

output = ln.Artifact(
    "exemplar-001/registration/exemplar-001.ome.tif", description="mcmicro"
)
output.save()
run.output_artifacts.set([output])

report = ln.Artifact("execution_report.html").save()
run.report = report
run.save()

nextflow_id = getoutput(f"nextflow log | awk '/{run.id}/{{print $8}}'")
run.reference = nextflow_id
run.reference_type = "nextflow_id"
run.save()

ln.finish()
