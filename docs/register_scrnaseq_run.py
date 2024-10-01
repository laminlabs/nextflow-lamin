import argparse
import lamindb as ln
import json
import re

from pathlib import Path


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Register input and output of a nf-core/scrnaseq run in LaminDB."
    )
    parser.add_argument(
        "--input", type=str, required=True, help="Nextflow run input folder path."
    )
    parser.add_argument(
        "--output", type=str, required=True, help="Nextflow run output folder path."
    )
    return parser.parse_args()


def setup_lamindb_context(
    transform_name: str, transform_version: str, transform_reference: str
) -> ln.Run:
    """Defines the lamindb Transform and Run context for the pipeline run."""
    scrnaseq_transform = ln.Transform(
        name=transform_name,
        version=transform_version,
        type="pipeline",
        reference=transform_reference,
    )
    ln.context.track(transform=scrnaseq_transform)
    return ln.context.run


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
    """Registers pipeline run metadata with the lamindb Run.

    All nf-core pipelines store Nextflow run metadata in the 'pipeline_info' directory.
    """
    ulabel = ln.ULabel(name="nextflow").save()
    global_run.transform.ulabels.add(ulabel)

    try:
        content = next(
            Path(f"{output_dir}/pipeline_info").glob("execution_report_*.html")
        ).read_text()
        match = re.search(r"run id \[([^\]]+)\]", content)
        nextflow_id = match.group(1) if match else ""
    except (StopIteration, AttributeError, FileNotFoundError):
        nextflow_id = ""
    global_run.reference = nextflow_id
    global_run.reference_type = "nextflow_id"

    report_artifact = ln.Artifact(
        next(Path(f"{output_dir}/pipeline_info").glob("execution_report*")),
        description=f"nextflow run execution report of {nextflow_id}",
        visibility=0,
        run=False,
    ).save()
    global_run.report = report_artifact

    environment_artifact = ln.Artifact(
        next(Path(f"{output_dir}/pipeline_info").glob("nf_core_pipeline_software*")),
        description=f"nextflow run software versions of {nextflow_id}",
        visibility=0,
        run=False,
    ).save()
    global_run.environment = environment_artifact

    params_path = next(Path(f"{output_dir}/pipeline_info").glob("params*"))
    with params_path.open() as params_file:
        params = json.load(params_file)
    ln.Param(name="params", dtype="dict").save()
    global_run.params.add_values({"params": params})

    global_run.save()


args = parse_arguments()
global_run = setup_lamindb_context(
    transform_name="scrna-seq",
    transform_version="2.7.1",
    transform_reference="https://github.com/nf-core/scrnaseq",
)

register_pipeline_io(args.input, args.output, global_run)
register_pipeline_metadata(args.output, global_run)

ln.context.finish()
