/**
 * Copyright (C) 2013 Typesafe <http://typesafe.com/>
 */
package activator

import xsbti.AppConfiguration
import activator.properties.ActivatorProperties.SCRIPT_NAME
import activator.cache.Actions.cloneTemplate
import java.io.File
import sbt.complete.{ Parser, Parsers }
import scala.concurrent.Await
import scala.concurrent.duration._
import activator.cache.{ TemplateMetadata, TemplateCache }

object ActivatorCli extends ActivatorCliHelper {
  case class ProjectInfo(projectName: String = "N/A", templateName: String = "N/A", file: Option[File] = None)

  def apply(configuration: AppConfiguration): Int = try withContextClassloader {
    // TODO - move this into a common shared location between CLI and GUI.
    val cache = UICacheHelper.makeDefaultCache(ActivatorCliHelper.system)
    val metadata = TemplateHandler.downloadTemplates(cache, ActivatorCliHelper.defaultDuration)

    // Handling input based on length (yes, it is brittle to do argument parsing like this...):
    // length = 2 : "new" and "project name" => generate project automatically, but query for template to use
    // length = 3 : "new", "project name" and "template name" => generate project and template automatically
    // length != (2 || 3) : query for both project name and template
    val projectInfo =
      configuration.arguments().length match {
        case 2 =>
          val pName = configuration.arguments()(1)
          createFile(pName) match {
            case f @ Some(_) =>
              ProjectInfo(
                projectName = pName,
                templateName = TemplateHandler.getTemplateName(metadata.map(_.name).toSeq.distinct),
                file = f)
            case None => ProjectInfo()
          }
        case 3 =>
          val pName = configuration.arguments()(1)
          val tName = configuration.arguments()(2)
          ProjectInfo(
            projectName = pName,
            templateName = tName,
            file = createFile(pName))
        case _ =>
          val pName = getApplicationName()
          createFile(pName) match {
            case f @ Some(_) =>
              ProjectInfo(
                projectName = pName,
                templateName = TemplateHandler.getTemplateName(metadata.map(_.name).toSeq.distinct),
                file = f)
            case None => ProjectInfo()
          }
      }

    val result = for {
      f <- projectInfo.file
      t <- TemplateHandler.findTemplate(metadata, projectInfo.templateName)
    } yield generateProjectTemplate(t, projectInfo.templateName, projectInfo.projectName, cache, f)

    result.getOrElse(1)
  }

  private def createFile(name: String): Option[File] = {
    val file = new File(name)
    if (!file.exists()) Some(file.getAbsoluteFile)
    else {
      System.err.println(s"There already is a project with name: $name. Either remove the existing project or create one with a unique name. ")
      None
    }
  }

  private def generateProjectTemplate(template: TemplateMetadata, tName: String, pName: String, cache: TemplateCache, projectDir: File): Int = {
    System.out.println(s"""OK, application "$pName" is being created using the "${template.name}" template.""")
    System.out.println()
    import scala.concurrent.ExecutionContext.Implicits.global

    // record stats in parallel while we are cloning
    val statsRecorded = TemplatePopularityContest.recordClonedIgnoringErrors(template.name)

    // TODO - Is this duration ok?
    Await.result(
      cloneTemplate(
        cache,
        template.id,
        projectDir,
        Some(pName),
        filterMetadata = !template.templateTemplate,
        additionalFiles = UICacheHelper.scriptFilesForCloning),
      Duration(5, MINUTES))

    printUsage(pName, projectDir)

    // don't wait too long on this remote call, we ignore the
    // result anyway; just don't want to exit the JVM too soon.
    Await.result(statsRecorded, Duration(5, SECONDS))
    0
  }

  private def printUsage(name: String, dir: File): Unit = {
    // TODO - Cross-platform-ize these strings! Possibly keep script name in SnapProperties.
    System.out.println(s"""|To run "$name" from the command-line, run:
                           |${dir.getAbsolutePath}/${SCRIPT_NAME} run
                           |
                           |To run the test for "$name" from the command-line, run:
                           |${dir.getAbsolutePath}/${SCRIPT_NAME} test
                           |
                           |To run the Activator UI for "$name" from the command-line, run:
                           |${dir.getAbsolutePath}/${SCRIPT_NAME} ui
                           |""".stripMargin)
  }

  private def getApplicationName(): String = {
    System.out.println("Enter an application name")
    val appNameParser: Parser[String] = {
      import Parser._
      import Parsers._
      token(any.* map { _ mkString "" }, "<application name>")
    }

    readLine(appNameParser) filterNot (_.isEmpty) getOrElse sys.error("No application name specified.")
  }

  def withContextClassloader[A](f: => A): A = {
    val current = Thread.currentThread
    val old = current.getContextClassLoader
    current setContextClassLoader getClass.getClassLoader
    try f
    finally current setContextClassLoader old
  }
}
