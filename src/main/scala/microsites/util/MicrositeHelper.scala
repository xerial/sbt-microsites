/*
 * Copyright 2016 47 Degrees, LLC. <http://www.47deg.com>
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

package microsites.util

import java.io.File

import microsites._
import net.jcazevedo.moultingyaml.{YamlObject, _}
import ConfigYamlProtocol._
import microsites.layouts._
import microsites.util.FileHelper._
import sbt._

import scala.io.Source
import com.sksamuel.scrimage._

class MicrositeHelper(config: MicrositeSettings) {
  implicitly(config)

  val jekyllDir = "jekyll"

  // format: OFF
  val faviconHeights = Seq(16, 24, 32, 48, 57, 60, 64, 70, 72, 76, 96,
                           114, 120, 128, 144, 150, 152, 196, 310)
  val faviconSizes = (faviconHeights zip faviconHeights) ++ Seq((310, 150))
  // format: ON

  lazy val faviconFilenames =
    faviconSizes.foldLeft(Seq[String]())((list, size) => {
      val (width, height) = size
      list :+ s"favicon${width}x${height}.png"
    })

  lazy val faviconDescriptions = (faviconFilenames zip faviconSizes).map {
    case (filename, (width, height)) =>
      MicrositeFavicon(filename, s"${width}x${height}")
  }

  def createResources(resourceManagedDir: File, tutSourceDirectory: File): List[File] = {

    val targetDir: String                   = getPathWithSlash(resourceManagedDir)
    val tutSourceDir: String                = getPathWithSlash(tutSourceDirectory)
    val pluginURL: URL                      = getClass.getProtectionDomain.getCodeSource.getLocation
    val dependencies: Seq[KazariDependency] = loadDependenciesFromBuild(resourceManagedDir)
    val configWithDependencies = config.copy(
      config.identity,
      config.visualSettings,
      config.configYaml,
      config.fileLocations,
      config.urlSettings,
      config.gitSettings,
      KazariSettings(
        config.micrositeKazariSettings.micrositeKazariEvaluatorUrl,
        config.micrositeKazariSettings.micrositeKazariEvaluatorToken,
        config.micrositeKazariSettings.micrositeKazariGithubToken,
        config.micrositeKazariSettings.micrositeKazariCodeMirrorTheme,
        (config.micrositeKazariSettings.micrositeKazariDependencies ++ dependencies).toSet.toSeq,
        config.micrositeKazariSettings.micrositeKazariResolvers
      )
    )

    copyPluginResources(pluginURL, s"$targetDir$jekyllDir/", "_sass")
    copyPluginResources(pluginURL, s"$targetDir$jekyllDir/", "css")
    copyPluginResources(pluginURL, s"$targetDir$jekyllDir/", "img")
    copyPluginResources(pluginURL, s"$targetDir$jekyllDir/", "js")
    copyPluginResources(pluginURL, s"$targetDir$jekyllDir/", "highlight")

    copyFilesRecursively(config.fileLocations.micrositeImgDirectory.getAbsolutePath,
                         s"$targetDir$jekyllDir/img/")
    copyFilesRecursively(config.fileLocations.micrositeCssDirectory.getAbsolutePath,
                         s"$targetDir$jekyllDir/css/")
    copyFilesRecursively(config.fileLocations.micrositeJsDirectory.getAbsolutePath,
                         s"$targetDir$jekyllDir/js/")
    copyFilesRecursively(config.fileLocations.micrositeExternalLayoutsDirectory.getAbsolutePath,
                         s"$targetDir$jekyllDir/_layouts/")
    copyFilesRecursively(config.fileLocations.micrositeExternalIncludesDirectory.getAbsolutePath,
                         s"$targetDir$jekyllDir/_includes/")
    copyFilesRecursively(config.fileLocations.micrositeDataDirectory.getAbsolutePath,
                         s"$targetDir$jekyllDir/_data/")

    config.fileLocations.micrositeExtraMdFiles foreach {
      case (sourceFile, targetFileConfig) =>
        println(s"Copying from ${sourceFile.getAbsolutePath} to $tutSourceDir$targetFileConfig")

        val targetFileContent =
          s"""---
             |layout: ${targetFileConfig.layout}
             |${targetFileConfig.metaProperties map {
               case (key, value) => "%s: %s" format (key, value)
             } mkString ("", "\n", "")}
             |---
             |${Source.fromFile(sourceFile.getAbsolutePath).mkString}
             |""".stripMargin

        IO.write(s"$tutSourceDir${targetFileConfig.fileName}".toFile, targetFileContent)
    }

    List(createConfigYML(targetDir), createPalette(targetDir)) ++
      createLayouts(targetDir, configWithDependencies) ++ createPartialLayout(targetDir) ++ createFavicons(
      targetDir)
  }

  def createConfigYML(targetDir: String): File = {
    val targetFile = createFilePathIfNotExists(s"$targetDir$jekyllDir/_config.yml")

    val yaml             = config.configYaml
    val customProperties = yaml.yamlCustomProperties.toYaml.asYamlObject.fields
    val inlineYaml =
      if (yaml.yamlInline.nonEmpty)
        yaml.yamlInline.parseYaml.asYamlObject.fields
      else Map.empty[YamlValue, YamlValue]
    val fileYaml = yaml.yamlPath.fold(Map.empty[YamlValue, YamlValue])(f =>
      if (f.exists()) {
        Source.fromFile(f.getAbsolutePath).mkString.parseYaml.asYamlObject.fields
      } else Map.empty[YamlValue, YamlValue])

    IO.write(targetFile, YamlObject(customProperties ++ fileYaml ++ inlineYaml).prettyPrint)

    targetFile
  }

  def createPalette(targetDir: String): File = {
    val targetFile = createFilePathIfNotExists(
      s"$targetDir$jekyllDir/_sass/_variables_palette.scss")
    val content = config.visualSettings.palette.map {
      case (key, value) => s"""$$$key: $value;"""
    }.mkString("\n")
    IO.write(targetFile, content)
    targetFile
  }

  def createLayouts(targetDir: String, configSettings: MicrositeSettings = config): List[File] =
    List(
      "home" -> new HomeLayout(configSettings),
      "docs" -> new DocsLayout(configSettings),
      "page" -> new PageLayout(configSettings)
    ) map {
      case (layoutName, layout) =>
        val targetFile =
          createFilePathIfNotExists(s"$targetDir$jekyllDir/_layouts/$layoutName.html")
        IO.write(targetFile, layout.render.toString())
        targetFile
    }

  def createPartialLayout(targetDir: String): List[File] =
    List("menu" -> new MenuPartialLayout(config)) map {
      case (layoutName, layout) =>
        val targetFile =
          createFilePathIfNotExists(s"$targetDir$jekyllDir/_includes/$layoutName.html")
        IO.write(targetFile, layout.render.toString())
        targetFile
    }

  def createFavicons(targetDir: String): List[File] = {
    val sourceFile = createFilePathIfNotExists(s"$targetDir$jekyllDir/img/navbar_brand2x.png")

    (faviconFilenames zip faviconSizes).map {
      case (name, size) =>
        (new File(s"$targetDir$jekyllDir/img/$name"), size)
    }.map {
      case (file, (width, height)) =>
        Image.fromFile(sourceFile).scaleTo(width, height).output(file)
    }.toList
  }

  def copyConfigurationFile(sourceDir: File, targetDir: File): Unit = {

    val targetFile = createFilePathIfNotExists(s"$targetDir/_config.yml")

    copyFilesRecursively(s"${sourceDir.getAbsolutePath}/_config.yml", targetFile.getAbsolutePath)
  }

  /*
   * This method has been extracted from the sbt-native-packager plugin:
   *
   * https://github.com/sbt/sbt-native-packager/blob/b5e2bb9027d08c00420476e6be0d876cf350963a/src/main/scala/com/typesafe/sbt/packager/MappingsHelper.scala#L21
   *
   */
  def directory(sourceDirPath: String): Seq[(File, String)] = {
    val sourceDir = file(sourceDirPath)
    Option(sourceDir.getParentFile)
      .map(parent => sourceDir.*** pair relativeTo(parent))
      .getOrElse(sourceDir.*** pair basic)
  }

  def kazariDependenciesFilename = "dependencies.txt"

  def storeDependenciesFromBuild(modules: Seq[ModuleID],
                                 targetDir: File,
                                 scalaVersion: String): File = {
    val targetPath = getPathWithSlash(targetDir)
    val targetFile =
      createFilePathIfNotExists(s"$targetPath/$kazariDependenciesFilename")
    val scalaVersionSuffix = "_" + scalaVersion.split('.').dropRight(1).mkString(".")
    val scalaDependencies = defaultScalaDependendencies(scalaVersion).map(dep =>
      s"${dep.groupId};${dep.artifactId};${dep.version}")
    val content =
      (modules.map(m =>
        if (m.name.contains("_2.10") || m.name.contains("_2.11") || m.name.contains("_2.12")) {
          s"${m.organization};${m.name};${m.revision}"
        } else {
          s"${m.organization};${m.name}$scalaVersionSuffix;${m.revision}"
      }) ++ scalaDependencies).mkString("\n")
    IO.write(targetFile, content)
    targetFile
  }

  def loadDependenciesFromBuild(targetDir: File): Seq[KazariDependency] = {
    val targetPath = getPathWithSlash(targetDir)
    val file       = new File(s"$targetPath/$kazariDependenciesFilename")
    if (file.exists()) {
      IO.read(file)
        .split("\n")
        .map(dep => dep.split(";"))
        .filter(rawDep => rawDep.length == 3)
        .map(rawDeps => KazariDependency(rawDeps(0), rawDeps(1), rawDeps(2)))
        .toSeq ++ config.micrositeKazariSettings.micrositeKazariDependencies
    } else {
      Seq()
    }
  }

  def defaultScalaDependendencies(scalaVersion: String) = Seq(
    microsites.KazariDependency("org.scala-lang", "scala-library", scalaVersion),
    microsites.KazariDependency("org.scala-lang", "scala-api", scalaVersion),
    microsites.KazariDependency("org.scala-lang", "scala-reflect", scalaVersion),
    microsites.KazariDependency("org.scala-lang", "scala-compiler", scalaVersion),
    microsites.KazariDependency("org.scala-lang", "scala-xml", scalaVersion)
  )
}
