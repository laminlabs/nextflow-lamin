/*
 * nf-lamin publish validation workflow with Nextflow 26.04+ (strict syntax)
 *
 * Publishes into a Lamin storage location via `-output-dir lamin://...`. Writes a file and a
 * directory per sample, plus a CSV index, so the upload, directory and append paths of the
 * lamin-s3 provider are all exercised.
 */

nextflow.enable.types = true

include { getRunUid; getTransformUid; getInstanceSlug } from 'plugin/nf-lamin'

params {
  // An artifact URI in lamin:// format, read back to check that reading still works. Leave
  // empty to skip.
  input: String = ''

  // Number of samples to publish
  samples: Integer = 2
}

record Sample {
  id: String
  report: Path
  tables: Path
}

/*
  Writes a small report plus a directory of files, so that both a single-file publish and a
  directory publish are covered.
*/
process makeReport {
  input:
  id: String

  output:
  record(
    id: id,
    report: file('report.json'),
    tables: file('tables')
  )

  script:
  def metadata = [
    id: id,
    runUid: getRunUid(),
    transformUid: getTransformUid(),
    instance: getInstanceSlug(),
    datetime: new Date().toString()
  ]
  """
cat > report.json << EOF
${groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(metadata))}
EOF
mkdir tables
printf 'gene,count\\nA,1\\nB,2\\n' > tables/counts.csv
printf 'sample,${id}\\n' > tables/meta.csv
  """
}

workflow {
  main:

  log.info "Publishing to: ${workflow.outputDir} (${workflow.outputDir.class.simpleName})"

  // reading through lamin:// must keep working alongside writing
  if (params.input) {
    def artPath = file(params.input)
    log.info "Resolved input artifact ${params.input} to ${artPath.resolveToStorage()} (${artPath.size()} bytes)"
  }

  def ids = (1..params.samples).collect { i -> "sample_${i}" as String }
  ch_reports = makeReport(channel.fromList(ids))

  publish:
  reports = ch_reports
}

output {
  reports {
    path { r -> "reports/${r.id}/" }
    index {
      path 'reports/index.csv'
    }
    mode 'copy'
  }
}
