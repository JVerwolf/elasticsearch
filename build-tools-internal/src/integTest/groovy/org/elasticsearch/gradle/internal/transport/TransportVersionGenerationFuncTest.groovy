/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.gradle.internal.transport

import org.elasticsearch.gradle.Version
import org.elasticsearch.gradle.VersionProperties
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome

class TransportVersionGenerationFuncTest extends AbstractTransportVersionFuncTest {

    def runGenerateAndValidateTask(String... additionalArgs) {
        List<String> args = new ArrayList<>()
        args.add(":myserver:validateTransportVersionResources")
        args.add(":myserver:generateTransportVersionDefinition")
        args.addAll(additionalArgs);
        return gradleRunner(args.toArray())
    }

    def runGenerateTask(String... additionalArgs) {
        List<String> args = new ArrayList<>()
        args.add(":myserver:generateTransportVersionDefinition")
        args.addAll(additionalArgs);
        return gradleRunner(args.toArray())
    }

    def runValidateTask() {
        return gradleRunner(":myserver:validateTransportVersionResources")
    }

    void assertGenerateSuccess(BuildResult result) {
        assert result.task(":myserver:generateTransportVersionDefinition").outcome == TaskOutcome.SUCCESS
    }

    void assertGenerateFailure(BuildResult result) {
        assert result.task(":myserver:generateTransportVersionDefinition").outcome == TaskOutcome.FAILED
    }

    void assertValidateSuccess(BuildResult result) {
        assert result.task(":myserver:validateTransportVersionResources").outcome == TaskOutcome.SUCCESS
    }

    void assertGenerateAndValidateSuccess(BuildResult result) {
        assertGenerateSuccess(result)
        assertValidateSuccess(result)
    }

    def setup() {

    }

    def "setup is valid"() {
        when:
        def result = runGenerateAndValidateTask().build()

        then:
        assertGenerateAndValidateSuccess(result)
    }

    def "a definition should be generated when specified by an arg but no code reference exists yet"() {
        when:
        def result = runGenerateTask("--name=new_tv", "--branches=9.2").build()

        then:
        assertGenerateSuccess(result)
        assertReferableDefinition("new_tv", "8124000")
        assertUpperBound("9.2", "new_tv,8124000")

        when:
        referencedTransportVersion("new_tv")
        def validateResult = runValidateTask().build()

        then:
        assertValidateSuccess(validateResult)
    }

    def "a definition should be generated when only a code reference exists"() {
        given:
        referencedTransportVersion("new_tv")

        when:
        def result = runGenerateAndValidateTask("--branches=9.2").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinition("new_tv", "8124000")
        assertUpperBound("9.2", "new_tv,8124000")
    }

    def "generation fails if branches omitted outside CI"() {
        when:
        def generateResult = runGenerateTask("--name=no_branches").buildAndFail()
        def validateResult = runValidateTask().build()

        then:
        assertGenerateFailure(generateResult)
        assertOutputContains(generateResult.output, "When running outside CI, --branches must be specified")
        assertValidateSuccess(validateResult)
    }

    def "invalid changes to a latest file should be reverted"() {
        given:
        transportVersionUpperBound("9.2", "modification", "9000000")

        when:
        def result = runGenerateAndValidateTask("--branches=9.2").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertUpperBound("9.2", "existing_92,8123000")
    }

    def "invalid changes to multiple latest files should be reverted"() {
        given:
        transportVersionUpperBound("9.2", "modification", "9000000")
        transportVersionUpperBound("9.1", "modification", "9000000")

        when:
        def result = runGenerateAndValidateTask("--branches=9.2,9.1").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertUpperBound("9.2", "existing_92,8123000")
        assertUpperBound("9.1", "existing_92,8012001")
    }

    def "unreferenced referable definition should be reverted"() {
        given:
        referableTransportVersion("test_tv", "8124000")
        transportVersionUpperBound("9.2", "test_tv", "8124000")

        when:
        def result = runGenerateAndValidateTask().build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinitionDoesNotExist("test_tv")
        assertUpperBound("9.2", "existing_92,8123000")
    }

