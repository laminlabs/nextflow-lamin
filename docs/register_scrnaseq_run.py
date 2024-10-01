import argparse
import lamindb as ln
import json
import re
from pathlib import Path


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Register input and output of a nf-core/scrnaseq run in LaminDB.")
    parser.add_argument("--input", type=str, required=True, help="Nextflow run input folder path.")
    parser.add_argument("--output", type=str, required=True, help="Nextflow run output folder path.")
    return parser.parse_args()


def register_pipeline_io(input_dir: str, output_dir: str, global_run: ln.Run) -> None:
    """Registers input and output files and folders of a nf-core/scrnaseq run."""
    input_artifacts = ln.Artifact.from_dir(input_dir, run=False)
    ln.save(input_artifacts)
    global_run.input_artifacts.set(input_artifacts)
    ln.Artifact(f"{output_dir}/multiqc", description="multiqc report").save()
    ln.Artifact(
        f"{output_dir}/star/mtx_conversions/combined_filtered_matrix.h5ad",
        description="filtered count matrix",
    ).save()


def register_pipeline_metadata(output_dir: str, global_run: ln.Run) -> None:
    """Registers nf-core pipeline run metadata stored in the 'pipeline_info' directory."""
    ulabel = ln.ULabel(name="nextflow").save()
    global_run.transform.ulabels.add(ulabel)

    # nextflow run id
    content = next(Path(f"{output_dir}/pipeline_info").glob("execution_report_*.html")).read_text()
    match = re.search(r"run id \[([^\]]+)\]", content)
    nextflow_id = match.group(1) if match else ""
    global_run.reference = nextflow_id
    global_run.reference_type = "nextflow_id"

    # execution report and software versions
    for file_pattern, description, run_attr in [
        ("execution_report*", "execution report", "report"),
        ("nf_core_pipeline_software*", "software versions", "environment"),
    ]:
        artifact = ln.Artifact(
            next(Path(f"{output_dir}/pipeline_info").glob(file_pattern)),
            description=f"nextflow run {description} of {nextflow_id}",
            visibility=0,
            run=False,
        ).save()
        setattr(global_run, run_attr, artifact)

    # nextflow run params
    params_path = next(Path(f"{output_dir}/pipeline_info").glob("params*"))
    with params_path.open() as params_file:
        params = json.load(params_file)
    ln.Param(name="params", dtype="dict").save()
    global_run.params.add_values({"params": params})
    global_run.save()


args = parse_arguments()
scrnaseq_transform = ln.Transform(
    name="scrna-seq",
    version="2.7.1",
    type="pipeline",
    reference="https://github.com/nf-core/scrnaseq",
)
ln.context.track(transform=scrnaseq_transform)
global_run = ln.context.run

register_pipeline_io(args.input, args.output, global_run)
register_pipeline_metadata(args.output, global_run)
ln.context.finish()
