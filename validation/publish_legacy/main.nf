/*
 * nf-lamin publish validation workflow with the publishDir directive (Nextflow < 26.04)
 *
 * Publishes into a Lamin storage location given as `--output-dir lamin://...`.
 */

include { getRunUid; getTransformUid; getInstanceSlug } from 'plugin/nf-lamin'

// Where to publish: a lamin:// storage URI, e.g. lamin://laminlabs/lamin-dev?prefix=results
params.output_dir = 'results'

// An artifact URI in lamin:// format, read back to check that reading still works. Leave empty to skip.
params.input = ''

// Number of samples to publish
params.samples = 2

/*
  Writes a small report plus a directory of files, so that both a single-file publish and a
  directory publish are covered.
*/
process makeReport {
  publishDir params.output_dir, mode: 'copy', overwrite: true, saveAs: { "reports/${id}/${it}" }

  input:
  val(id)

  output:
  tuple val(id), path('report.json'), path('tables')

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

  log.info "Publishing to: ${params.output_dir}"

  // reading through lamin:// must keep working alongside writing
  if (params.input) {
    def artPath = file(params.input)
    log.info "Resolved input artifact ${params.input} to ${artPath.resolveToStorage()} (${artPath.size()} bytes)"
  }

  def ids = (1..params.samples).collect { i -> "sample_${i}" as String }
  channel.fromList(ids)
    | makeReport
    | view { id, report, tables -> "Published ${id}: ${report}, ${tables}" }
}
