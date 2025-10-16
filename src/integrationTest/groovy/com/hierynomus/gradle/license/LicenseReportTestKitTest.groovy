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
package com.hierynomus.gradle.license

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.StandardOpenOption

class LicenseReportTestKitTest extends Specification {
    @TempDir
    File testProjectDir
    File buildFile
    File outputDir

    def setup() {
        buildFile = new File(testProjectDir, "build.gradle")
        outputDir = new File(testProjectDir, "build/reports/license")
        buildFile.text = """
plugins {
    id 'java'
    id 'cloud.rio.license-report'
}
group = 'testGroup'
version = '1.5'
repositories { mavenCentral() }
dependencies {
//    implementation 'org.apache.commons:commons-lang3:3.12.0'
}
downloadLicenses {
    licenses = ["testDependency.jar": license("Apache 2")]
    report {
        xml.enabled = true
        xml.destination = file("build/reports/license")
        html.enabled = false
        json.enabled = true
        json.destination = file("build/reports/license")
    }
    dependencyConfiguration = 'runtimeClasspath'
}
""".stripIndent()
    }

    def cleanup() {
        // No need to delete testProjectDir, @TempDir handles cleanup
    }

    def "should run downloadLicenses task successfully"() {
        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments('downloadLicenses')
                .withPluginClasspath()
                .build()
        then:
        result.task(":downloadLicenses").outcome == TaskOutcome.SUCCESS
        outputDir.exists()
    }

    def "should report on dependencies in subprojects when in multimodule build"() {
        given:
        def subProjectDir = new File(testProjectDir, "subproject")
        Files.createDirectories(subProjectDir.toPath())
        def subProjectBuildFile = new File(subProjectDir, "build.gradle")
        Files.write(subProjectBuildFile.toPath(), """
plugins {
    id 'java'
}
group = 'testSubGroup'
version = '1.7'
repositories { mavenCentral() }
dependencies {
    implementation 'org.jboss.logging:jboss-logging:3.1.3.GA'
    implementation 'com.google.guava:guava:15.0'
}
""".stripIndent().getBytes())
        def settingsFile = new File(testProjectDir, "settings.gradle")
        Files.write(settingsFile.toPath(), "include 'subproject'\n".getBytes())
        Files.write(buildFile.toPath(), ("""
downloadLicenses {
    licenses = [
        'com.google.guava:guava:15.0': license('MY_LICENSE', 'MY_URL'),
        'org.jboss.logging:jboss-logging:3.1.3.GA': license('MY_LICENSE', 'MY_URL')
    ]   
}
""").getBytes(), StandardOpenOption.APPEND)
        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments('downloadLicenses')
                .withPluginClasspath()
                .build()
        then:
        result.task(":downloadLicenses").outcome == TaskOutcome.SUCCESS
        def xmlReport = outputDir.toPath().resolve("license-dependency.xml")
        xmlReport.toFile().exists()
        def xmlContent = new String(Files.readAllBytes(xmlReport))
        xmlContent.contains("MY_LICENSE")
        xmlContent.contains("MY_URL")
        xmlContent.contains("jboss-logging-3.1.3.GA.jar")
        xmlContent.contains("guava-15.0.jar")
    }

    def "should report project dependency if license specified"() {
        given:
        def subProjectDir = new File(testProjectDir, "subproject")
        Files.createDirectories(subProjectDir.toPath())
        def subProjectBuildFile = new File(subProjectDir, "build.gradle")
        Files.write(subProjectBuildFile.toPath(), """
plugins {
    id 'java'
}
group = 'testSubGroup'
version = '1.7'
repositories { mavenCentral() }
""".stripIndent().getBytes())
        def settingsFile = new File(testProjectDir, "settings.gradle")
        Files.write(settingsFile.toPath(), "include 'subproject'\n".getBytes())
        Files.write(buildFile.toPath(), ("""
dependencies {
    implementation project(':subproject')
}
downloadLicenses.licenses = [
    'testSubGroup:subproject:1.7' : 'SbPrj license'
]
downloadLicenses.includeProjectDependencies = true
""").getBytes(), StandardOpenOption.APPEND)
        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments('downloadLicenses')
                .withPluginClasspath()
                .build()
        then:
        result.task(":downloadLicenses").outcome == TaskOutcome.SUCCESS
        def xmlReport = outputDir.toPath().resolve("license-dependency.xml")
        xmlReport.toFile().exists()
        def xmlContent = new String(Files.readAllBytes(xmlReport))
        xmlContent.contains("subproject-1.7.jar")
        xmlContent.contains("SbPrj license")
    }

    def "should report project dependency if no license specified"() {
        given:
        def subProjectDir = new File(testProjectDir, "subproject")
        Files.createDirectories(subProjectDir.toPath())
        def subProjectBuildFile = new File(subProjectDir, "build.gradle")
        Files.write(subProjectBuildFile.toPath(), """
plugins {
    id 'java'
}
group = 'testSubGroup'
version = '1.7'
repositories { mavenCentral() }
""".stripIndent().getBytes())
        def settingsFile = new File(testProjectDir, "settings.gradle")
        Files.write(settingsFile.toPath(), "include 'subproject'\n".getBytes())
        Files.write(buildFile.toPath(), ("""
dependencies {
    implementation project(':subproject')
}
downloadLicenses.includeProjectDependencies = true
""").getBytes(), StandardOpenOption.APPEND)
        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments('downloadLicenses')
                .withPluginClasspath()
                .build()
        then:
        result.task(":downloadLicenses").outcome == TaskOutcome.SUCCESS
        def xmlReport = outputDir.toPath().resolve("license-dependency.xml")
        xmlReport.toFile().exists()
        def xmlContent = new String(Files.readAllBytes(xmlReport))
        xmlContent.contains("subproject-1.7.jar")
        xmlContent.contains("No license found")
    }

