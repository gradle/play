package com.playframework.gradle.tasks

import com.playframework.gradle.PlayMultiVersionIntegrationTest
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.VersionNumber
import spock.lang.Ignore
import spock.lang.Unroll

import static com.playframework.gradle.fixtures.Repositories.playRepositories
import static com.playframework.gradle.fixtures.file.FileFixtures.assertHasNotChangedSince
import static com.playframework.gradle.fixtures.file.FileFixtures.snapshot
import static com.playframework.gradle.plugins.PlayTwirlPlugin.TWIRL_COMPILE_TASK_NAME

class TwirlCompileIntegrationTest extends PlayMultiVersionIntegrationTest {

    private static final TWIRL_COMPILE_TASK_PATH = ":$TWIRL_COMPILE_TASK_NAME".toString()
    private static final SCALA_COMPILE_TASK_NAME = 'compileScala'
    private static final SCALA_COMPILE_TASK_PATH = ":$SCALA_COMPILE_TASK_NAME".toString()
    File destinationDir

    def setup() {
        destinationDir = file("build/src/play/twirl/views")
        settingsFile << """ rootProject.name = 'twirl-play-app' """
        buildFile << """
            plugins {
                id 'com.playframework.play-application'
            }

            ${playRepositories()}
        """
        configurePlayVersionInBuildScript()
    }

    @Unroll
    def "can run TwirlCompile with #format template"() {
        given:
        twirlTemplate("test.scala.${format}") << template
        when:
        build(SCALA_COMPILE_TASK_NAME)
        then:
        def generatedFile = new File(destinationDir, "${format}/test.template.scala")
        generatedFile.isFile()
        generatedFile.text.contains("import views.${format}._")
        generatedFile.text.contains(templateFormat)

        where:
        format | templateFormat     | template
        "js"   | 'JavaScriptFormat' | '@(username: String) alert(@helper.json(username));'
        "xml"  | 'XmlFormat'        | '@(username: String) <xml> <username> @username </username>'
        "txt"  | 'TxtFormat'        | '@(username: String) @username'
        "html" | 'HtmlFormat'       | '@(username: String) <html> <body> <h1>Hello @username</h1> </body> </html>'
    }

    def "can compile custom Twirl templates"() {
        given:
        twirlTemplate("test.scala.csv") << """
            @(username: String)(content: Csv)
            # generated by @username
            @content
        """

        addCsvFormat()

        when:
        build(SCALA_COMPILE_TASK_NAME)
        then:
        def generatedFile = new File(destinationDir,"csv/test.template.scala")
        generatedFile.isFile()
        generatedFile.text.contains("import views.formats.csv._")
        generatedFile.text.contains("CsvFormat")

        // Modifying user templates causes TwirlCompile to be out-of-date
        when:
        buildFile << """
            sourceSets {
                main {
                    twirl {
                        userTemplateFormats.add(newUserTemplateFormat("unused", "views.formats.unused.UnusedFormat"))
                    }
                }
            }
        """
        and:
        BuildResult result = build(SCALA_COMPILE_TASK_NAME)
        then:
        result.task(TWIRL_COMPILE_TASK_PATH).outcome == TaskOutcome.SUCCESS
        result.task(SCALA_COMPILE_TASK_PATH).outcome == TaskOutcome.SUCCESS
    }

    def "can specify additional imports for a Twirl template"() {
        given:
        withTwirlTemplate()
        buildFile << """
            sourceSets {
                main {
                    twirl {
                        additionalImports = [ 'my.pkg._' ]
                    }
                }
            }
        """
        temporaryFolder.newFolder('app', 'my', 'pkg')
        file("app/my/pkg/MyClass.scala") << """
            package my.pkg

            object MyClass;
        """

        when:
        build(SCALA_COMPILE_TASK_NAME)
        then:
        def generatedFile = new File(destinationDir, "html/index.template.scala")
        generatedFile.isFile()
        generatedFile.text.contains("import my.pkg._")

        // Changing the imports causes TwirlCompile to be out-of-date
        when:
        buildFile << """
            sourceSets {
                main {
                    twirl {
                        additionalImports = [ 'my.pkg._', 'my.pkg.MyClass' ]
                    }
                }
            }
        """
        and:
        BuildResult result = build(SCALA_COMPILE_TASK_NAME)
        then:
        result.task(TWIRL_COMPILE_TASK_PATH).outcome == TaskOutcome.SUCCESS
        result.task(SCALA_COMPILE_TASK_PATH).outcome == TaskOutcome.SUCCESS
        generatedFile.text.contains("import my.pkg._")
        generatedFile.text.contains("import my.pkg.MyClass")
    }

