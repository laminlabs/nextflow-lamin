# Nextflow config

All `nf-lamin` configuration lives in the `lamin {}` scope of your `nextflow.config`.

## Best-practice config

A recommended starting point for nf-core-style pipelines. The key idea is to exclude all output files by default (`exclude_pattern = '.*'`), then use `type = 'include'` rules to opt-in to the files that matter. Pre-define rules for optional outputs with `enabled = false` so users can turn them on without writing new patterns.

```groovy
plugins {
  id 'nf-lamin'
}

lamin {
  // Link all artifacts, runs, and transforms to a project
  project_uids = ['projXXXXXXXXXXXXXX']

  // Track input artifacts
  input_artifacts {
    rules {
      // Explicitly track files not staged into Nextflow processes
      samplesheet { include_paths = { params.input }; kind = 'dataset' }
      fastq_reads { pattern = '.*\\.fastq(\\.gz)?$'; kind = 'dataset' }
      reference   { pattern = '.*\\.(fasta|fa)(\\.gz)?$'; kind = 'dataset' }
      annotation  { pattern = '.*\\.(gtf|gff)(\\.gz)?$'; kind = 'dataset' }
    }
  }

  // Track output artifacts
  output_artifacts {
    exclude_pattern = '.*'
    rules {
      // Enabled by default
      reports      { type = 'include'; pattern = '.*\\.html$'; kind = 'report' }
      mapped_reads { type = 'include'; pattern = '.*\\.bam$'; kind = 'dataset' }
      // Disabled (opt-in)
      bam_index    { type = 'include'; enabled = false; pattern = '.*\\.bai$'; kind = 'dataset' }
    }
  }
}
```

To enable the optional BAM index tracking, a user could modify the config above, or create a new config file with just the override:

```groovy
lamin.output_artifacts.rules.bam_index.enabled = true
```

The sections below document each setting in detail.

---

## `lamin` - top-level settings

| Setting         | Type    | Default  | Env variable             | Description                               |
| --------------- | ------- | -------- | ------------------------ | ----------------------------------------- |
| `instance`      | String  | env var  | `LAMIN_CURRENT_INSTANCE` | LaminDB instance (`owner/name`)           |
| `api_key`       | String  | env var  | `LAMIN_API_KEY`          | LaminHub API key                          |
| `project_uids`  | List    | `null`   | `LAMIN_CURRENT_PROJECT`  | Project UIDs to link to all records       |
| `ulabel_uids`   | List    | `null`   |                          | ULabel UIDs to link to all records        |
| `space_uid`     | String  | `null`   |                          | Space UID                                 |
| `branch_uid`    | String  | `null`   |                          | Branch UID                                |
| `env`           | String  | `'prod'` | `LAMIN_ENV`              | Environment (`'prod'` or `'staging'`)     |
| `dry_run`       | Boolean | `false`  | `LAMIN_DRY_RUN`          | Validate config without creating records  |
| `transform_uid` | String  | `null`   | `LAMIN_TRANSFORM_UID`    | Override the auto-generated transform UID |
| `run_uid`       | String  | `null`   | `LAMIN_RUN_UID`          | Override the auto-generated run UID       |

**Experimental**: UID fields (`project_uids`, `ulabel_uids`, `space_uid`, `branch_uid`) also accept named references: `'?name'` (lookup by name, omit if missing), `'!name'` (lookup, error if missing), `'+name'` (create if missing). This is an experimental feature and may be removed in a future release.

---

## `lamin.run` / `lamin.transform` - record-specific metadata

Attach ULabel UIDs specifically to runs or transforms. These are merged with the root-level `ulabel_uids`.

| Setting       | Type | Default |
| ------------- | ---- | ------- |
| `ulabel_uids` | List | `[]`    |

```groovy
lamin {
  run       { ulabel_uids = ['ulab-run-specific'] }
  transform { ulabel_uids = ['ulab-transform-specific'] }
}
```

---

## Artifact tracking

Control which files are tracked and what metadata is attached. Configure tracking either globally (`artifacts`) or separately for inputs and outputs (`input_artifacts` / `output_artifacts`). These two approaches are **mutually exclusive**.

### Artifact config options

Apply to `artifacts`, `input_artifacts`, or `output_artifacts`:

| Setting              | Type                    | Default | Description                                   |
| -------------------- | ----------------------- | ------- | --------------------------------------------- |
| `enabled`            | Boolean                 | `true`  | Enable/disable tracking                       |
| `exclude_work_dir`   | Boolean                 | `true`  | Ignore paths in the Nextflow work directory   |
| `exclude_assets_dir` | Boolean                 | `true`  | Ignore paths in the Nextflow assets directory |
| `include_pattern`    | String                  | `null`  | Regex; only matching files are tracked        |
| `exclude_pattern`    | String                  | `null`  | Regex; matching files are skipped             |
| `ulabel_uids`        | List                    | `null`  | ULabel UIDs for matched artifacts             |
| `kind`               | String                  | `null`  | Artifact kind (e.g. `'dataset'`, `'report'`)  |
| `include_paths`      | String / List / Closure | `null`  | Paths to explicitly track (see below)         |
| `rules`              | Map                     | `{}`    | Pattern-based rules (see below)               |

### Explicit paths (`include_paths`)

