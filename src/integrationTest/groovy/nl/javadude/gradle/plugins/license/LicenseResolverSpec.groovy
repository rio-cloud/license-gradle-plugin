/*
 * Copyright (C)2011 - Jeroen van Erp <jeroen@javadude.nl>
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
package nl.javadude.gradle.plugins.license

import groovy.xml.XmlSlurper
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.BuildResult
import spock.lang.Specification
import spock.lang.TempDir
import static org.assertj.core.api.Assertions.*
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

class LicenseResolverSpec extends Specification {
    @TempDir
    File testProjectDir

    def "should resolve license metadata for dependencies using real Gradle build"() {
        given:
        File buildFile = new File(testProjectDir, 'build.gradle')
        buildFile << """
plugins {
    id 'java'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.google.guava:guava:31.0.1-jre'
}
"""

        when:
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments('assemble')
            .withPluginClasspath()
            .build()

        Project project = ProjectBuilder.builder().withProjectDir(testProjectDir).build()
        project.evaluate()
        def resolver = new LicenseResolver(
            project: project,
            licenses: [:],
            aliases: [:],
            includeProjectDependencies: false,
            dependencyConfiguration: 'runtimeClasspath',
            ignoreFatalParseErrors: true,
            patternsToIgnore: []
        )
        def resultSet = resolver.provideLicenseMap4Dependencies()

        then:
        assertThat(resultSet).isNotEmpty()
        def guavaDep = resultSet.find { it.dependency.contains('com.google.guava:guava') }
        assertThat(guavaDep).isNotNull()
        assertThat(guavaDep.licenseMetadataList).isNotEmpty()
        def licenseNames = guavaDep.licenseMetadataList.collect { it.licenseName }
        assertThat(licenseNames).contains('Apache License, Version 2.0')
    }

    def "should resolve license metadata for dependencies in a multi-project build"() {
        given:
        // Create settings.gradle to include subproject
        File settingsFile = new File(testProjectDir, 'settings.gradle')
        settingsFile << "include 'subproject'"

        // Root build.gradle with project dependency and license plugin
        File buildFile = new File(testProjectDir, 'build.gradle')
        buildFile << """
plugins {
    id 'java'
    id 'com.github.hierynomus.license-report'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation project(':subproject')
    implementation 'org.apache.commons:commons-lang3:3.12.0'
}

downloadLicenses {
    includeProjectDependencies = true
    report {
        xml.enabled = true
        xml.destination = file("build/reports/license")
        html.enabled = false
        json.enabled = false
    }
    dependencyConfiguration = 'runtimeClasspath'
}
"""

        // Subproject build.gradle with external dependency
        File subDir = new File(testProjectDir, 'subproject')
        subDir.mkdirs()
        File subBuildFile = new File(subDir, 'build.gradle')
        subBuildFile << """
plugins {
    id 'java'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'com.google.guava:guava:31.0.1-jre'
    implementation 'org.apache.commons:commons-lang3:3.12.0'
}
"""

        // Add a simple Java file to the subproject
        File subSrcDir = new File(subDir, 'src/main/java/com/example')
        subSrcDir.mkdirs()
        File subJavaFile = new File(subSrcDir, 'SubClass.java')
        subJavaFile << """
package com.example;
public class SubClass {
    public static String getMessage() { return \"Hello from SubClass\"; }
}
"""

        // Add a simple Java file to the root project that uses the subproject
        File rootSrcDir = new File(testProjectDir, 'src/main/java/com/example')
        rootSrcDir.mkdirs()
        File rootJavaFile = new File(rootSrcDir, 'MainClass.java')
        rootJavaFile << """
package com.example;
public class MainClass {
    public static void main(String[] args) {
        System.out.println(SubClass.getMessage());
    }
}
"""

        when:
        BuildResult result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments('downloadLicenses')
            .withPluginClasspath()
            .build()

        // Find the XML report file dynamically
        File xmlReportDir = new File(testProjectDir, 'build/reports/license')
        assert xmlReportDir.exists() : "XML report directory missing! Gradle output:\n" + result.output
        File xmlReport = xmlReportDir.listFiles()?.find { it.name.endsWith('dependency-license.xml') }

        assert xmlReport != null : "No XML report found! Gradle output:\n" + result.output
        def xml = new XmlSlurper().parse(xmlReport)

        then:
        // Should contain the subproject dependency
        def subprojectDep = xml.dependency.find { it.@name.text().contains('subproject') }
        assert subprojectDep != null
        assert subprojectDep.license.@name.text() == 'No license found'

        // Should contain the external dependencies with correct license names and URLs
        def guavaDep = xml.dependency.find { it.@name.text().contains('guava:31.0.1-jre') }
        assert guavaDep != null
        assert guavaDep.license.@name.text() == 'Apache License, Version 2.0'
        assert guavaDep.license.@url.text() == 'http://www.apache.org/licenses/LICENSE-2.0.txt'

        def commonsDep = xml.dependency.find { it.@name.text().contains('commons-lang3:3.12.0') }
        assert commonsDep != null
        assert commonsDep.license.@name.text() == 'Apache License, Version 2.0'
        assert commonsDep.license.@url.text().contains('apache.org/licenses/LICENSE-2.0')

        def listenableFutureDep = xml.dependency.find { it.@name.text().contains('listenablefuture') }
        assert listenableFutureDep != null
        assert listenableFutureDep.license.@name.text() == 'The Apache Software License, Version 2.0'
        assert listenableFutureDep.license.@url.text() == 'http://www.apache.org/licenses/LICENSE-2.0.txt'

        def j2objcDep = xml.dependency.find { it.@name.text().contains('j2objc-annotations') }
        assert j2objcDep != null
        assert j2objcDep.license.@name.text() == 'The Apache Software License, Version 2.0'
        assert j2objcDep.license.@url.text() == 'http://www.apache.org/licenses/LICENSE-2.0.txt'

        def failureaccessDep = xml.dependency.find { it.@name.text().contains('failureaccess') }
        assert failureaccessDep != null
        assert failureaccessDep.license.@name.text() == 'The Apache Software License, Version 2.0'
        assert failureaccessDep.license.@url.text() == 'http://www.apache.org/licenses/LICENSE-2.0.txt'

        def jsr305Dep = xml.dependency.find { it.@name.text().contains('jsr305') }
        assert jsr305Dep != null
        assert jsr305Dep.license.@name.text() == 'The Apache Software License, Version 2.0'
        assert jsr305Dep.license.@url.text() == 'http://www.apache.org/licenses/LICENSE-2.0.txt'

        def errorproneDep = xml.dependency.find { it.@name.text().contains('error_prone_annotations') }
        assert errorproneDep != null
        assert errorproneDep.license.@name.text() == 'Apache 2.0'
        assert errorproneDep.license.@url.text() == 'http://www.apache.org/licenses/LICENSE-2.0.txt'

        def checkerQualDep = xml.dependency.find { it.@name.text().contains('checker-qual') }
        assert checkerQualDep != null
        assert checkerQualDep.license.@name.text() == 'The MIT License'
        assert checkerQualDep.license.@url.text() == 'http://opensource.org/licenses/MIT'
    }

    def "should resolve license metadata using aliases for canonicalization"() {
        given:
        File buildFile = new File(testProjectDir, 'build.gradle')
        buildFile << """
plugins {
    id 'java'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.apache.commons:commons-lang3:3.12.0' // Apache 2
    implementation 'org.apache.commons:commons-collections4:4.4' // The Apache 2
    implementation 'org.apache.commons:commons-compress:1.21' // Apache
}
"""

        when:
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments('assemble')
            .withPluginClasspath()
            .build()

        Project project = ProjectBuilder.builder().withProjectDir(testProjectDir).build()
        project.evaluate()
        def resolver = new LicenseResolver(
            project: project,
            licenses: [
                'org.apache.commons:commons-lang3:3.12.0': 'Apache 2',
                'org.apache.commons:commons-collections4:4.4': 'The Apache 2',
                'org.apache.commons:commons-compress:1.21': 'Apache'
            ],
            aliases: [
                'The Apache Software License, Version 2.0': ['Apache 2', 'The Apache 2', 'Apache']
            ],
            includeProjectDependencies: false,
            dependencyConfiguration: 'runtimeClasspath',
            ignoreFatalParseErrors: true,
            patternsToIgnore: []
        )
        def resultSet = resolver.provideLicenseMap4Dependencies()

        then:
        assertThat(resultSet).isNotEmpty()
        def commonsDeps = resultSet.findAll {
            it.dependency.contains('org.apache.commons:commons-lang3') ||
            it.dependency.contains('org.apache.commons:commons-collections4') ||
            it.dependency.contains('org.apache.commons:commons-compress')
        }
        assertThat(commonsDeps).hasSize(3)
        commonsDeps.each { dep ->
            assertThat(dep.licenseMetadataList*.licenseName).contains('The Apache Software License, Version 2.0')
        }
    }
}