    @Ignore("does not support incrementality anymore")
    def "runs compiler incrementally"() {
        when:
        withTwirlTemplate("input1.scala.html")
        then:
        build(TWIRL_COMPILE_TASK_NAME)
        and:
        new File(destinationDir, "html/input1.template.scala").isFile()
        def input1FirstCompileSnapshot = snapshot(new File(destinationDir, "html/input1.template.scala"))

        when:
        BuildResult result = build(TWIRL_COMPILE_TASK_NAME)
        then:
        result.task(TWIRL_COMPILE_TASK_PATH).outcome == TaskOutcome.UP_TO_DATE

        when:
        withTwirlTemplate("input2.scala.html")
        and:
        build(TWIRL_COMPILE_TASK_NAME)
        then:
        new File(destinationDir, "html/input1.template.scala").isFile()
        new File(destinationDir, "html/input2.template.scala").isFile()
        and:
        assertHasNotChangedSince(input1FirstCompileSnapshot, new File(destinationDir,"html/input1.template.scala"))

        when:
        file("app/views/input2.scala.html").delete()
        then:
        build(TWIRL_COMPILE_TASK_NAME)
        and:
        new File(destinationDir,"html/input1.template.scala").isFile()
    }

    @Ignore("does not support incrementality anymore")
    def "removes stale output files in incremental compile"(){
        given:
        withTwirlTemplate("input1.scala.html")
        withTwirlTemplate("input2.scala.html")
        build(TWIRL_COMPILE_TASK_PATH)

        and:
        new File(destinationDir, "html/input1.template.scala").isFile()
        new File(destinationDir, "html/input2.template.scala").isFile()
        def input1FirstCompileSnapshot = snapshot(new File(destinationDir, "html/input1.template.scala"))

        when:
        file("app/views/input2.scala.html").delete()

        then:
        build(TWIRL_COMPILE_TASK_PATH)
        and:
        new File(destinationDir, "html/input1.template.scala")
        assertHasNotChangedSince(input1FirstCompileSnapshot, new File(destinationDir, "html/input1.template.scala"))
        !new File(destinationDir, "html/input2.template.scala").isFile()
    }

    def "can build twirl source set with default Java imports" () {
        withTwirlJavaSourceSets()
        File twirlJavaDir = temporaryFolder.newFolder("twirlJava")
        withTemplateSourceExpectingJavaImports(new File(twirlJavaDir, "javaTemplate.scala.html"))
        validateThatPlayJavaDependencyIsAdded()

        when:
        BuildResult result = build "assemble"

        then:
        result.task(TWIRL_COMPILE_TASK_PATH).outcome == TaskOutcome.SUCCESS

        and:
        jar("build/libs/twirl-play-app.jar")
                .containsDescendants("html/javaTemplate.class")
    }

    def "twirl source sets default to Scala imports" () {
        File appViewsDir = temporaryFolder.newFolder('app', 'views')
        withTemplateSource(new File(appViewsDir, "index.scala.html"))
        validateThatPlayJavaDependencyIsNotAdded()
        validateThatSourceSetsDefaultToScalaImports()

        when:
        BuildResult result = build "assemble"

        then:
        result.task(TWIRL_COMPILE_TASK_PATH).outcome == TaskOutcome.SUCCESS
    }


    @Unroll
    def "has reasonable error if Twirl template is configured incorrectly with (#template)"() {
        given:
        buildFile << """
            sourceSets {
                main {
                    twirl {
                        userTemplateFormats.add(newUserTemplateFormat($template))
                    }
                }
            }
        """

        when:
        BuildResult result = buildAndFail('tasks')
        then:
        result.output.contains(errorMessage)

        where:
        template                      | errorMessage
        "null, 'CustomFormat'"        | "Custom template extension cannot be null."
        "'.ext', 'CustomFormat'"      | "Custom template extension should not start with a dot."
        "'ext', null"                 | "Custom template format type cannot be null."
    }

    def "has reasonable error if Twirl template cannot be found"() {
        twirlTemplate("test.scala.custom") << "@(username: String) Custom template, @username!"
        when:
        BuildResult result = buildAndFail(SCALA_COMPILE_TASK_NAME)
        then:
        result.output.contains("Twirl compiler could not find a matching template for 'test.scala.custom'.")
    }

    def withTemplateSource(File templateFile) {
        templateFile << """@(message: String)

            <h1>@message</h1>

        """
    }

    def twirlTemplate(String fileName) {
        File appViewsDir = file('app/views')

        if (!appViewsDir.isDirectory()) {
            temporaryFolder.newFolder('app', 'views')
        }

        new File(appViewsDir, fileName)
    }