Some files are never staged into Nextflow process input channels (e.g. samplesheets parsed by Groovy helpers like nf-schema's `samplesheetToList`). Use `include_paths` to explicitly track these files.

`include_paths` accepts a string, a list of strings, or a closure returning a string or list:

**On an artifact config:**

```groovy
input_artifacts {
  include_paths = { params.input }
}
```

**On a rule** (to attach metadata like `kind`):

```groovy
input_artifacts {
  rules {
    samplesheet {
      include_paths = { params.input }
      kind = 'dataset'
    }
  }
}
```

:::{note}
**Use closures for `params.*` references**

When the `lamin {}` config scope is first evaluated, not all Nextflow params may be available yet. For example, params set by a profile (`-profile test`) or pulled in via `includeConfig` are resolved later. Using a closure defers the evaluation until params are fully resolved:

```groovy
// safe: evaluated after all params are resolved
include_paths = { params.input }

// may fail if the param isn't available yet
include_paths = params.input
```

:::

Input paths are resolved at the beginning of the workflow (`onFlowBegin`), and output paths just before finalizing the run. Resolved paths go through the same deduplication, metadata linking, and rule evaluation as auto-detected artifacts.

### Rules

Rules apply different settings based on file patterns or explicit paths. Each rule is a named block. Either `pattern` or `include_paths` (or both) must be specified.

| Setting         | Type                    | Default     | Description                        |
| --------------- | ----------------------- | ----------- | ---------------------------------- |
| `pattern`       | String                  | `null`      | Java regex to match file paths     |
| `include_paths` | String / List / Closure | `null`      | Paths to explicitly track          |
| `enabled`       | Boolean                 | `true`      | Enable/disable this rule           |
| `type`          | String                  | `'include'` | `'include'` or `'exclude'`         |
| `direction`     | String                  | `'both'`    | `'input'`, `'output'`, or `'both'` |
| `order`         | Integer                 | `100`       | Priority (lower = evaluated first) |
| `ulabel_uids`   | List                    | `null`      | ULabel UIDs for matched artifacts  |
| `kind`          | String                  | `null`      | Override artifact kind             |

**Evaluation order:**

1. Global `include_pattern` / `exclude_pattern` are checked first
2. Rules are evaluated by `order` (lower first)
3. All matching rules are applied; later rules can override earlier ones
4. ULabel UIDs from all matching rules are merged (deduplicated)

Patterns are Java regular expressions. Backslashes must be escaped in Groovy: `\\.` not `\.`

### Example: direction-specific tracking

```groovy
lamin {
  input_artifacts {
    rules {
      samplesheet { include_paths = { params.input }; kind = 'dataset'; order = 1 }
      reference   { pattern = '.*\\.(fasta|gtf)$'; kind = 'dataset' }
      fastqs      { pattern = '.*\\.fastq\\.gz$'; kind = 'dataset' }
    }
  }

  output_artifacts {
    exclude_pattern = '.*\\.(log|tmp)$'
    rules {
      exclude_intermediate { type = 'exclude'; pattern = '.*intermediate.*'; order = 1 }
      bam_files  { pattern = '.*\\.bam$'; kind = 'dataset'; order = 2 }
      vcf_files  { pattern = '.*\\.vcf\\.gz$'; kind = 'dataset'; order = 3 }
    }
  }
}
```

### Example: disable all artifact tracking

```groovy
lamin {
  output_artifacts { enabled = false }
  input_artifacts  { enabled = false }
}
```

---

## `lamin.features` - Feature flags

Optional toggles for plugin features.

| Setting                 | Type    | Default | Description                                                                         |
| ----------------------- | ------- | ------- | ----------------------------------------------------------------------------------- |
| `manage_s3_credentials` | Boolean | `true`  | Enable automatic credential federation for S3 (see [`lamin://` URIs](lamin-uri.md)) |

**Example** — disable credential federation if it causes issues in your environment:

```groovy
lamin {
  features {
    manage_s3_credentials = false
  }
}
```

When `manage_s3_credentials = false`, the plugin resolves `lamin://` URIs to their underlying `s3://` paths and lets Nextflow handle authentication via the standard credential provider chain (environment variables, AWS credentials file, instance profile, etc.). This applies to publishing as well as reading.

### `space_uid` and publish targets

A publish target that names a space (`lamin://owner/instance?space=<uid>`) decides the space of the artifacts published to it, because the storage location it resolves to is the one LaminDB associates with that space. The same holds for a `?storage=<uid>` that sits in a space. `lamin.space_uid` applies to everything else, including publish targets whose storage location is not in a space.

---

## `lamin.api` - API connection (Advanced settings)

| Setting             | Type    | Default | Env variable        |
| ------------------- | ------- | ------- | ------------------- |
| `supabase_api_url`  | String  | `null`  | `SUPABASE_API_URL`  |
| `supabase_anon_key` | String  | `null`  | `SUPABASE_ANON_KEY` |
| `max_retries`       | Integer | `3`     | `LAMIN_MAX_RETRIES` |
| `retry_delay`       | Integer | `100`   | `LAMIN_RETRY_DELAY` |
| `max_workers`       | Integer | `8`     | `LAMIN_MAX_WORKERS` |

Only needed for custom LaminHub deployments or to tune retry and parallelism behavior.
`max_workers` controls the size of the thread pool used to create artifacts in parallel.

---

## Troubleshooting

### `ERROR ~ Unknown config attribute` when using `params` in config

```
ERROR ~ Unknown config attribute `lamin.input_artifacts.rules.samplesheet.params.input` -- check config file
```

When the `lamin {}` config scope is evaluated, not all Nextflow params are necessarily available yet. Params set by a profile (`-profile test`), pulled in via `includeConfig`, or resolved only after plugin loading may be absent at that point. A bare `params.input` reference then creates an unresolved placeholder that triggers this error.

**Fix**: wrap `params.*` references in a closure so evaluation is deferred until all params have been fully resolved:

```groovy
// Safe: evaluated after all params (CLI, profiles, includeConfig) are resolved
include_paths = { params.input }
include_paths = { ["${params.outdir}/samplesheet/samplesheet.csv"] }

// May fail if the param is not yet available when this line is evaluated
include_paths = params.input
```
