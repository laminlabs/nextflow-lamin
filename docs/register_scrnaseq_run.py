import argparse
import lamindb as ln
import json
import re
from pathlib import Path


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=str, required=True)
    parser.add_argument("--output", type=str, required=True)
    return parser.parse_args()


def register_pipeline_io(input_dir: str, output_dir: str, run: ln.Run) -> None:
    """Register input and output artifacts for an `nf-core/scrnaseq` run."""
    input_artifacts = ln.Artifact.from_dir(input_dir, run=False)
    ln.save(input_artifacts)
    run.input_artifacts.set(input_artifacts)
    ln.Artifact(f"{output_dir}/multiqc", description="multiqc report", run=run).save()
    ln.Artifact(
        f"{output_dir}/star/mtx_conversions/combined_filtered_matrix.h5ad",
        description="filtered count matrix",
        run=run,
    ).save()


def register_pipeline_metadata(output_dir: str, run: ln.Run) -> None:
    """Register nf-core run metadata stored in the 'pipeline_info' folder."""
    ulabel = ln.ULabel(name="nextflow").save()
    run.transform.ulabels.add(ulabel)

    # nextflow run id
    content = next(Path(f"{output_dir}/pipeline_info").glob("execution_report_*.html")).read_text()
    match = re.search(r"run id \[([^\]]+)\]", content)
    nextflow_id = match.group(1) if match else ""
    run.reference = nextflow_id
    run.reference_type = "nextflow_id"

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
        setattr(run, run_attr, artifact)

    # nextflow run parameters
    params_path = next(Path(f"{output_dir}/pipeline_info").glob("params*"))
    with params_path.open() as params_file:
        params = json.load(params_file)
    ln.Param(name="params", dtype="dict").save()
    run.params.add_values({"params": params})
    run.save()


args = parse_arguments()
scrnaseq_transform = ln.Transform(
    name="scrna-seq",
    version="2.7.1",
    type="pipeline",
    reference="https://github.com/nf-core/scrnaseq",
).save()
run = ln.Run(transform=scrnaseq_transform).save()
register_pipeline_io(args.input, args.output, run)
register_pipeline_metadata(args.output, run)
