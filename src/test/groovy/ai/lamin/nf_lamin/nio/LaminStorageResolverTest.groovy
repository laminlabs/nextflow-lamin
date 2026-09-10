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

package ai.lamin.nf_lamin.nio

import spock.lang.Specification

import ai.lamin.nf_lamin.hub.InstanceSettings
import ai.lamin.nf_lamin.instance.Instance

class LaminStorageResolverTest extends Specification {

    static final String INSTANCE_UID = 'InstUid00001'

    InstanceSettings settings = new InstanceSettings([
        id: '037ba1e0-8d80-4f91-a902-75a47735076a',
        owner: 'laminlabs',
        name: 'lamindata',
        schema_id: '90541d56-0ee5-4757-b93a-8afa8ace1bd1',
        api_url: 'https://api.example.org',
        lnid: INSTANCE_UID,
        storage: [
            id: '90541d56-0ee5-4757-b93a-8afa8ace1bd1',
            lnid: 'DefaultSt001',
            root: 's3://lamindata',
            type: 's3',
            region: 'us-east-1',
            is_default: true,
        ],
    ])

    Instance instance = Mock(Instance) {
        getSettings() >> settings
    }

    LaminStorageResolver resolver = new LaminStorageResolver()

    Map storageRecord(Map overrides = [:]) {
        [id: 7, uid: 'JwMEKs04D9WJ', root: 's3://lamin-eu/JwMEKs04D9WJ', type: 's3', region: 'eu-central-1',
         instance_uid: INSTANCE_UID, space_id: 1] + overrides
    }

    def "uses the default storage when neither space nor storage is given"() {
        when:
        def target = resolver.resolve(instance, LaminUriParser.parse('lamin://laminlabs/lamindata?prefix=results'))

        then:
        0 * instance.getRecord(_)
        0 * instance.getRecords(_)
        target.storageRoot == 's3://lamindata'
        target.storageUid == 'DefaultSt001'
        target.type == 's3'
        target.region == 'us-east-1'
        target.spaceId == null
    }

    def "looks up an explicit storage by uid"() {
        when:
        def target = resolver.resolve(instance, LaminUriParser.parse('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ'))

        then:
        1 * instance.getRecord({ Map args -> args.modelName == 'storage' && args.idOrUid == 'JwMEKs04D9WJ' }) >> storageRecord()
        target.storageRoot == 's3://lamin-eu/JwMEKs04D9WJ'
        target.storageUid == 'JwMEKs04D9WJ'
        target.storageId == 7
        target.region == 'eu-central-1'
    }

    def "a storage in the default space leaves the space to the plugin config"() {
        when:
        def target = resolver.resolve(instance, LaminUriParser.parse('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ'))

        then:
        1 * instance.getRecord(_) >> storageRecord(space_id: 1)
        target.spaceId == null
    }

    def "a storage in a space decides the space"() {
        when:
        def target = resolver.resolve(instance, LaminUriParser.parse('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ'))

        then:
        1 * instance.getRecord(_) >> storageRecord(space_id: 5)
        target.spaceId == 5
    }

    def "picks the lowest-id managed storage of a space when only a space is given"() {
        when:
        def target = resolver.resolve(instance, LaminUriParser.parse('lamin://laminlabs/lamindata?space=Sp4ce0000001'))

        then:
        1 * instance.getRecord({ Map args -> args.modelName == 'space' && args.idOrUid == 'Sp4ce0000001' }) >> [id: 5, uid: 'Sp4ce0000001', name: 'team-a']
        1 * instance.getRecords({ Map args ->
            args.modelName == 'storage' &&
            args.limit == 1 &&
            args.filter == [and: [[space_id: [eq: 5]], [instance_uid: [eq: INSTANCE_UID]]]] &&
            args.orderBy == [[field: 'id', descending: false]]
        }) >> [storageRecord(space_id: 5)]
        target.storageUid == 'JwMEKs04D9WJ'
        target.spaceId == 5
        target.spaceUid == 'Sp4ce0000001'
    }

    def "fails when a space has no storage location"() {
        when:
        resolver.resolve(instance, LaminUriParser.parse('lamin://laminlabs/lamindata?space=Sp4ce0000001'))

        then:
        1 * instance.getRecord(_) >> [id: 5, uid: 'Sp4ce0000001']
        1 * instance.getRecords(_) >> []
        def e = thrown(IllegalArgumentException)
        e.message.contains("No storage location found for space 'Sp4ce0000001'")
    }

    def "fails when space and storage disagree"() {
        when:
        resolver.resolve(instance, LaminUriParser.parse('lamin://laminlabs/lamindata?space=Sp4ce0000001&storage=JwMEKs04D9WJ'))

        then:
        1 * instance.getRecord({ Map args -> args.modelName == 'space' }) >> [id: 5, uid: 'Sp4ce0000001']
        1 * instance.getRecord({ Map args -> args.modelName == 'storage' }) >> storageRecord(space_id: 9)
        def e = thrown(IllegalArgumentException)
        e.message.contains("belongs to space")
    }

    def "fails when the storage is managed by another instance"() {
        when:
        resolver.resolve(instance, LaminUriParser.parse('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ'))

        then:
        1 * instance.getRecord(_) >> storageRecord(instance_uid: 'OtherInst001')
        def e = thrown(IllegalArgumentException)
        e.message.contains("managed by instance 'OtherInst001'")
    }

    def "fails when the storage uid is unknown"() {
        when:
        resolver.resolve(instance, LaminUriParser.parse('lamin://laminlabs/lamindata?storage=N0tThere0001'))

        then:
        1 * instance.getRecord(_) >> null
        def e = thrown(IllegalArgumentException)
        e.message.contains("No storage with uid 'N0tThere0001'")
    }

    def "fails when the space uid is unknown"() {
        when:
        resolver.resolve(instance, LaminUriParser.parse('lamin://laminlabs/lamindata?space=N0tThere0001'))

        then:
        1 * instance.getRecord(_) >> null
        def e = thrown(IllegalArgumentException)
        e.message.contains("No space with uid 'N0tThere0001'")
    }

    def "rejects artifact URIs"() {
        when:
        resolver.resolve(instance, LaminUriParser.parse('lamin://laminlabs/lamindata/artifact/uid123'))

        then:
        thrown(IllegalArgumentException)
    }

    def "caches the lookup per target"() {
        given:
        def uri = LaminUriParser.parse('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ&prefix=a')
        def sameTargetOtherPrefix = LaminUriParser.parse('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ&prefix=b')

        when:
        def first = resolver.resolve(instance, uri)
        def second = resolver.resolve(instance, sameTargetOtherPrefix)

        then:
        1 * instance.getRecord(_) >> storageRecord()
        second.is(first)
    }

    def "clear() drops the cache"() {
        given:
        def uri = LaminUriParser.parse('lamin://laminlabs/lamindata?storage=JwMEKs04D9WJ')

        when:
        resolver.resolve(instance, uri)
        resolver.clear()
        resolver.resolve(instance, uri)

        then:
        2 * instance.getRecord(_) >> storageRecord()
    }
}