    def "unreferenced referable definition in multiple branches should be reverted"() {
        given:
        referableTransportVersion("test_tv", "8124000")
        transportVersionUpperBound("9.2", "test_tv", "8124000")
        transportVersionUpperBound("9.1", "test_tv", "8012002")

        when:
        def result = runGenerateAndValidateTask().build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinitionDoesNotExist("test_tv")
        assertUpperBound("9.2", "existing_92,8123000")
        assertUpperBound("9.1", "existing_92,8012001")
    }

    def "a reference can be renamed"() {
        given:
        referableTransportVersion("first_tv", "8124000")
        transportVersionUpperBound("9.2", "first_tv", "8124000")
        referencedTransportVersion("renamed_tv")

        when:
        def result = runGenerateAndValidateTask("--branches=9.2").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinitionDoesNotExist("first_tv")
        assertReferableDefinition("renamed_tv", "8124000")
        assertUpperBound("9.2", "renamed_tv,8124000")
    }

    def "a reference with a patch version can be renamed"() {
        given:
        referableTransportVersion("first_tv", "8124000,8012002")
        transportVersionUpperBound("9.2", "first_tv", "8124000")
        transportVersionUpperBound("9.1", "first_tv", "8012002")
        referencedTransportVersion("renamed_tv")

        when:
        def result = runGenerateAndValidateTask("--branches=9.2,9.1").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinitionDoesNotExist("first_tv")
        assertReferableDefinition("renamed_tv", "8124000,8012002")
        assertUpperBound("9.2", "renamed_tv,8124000")
        assertUpperBound("9.1", "renamed_tv,8012002")
    }

    def "a missing definition will be regenerated"() {
        given:
        referencedTransportVersion("test_tv")
        transportVersionUpperBound("9.2", "test_tv", "8124000")

        when:
        def result = runGenerateAndValidateTask("--branches=9.2").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinition("test_tv", "8124000")
        assertUpperBound("9.2", "test_tv,8124000")
    }

    def "a latest file can be regenerated"() {
        given:
        referableAndReferencedTransportVersion("test_tv", "8124000")

        when:
        def result = runGenerateAndValidateTask("--branches=9.2").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertUpperBound("9.2", "test_tv,8124000")
    }

    def "branches for definition can be removed"() {
        given:
        // previously generated with 9.1 and 9.2
        referableAndReferencedTransportVersion("test_tv", "8124000,8012002")
        transportVersionUpperBound("9.2", "test_tv", "8124000")
        transportVersionUpperBound("9.1", "test_tv", "8012002")

        when:
        def result = runGenerateAndValidateTask("--branches=9.2").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinition("test_tv", "8124000")
        assertUpperBound("9.2", "test_tv,8124000")
        assertUpperBound("9.1", "existing_92,8012001")
    }

    def "branches for definition can be added"() {
        given:
        referableAndReferencedTransportVersion("test_tv", "8124000")
        transportVersionUpperBound("9.2", "test_tv", "8124000")

        when:
        def result = runGenerateAndValidateTask("--branches=9.2,9.1").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinition("test_tv", "8124000,8012002")
        assertUpperBound("9.2", "test_tv,8124000")
        assertUpperBound("9.1", "test_tv,8012002")
    }

    def "unreferenced definitions are removed"() {
        given:
        referableTransportVersion("test_tv", "8124000,8012002")
        transportVersionUpperBound("9.2", "test_tv", "8124000")
        transportVersionUpperBound("9.1", "test_tv", "8012002")

        when:
        def result = runGenerateAndValidateTask().build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinitionDoesNotExist("test_tv")
        assertUpperBound("9.2", "existing_92,8123000")
        assertUpperBound("9.1", "existing_92,8012001")
    }