    def withTwirlTemplate(String fileName = "index.scala.html") {
        File appViewsDir = file('app/views')

        if (!appViewsDir.isDirectory()) {
            temporaryFolder.newFolder('app', 'views')
        }

        def templateFile = new File(appViewsDir, fileName)
        templateFile.createNewFile()
        withTemplateSource(templateFile)
    }

    def withTemplateSourceExpectingJavaImports(File templateFile) {
        templateFile << """
            <!DOCTYPE html>
            <html>
                <body>
                  <p>@UUID.randomUUID().toString()</p>
                </body>
            </html>
        """
    }

    def withTwirlJavaSourceSets() {
        buildFile << """
            sourceSets {
                main {
                    twirl {
                        defaultImports = com.playframework.gradle.sourcesets.TwirlImports.JAVA
                        srcDirs = ["twirlJava"]
                    }
                }
            }
        """
    }

    def validateThatPlayJavaDependencyIsAdded() {
        validateThatPlayJavaDependency(true)
    }

    def validateThatPlayJavaDependencyIsNotAdded() {
        validateThatPlayJavaDependency(false)
    }

    def validateThatPlayJavaDependency(boolean shouldBePresent) {
        buildFile << """
            $TWIRL_COMPILE_TASK_NAME {
                doFirst {
                    assert ${shouldBePresent ? "" : "!"} configurations.play.dependencies.any {
                        it.group == "com.typesafe.play" &&
                        it.name == "play-java_\${play.platform.scalaVersion.get()}" &&
                        it.version == play.platform.playVersion.get()
                    }
                }
            }
        """
    }

    def validateThatSourceSetsDefaultToScalaImports() {
        buildFile << """
            $TWIRL_COMPILE_TASK_NAME {
                doFirst {
                    assert defaultImports.get() == com.playframework.gradle.sourcesets.TwirlImports.SCALA
                    sourceSets {
                        main {
                            twirl {
                                assert defaultImports.get() == com.playframework.gradle.sourcesets.TwirlImports.SCALA
                            }
                        }
                    }
                }
            }
        """
    }

    private void addCsvFormat() {
        buildFile << """
            sourceSets {
                main {
                    twirl {
                        userTemplateFormats.add(newUserTemplateFormat("csv", "views.formats.csv.CsvFormat", "views.formats.csv._"))
                    }
                }
            }
        """
        temporaryFolder.newFolder('app', 'views', 'formats', 'csv')

        if (versionNumber < VersionNumber.parse("2.3.0")) {
            file("app/views/formats/csv/Csv.scala") << """
package views.formats.csv

import play.api.http.ContentTypeOf
import play.api.mvc.Codec
import play.api.templates.BufferedContent
import play.templates.Format

class Csv(buffer: StringBuilder) extends BufferedContent[Csv](buffer) {
  val contentType = Csv.contentType
}

object Csv {
  val contentType = "text/csv"
  implicit def contentTypeCsv(implicit codec: Codec): ContentTypeOf[Csv] = ContentTypeOf[Csv](Some(Csv.contentType))

  def apply(text: String): Csv = new Csv(new StringBuilder(text))

  def empty: Csv = new Csv(new StringBuilder)
}

object CsvFormat extends Format[Csv] {
  def raw(text: String): Csv = Csv(text)
  def escape(text: String): Csv = Csv(text)
}
"""
        } else {
            file("app/views/formats/csv/Csv.scala") << """
package views.formats.csv

import scala.collection.immutable
import play.twirl.api.BufferedContent
import play.twirl.api.Format

class Csv private (elements: immutable.Seq[Csv], text: String) extends BufferedContent[Csv](elements, text) {
  def this(text: String) = this(Nil, if (text eq null) "" else text)
  def this(elements: immutable.Seq[Csv]) = this(elements, "")

  /**
   * Content type of CSV.
   */
  def contentType = "text/csv"
}

/**
 * Helper for CSV utility methods.
 */
object Csv {

  /**
   * Creates an CSV fragment with initial content specified.
   */
  def apply(text: String): Csv = {
    new Csv(text)
  }

}

/**
 * Formatter for CSV content.
 */
object CsvFormat extends Format[Csv] {

  /**
   * Creates a CSV fragment.
   */
  def raw(text: String) = Csv(text)

  /**
   * Creates an escaped CSV fragment.
   */
  def escape(text: String) = Csv(text)

  /**
   * Generate an empty CSV fragment
   */
  val empty: Csv = new Csv("")

  /**
   * Create a CSV Fragment that holds other fragments.
   */
  def fill(elements: immutable.Seq[Csv]): Csv = new Csv(elements)
}
        """
        }
    }
}
