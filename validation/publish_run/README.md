# validation/publish_run

Publishes Nextflow outputs into a Lamin storage location with **Nextflow 26.04+**, using a `lamin://` output directory.

Exercises:

- `-output-dir 'lamin://<owner>/<instance>?prefix=...'` as a publish target
- the `output { }` block with a per-record `path`, publishing a file and a directory per sample
- a CSV `index` file, which is written with APPEND and goes through the byte-channel path of the lamin-s3 provider
- artifact registration of each published file in place, with the storage-relative key

## Run

```bash
make validate-publish-run INSTANCE=laminlabs/lamin-dev PREFIX=nf-lamin-test/$(date +%s)
```

Pass `ARGS="--input lamin://<owner>/<instance>/artifact/<uid>"` to also read an artifact from the same instance, checking that reading keeps working alongside writing.

Re-run the same command to check that publishing over existing files works.
