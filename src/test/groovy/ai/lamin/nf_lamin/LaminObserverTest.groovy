/*
 * Copyright 2025, Lamin Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.lamin.nf_lamin

import java.lang.reflect.Field
import java.nio.file.Path

import nextflow.Session
import nextflow.trace.event.FilePublishEvent
import nextflow.trace.event.WorkflowOutputEvent
import spock.lang.Specification

import ai.lamin.nf_lamin.instance.Instance

class LaminObserverTest extends Specification {

    def setup() {
        LaminRunManager.instance.reset()
    }

    private static void injectField(Object target, String fieldName, Object value) {
        Field field = target.class.getDeclaredField(fieldName)
        field.accessible = true
        field.set(target, value)
    }

    private Path remotePath(String uri) {
        def path = Stub(Path)
        path.toUri() >> new URI(uri)
        path.toAbsolutePath() >> path
        path.normalize() >> path
        return path
    }

    def 'should create the observer instance' () {
        given:
        def factory = new LaminFactory()

        when:
        def result = factory.create(Mock(Session))

        then:
        result.size() == 1
        result.first() instanceof LaminObserver
    }

    def 'registers the index file of an output block once it is published'() {
        given:
        def mockInstance = Mock(Instance)
        def manager = LaminRunManager.instance
        manager.setCurrentInstance(mockInstance)
        injectField(manager, 'config', new LaminConfig([instance: 'org/inst', api_key: 'key']))
        injectField(manager, 'run', [uid: 'R1', id: 1])
        mockInstance.getArtifactByPath(_) >> null

        def observer = new LaminObserver()
        injectField(observer, 'trackingEnabled', true)
        def index = remotePath('s3://bucket/results/reports/index.csv')

        when: 'the output block is announced; Nextflow only writes the index file afterwards'
        observer.onWorkflowOutput(new WorkflowOutputEvent('reports', [], index))

        then:
        0 * mockInstance.createArtifact(_)

        when:
        observer.onFilePublish(new FilePublishEvent(null, index, []))
        manager.awaitArtifactTasks()

        then:
        1 * mockInstance.createArtifact({ Map args ->
            args.path == 's3://bucket/results/reports/index.csv' && (args.description as String).contains("'reports'")
        }) >> [uid: 'A1', id: 11, run: 1]
    }
}
