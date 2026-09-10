# validation/publish_legacy

Publishes Nextflow outputs into a Lamin storage location with the `publishDir` directive, for Nextflow versions before 26.04.

Exercises:

- `publishDir 'lamin://<owner>/<instance>?prefix=...'` with `saveAs`, publishing a file and a directory per sample
- `overwrite: true`, which makes PublishDir delete and re-publish existing files on a re-run
- artifact registration of each published file in place, with the storage-relative key

## Run

```bash
make validate-publish-legacy INSTANCE=laminlabs/lamin-dev PREFIX=nf-lamin-test/$(date +%s)
```

Pass `ARGS="--input lamin://<owner>/<instance>/artifact/<uid>"` to also read an artifact from the same instance.