    def "should handle poms with xlint args"() {
        given:
        Files.write(buildFile.toPath(), ("""
dependencies {
    implementation 'com.sun.mail:javax.mail:1.5.4'
}
""").getBytes(), StandardOpenOption.APPEND)
        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments('downloadLicenses')
                .withPluginClasspath()
                .build()
        then:
        outputDir.listFiles().length == 4
        // Only check that the report contains two dependencies and two licenses
        def xmlReport = outputDir.toPath().resolve("license-dependency.xml")
        def xmlContent = new String(Files.readAllBytes(xmlReport))
        xmlContent.count('<dependency>') == 2
        xmlContent.count('<license ') == 2
    }

    def "should ignore fatal pom parse errors"() {
        given:
        Files.write(buildFile.toPath(), ("""
dependencies {
    // This depends on 'org.codehouse.plexus:plexus:1.0.4' whose POM is malformed.
    implementation 'org.apache.maven:maven-ant-tasks:2.1.3'
}

downloadLicenses.ignoreFatalParseErrors = true
""").getBytes(), StandardOpenOption.APPEND)
        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments('downloadLicenses')
                .withPluginClasspath()
                .build()
        then:
        def xmlReport = outputDir.toPath().resolve("license-dependency.xml")
        def xmlContent = new String(Files.readAllBytes(xmlReport))
        xmlContent.count('<dependency>') == 24
    }

    def "should not report on dependencies in other configurations"() {
        given:
        Files.write(buildFile.toPath(), ("""
dependencies {
    testImplementation project.files('testDependency.jar')
    testRuntimeOnly 'com.google.guava:guava:15.0'
    runtimeOnly 'org.apache.ivy:ivy:2.3.0',
                'org.jboss.logging:jboss-logging:3.1.3.GA'
}
""").getBytes(), StandardOpenOption.APPEND)
        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments('downloadLicenses')
                .withPluginClasspath()
                .build()
        then:
        // Dynamically find the XML report file
        def xmlFile = outputDir.listFiles().find { it.name.endsWith('.xml') }
        assert xmlFile != null : "No XML report file found in ${outputDir}"
        def xmlContent = new String(Files.readAllBytes(xmlFile.toPath()))
        // Only runtimeOnly dependencies should be present (by jar file name)
        assert xmlContent.contains('ivy-2.3.0.jar')
        assert xmlContent.contains('jboss-logging-3.1.3.GA.jar')
        assert !xmlContent.contains('testDependency.jar')
        assert !xmlContent.contains('guava-15.0.jar')
        // There should be exactly 2 dependencies in the report
        assert xmlContent.count('<dependency') == 2
    }

    def "should support string-to-list license aliases"() {
        given:
        Files.write(buildFile.toPath(), (("""
downloadLicenses {
    aliases = [
        'The Apache Software License, Version 2.0': ['Apache 2', 'The Apache 2', 'Apache']
    ]
}

downloadLicenses {
    licenses = [
        'org.apache.commons:commons-collections4:4.4': license('Apache 2'),
        'org.apache.commons:commons-compress:1.21': license('The Apache 2'),
        'org.apache.commons:commons-math3:3.6.1': 'Apache'
    ]
}

dependencies {
    implementation 'org.apache.commons:commons-collections4:4.4'
    implementation 'org.apache.commons:commons-compress:1.21'
    implementation 'org.apache.commons:commons-math3:3.6.1'
}
""").getBytes()), StandardOpenOption.APPEND)
        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments('downloadLicenses')
                .withPluginClasspath()
                .build()
        then:
        // Dynamically find the XML report file
        def xmlFile = outputDir.listFiles().find { it.name.endsWith('.xml') }
        assert xmlFile != null : "No XML report file found in ${outputDir}"
        def xmlContent = new String(Files.readAllBytes(xmlFile.toPath()))
        assert xmlContent.contains('The Apache Software License, Version 2.0')
        assert xmlContent.contains('commons-collections4-4.4.jar')
        assert xmlContent.contains('commons-compress-1.21.jar')
        assert xmlContent.contains('commons-math3-3.6.1.jar')
        assert xmlContent.count('<dependency') == 3
    }

    def "should support licenseMetadata-to-list license aliases"() {
        given:
        Files.write(buildFile.toPath(), ("""
downloadLicenses {
    aliases[license('The Apache Software License, Version 2.0', 'MY_URL')] = ['Apache 2', 'The Apache 2', 'Apache']
}

downloadLicenses {
    licenses = [
        'org.apache.commons:commons-lang3:3.12.0': license('Apache 2'),
        'org.apache.commons:commons-collections4:4.4': license('The Apache 2'),
        'org.apache.commons:commons-compress:1.21': 'Apache'
    ]
}

dependencies {
    implementation 'org.apache.commons:commons-lang3:3.12.0'
    implementation 'org.apache.commons:commons-collections4:4.4'
    implementation 'org.apache.commons:commons-compress:1.21'
}
""").getBytes(), StandardOpenOption.APPEND)
        when:
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir)
                .withArguments('downloadLicenses')
                .withPluginClasspath()
                .build()
        then:
        // Dynamically find the XML report file
        def xmlFile = outputDir.listFiles().find { it.name.endsWith('.xml') }
        assert xmlFile != null : "No XML report file found in ${outputDir}"
        def xmlContent = new String(Files.readAllBytes(xmlFile.toPath()))
        assert xmlContent.contains('The Apache Software License, Version 2.0')
        assert xmlContent.count('<dependency') == 3
    }
}
