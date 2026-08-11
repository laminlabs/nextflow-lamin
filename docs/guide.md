---
execute_via: python
---

# Nextflow

There are two ways of tracking Nextflow pipeline runs with their inputs and outputs in LaminDB.

## `nf-lamin`

The [`nf-lamin`](https://github.com/laminlabs/nf-lamin) Nextflow plugin works without modifying pipeline code via LaminHub's REST API.

**Option A: environment variables** (no config file needed):

```bash tags=["skip-execution"]
export LAMIN_CURRENT_INSTANCE="your-org/your-instance"
export LAMIN_API_KEY="<your-lamin-api-key>"
nextflow run -plugins nf-lamin <your-pipeline>
```

**Option B: Nextflow secrets + config file**:

Store your API key as a Nextflow secret:

```bash tags=["skip-execution"]
nextflow secrets set LAMIN_API_KEY <your-lamin-api-key>
```

Create a `lamin.config`:

```groovy tags=["skip-execution"]
plugins {
  id 'nf-lamin'
}

lamin {
  instance = "your-org/your-instance"
  api_key = secrets.LAMIN_API_KEY
}
```

Then run your pipeline with the config:

```bash tags=["skip-execution"]
nextflow run <your-pipeline> -c lamin.config
```

After the run, explore the tracked data in LaminHub or via the Python SDK:

```python tags=["skip-execution"]
import lamindb as ln

ln.Run.get("your-run-uid")
```

![](guide/nf_core_scrnaseq_run.png)

Runs executed with `-with-tower` or launched from Seqera Platform additionally get `run.reference` set
to the Platform watch URL, with `run.reference_type` set to `"Seqera"`.

→ See {doc}`/reference` for the full `nf-lamin` configuration reference.

→ See {doc}`/reference/examples` for ready-to-run examples for existing pipelines.

## Post-run scripts

You can register runs manually without using the `nf-lamin` plugin using LaminDB in a Python post-run script. First run the pipeline:

```shell
# the test profile uses all downloaded input files as an input
nextflow run nf-core/scrnaseq -r 4.0.0 -profile docker,test -resume --outdir scrnaseq_output
```

:::{dropdown} Example: nf-core/scrnaseq

![](guide/nf_core_scrnaseq_diagram.png)

:::

After the run is complete, use a post-run script to register inputs and outputs in LaminDB:

```{eval-rst}
.. literalinclude:: guide/register_scrnaseq_run.py
   :language: python
   :caption: nf-core/scrnaseq run registration
```

```shell
python guide/register_scrnaseq_run.py --input scrnaseq_input --output scrnaseq_output
```

Such a script can also be triggered from a serverless environment (e.g., AWS Lambda).