    def "merge conflicts in latest files can be regenerated"() {
        given:
        file("myserver/src/main/resources/transport/latest/9.2.csv").text =
            """
            <<<<<<< HEAD
            existing_92,8123000
            =======
            second_tv,8123000
            >>>>>> releaseBranch
            """.strip()
        referableAndReferencedTransportVersion("second_tv", "8123000")

        when:
        def result = runGenerateAndValidateTask("--name=second_tv", "--branches=9.2").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinition("existing_92", "8123000,8012001")
        assertReferableDefinition("second_tv", "8124000")
        assertUpperBound("9.2", "second_tv,8124000")
    }

    def "branches param order can be changed"() {
        given:
        referableAndReferencedTransportVersion("test_tv", "8124000,8012002")
        transportVersionUpperBound("9.2", "test_tv", "8124000")
        transportVersionUpperBound("9.1", "test_tv", "8012002")

        when:
        def result = runGenerateAndValidateTask("--branches=9.1,9.2").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinition("test_tv", "8124000,8012002")
        assertUpperBound("9.2", "test_tv,8124000")
        assertUpperBound("9.1", "test_tv,8012002")
    }

    def "if the files for a new definition already exist, no change should occur"() {
        given:
        transportVersionUpperBound("9.2", "test_tv", "8124000")
        referableAndReferencedTransportVersion("test_tv", "8124000")


        when:
        def result = runGenerateAndValidateTask("--branches=9.2").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinition("test_tv", "8124000")
        assertUpperBound("9.2", "test_tv,8124000")
    }

    def "can add backport to an existing definition"() {
        when:
        def result = runGenerateAndValidateTask("--name=existing_92", "--branches=9.2,9.1,9.0").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinition("existing_92", "8123000,8012001,8000001")
        assertUpperBound("9.2", "existing_92,8123000")
        assertUpperBound("9.1", "existing_92,8012001")
        assertUpperBound("9.0", "existing_92,8000001")
    }


    def "a different increment can be specified"() {
        given:
        referencedTransportVersion("new_tv")
        file("myserver/build.gradle") << """
            tasks.named('validateTransportVersionResources') {
                shouldValidateDensity = false
            }
        """

        when:
        def result = runGenerateAndValidateTask("--branches=9.2", "--increment=100").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinition("new_tv", "8123100")
        assertUpperBound("9.2", "new_tv,8123100")
    }

    def "an invalid increment should fail"() {
        given:
        referencedTransportVersion("new_tv")

        when:
        def result = runGenerateTask("--branches=9.2", "--increment=0").buildAndFail()

        then:
        assertOutputContains(result.output, "Invalid increment: must be a positive integer > 0")
    }

    def "a new definition exists and is in the latest file, but the version id is wrong and needs to be updated"(){
        given:
        referableAndReferencedTransportVersion("existing_92", "1000000")
        transportVersionUpperBound("9.2", "existing_92", "1000000")

        when:
        def result = runGenerateAndValidateTask("--branches=9.2").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinition("existing_92", "8123000,8012001")
        assertUpperBound("9.2", "existing_92,8123000")
        assertUpperBound("9.1", "existing_92,8012001")
    }

    def "branches=main should be translated"() {
        given:
        execute("git checkout main")
        Version esVersion = VersionProperties.getElasticsearchVersion()
        String releaseBranch = esVersion.getMajor() + "." + esVersion.getMinor()
        unreferableTransportVersion("initial_main", "9000000")
        transportVersionUpperBound(releaseBranch, "initial_main", "9000000")
        file('myserver/build.gradle') << """
            tasks.named('generateTransportVersionDefinition') {
                getMainReleaseBranch().unset()
            }
        """
        execute('git commit -a -m "setup_main"')
        execute('git checkout -b new_branch')
        referencedTransportVersion("new_tv")

        when:
        def result = runGenerateAndValidateTask("--branches=main").build()

        then:
        assertGenerateAndValidateSuccess(result)
        assertReferableDefinition("new_tv", "9001000")
        assertUpperBound(releaseBranch, "new_tv,9001000")
    }
}
