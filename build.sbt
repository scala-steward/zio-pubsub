import zio.sbt.githubactions.{Job, Step, Condition, ActionRef}
import _root_.io.circe.Json
enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

lazy val _scala2 = "2.13.16"

lazy val _scala3 = "3.3.5"

inThisBuild(
  List(
    name         := "ZIO Google Cloud Pub/Sub",
    organization := "com.anymindgroup",
    licenses     := Seq(License.Apache2),
    homepage     := Some(url("https://anymindgroup.github.io/zio-pubsub")),
    developers := List(
      Developer(id = "rolang", name = "Roman Langolf", email = "rolang@pm.me", url = url("https://github.com/rolang")),
      Developer(
        id = "dutch3883",
        name = "Panuwach Boonyasup",
        email = "dutch3883@hotmail.com",
        url = url("https://github.com/dutch3883"),
      ),
      Developer(
        id = "qhquanghuy",
        name = "Huy Nguyen",
        email = "huy_ngq@flinters.vn",
        url = url("https://github.com/qhquanghuy"),
      ),
    ),
    zioVersion         := "2.1.16",
    scala213           := _scala2,
    scala3             := _scala3,
    scalaVersion       := _scala2,
    crossScalaVersions := Seq(_scala2, _scala3),
    versionScheme      := Some("early-semver"),
    ciEnabledBranches  := Seq("master"),
    ciJvmOptions ++= Seq("-Xms2G", "-Xmx2G", "-Xss4M", "-XX:+UseG1GC"),
    ciTargetJavaVersions := Seq("17", "21"),
    ciBuildJobs := ciBuildJobs.value.map { j =>
      j.copy(steps =
        j.steps.map {
          case s @ Step.SingleStep("Check all code compiles", _, _, _, _, _, _) =>
            Step.SingleStep(
              name = s.name,
              run = Some("sbt '+Test/compile; +examples/compile'"),
            )
          case s @ Step.SingleStep("Check website build process", _, _, _, _, _, _) =>
            Step.StepSequence(
              Seq(
                s,
                Step.SingleStep(
                  "Adjust baseUrl in website build",
                  run = Some(
                    """sed -i "s/baseUrl:.*/baseUrl: \"\/zio-pubsub\/\",/g" zio-pubsub-docs/target/website/docusaurus.config.js && sbt docs/buildWebsite"""
                  ),
                ),
              )
            )
          case s => s
        } :+ Step.SingleStep(
          name = "Upload website build",
          uses = Some(ActionRef("actions/upload-pages-artifact@v3")),
          parameters = Map("path" -> Json.fromString("zio-pubsub-docs/target/website/build")),
        )
      )
    },
    ciTestJobs := ciTestJobs.value.map {
      case j if j.id == "test" =>
        val startPubsub = Step.SingleStep(
          name = "Start up pubsub",
          run = Some(
            "docker compose up -d && until curl -s http://localhost:8085; do printf 'waiting for pubsub...'; sleep 1; done && echo \"pubsub ready\""
          ),
        )
        j.copy(steps = j.steps.flatMap {
          case s: Step.SingleStep if s.name.contains("Git Checkout") => Seq(s, startPubsub)
          case s                                                     => Seq(s)
        })
      case j => j
    },
    sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost,
    ciReleaseJobs := ciReleaseJobs.value.map(j =>
      j.copy(
        steps = j.steps.map {
          case Step.SingleStep(name @ "Release", _, _, _, _, _, env) =>
            Step.SingleStep(
              name = name,
              run = Some(
                """|echo "$PGP_SECRET" | base64 -d -i - > /tmp/signing-key.gpg
                   |echo "$PGP_PASSPHRASE" | gpg --pinentry-mode loopback --passphrase-fd 0 --import /tmp/signing-key.gpg
                   |(echo "$PGP_PASSPHRASE"; echo; echo) | gpg --command-fd 0 --pinentry-mode loopback --change-passphrase $(gpg --list-secret-keys --with-colons 2> /dev/null | grep '^sec:' | cut --delimiter ':' --fields 5 | tail -n 1)
                   |sbt '+publishSigned; sonatypeCentralRelease'""".stripMargin
              ),
              env = env,
            )
          case s => s
        }
      )
    ),
    // this overrides the default post release jobs generated by zio-sbt-ci which publish the docs to NPM Registry
    // can try to make it work with NPM later
    ciPostReleaseJobs := Nil,
    // zio.sbt.githubactions.Job doesn't provide options for adding permissions and environment
    // overriding ciGenerateGithubWorkflow as well as ciCheckGithubWorkflow to get both working
    ciGenerateGithubWorkflow := ciGenerateGithubWorkflowV2.value,
    ciCheckGithubWorkflow := Def.task {
      import sys.process.*
      val _ = ciGenerateGithubWorkflowV2.value

      if ("git diff --exit-code".! == 1) {
        sys.error(
          "The ci.yml workflow is not up-to-date!\n" +
            "Please run `sbt ciGenerateGithubWorkflow` and commit new changes."
        )
      }
    },
    scalafmt         := true,
    scalafmtSbtCheck := true,
    scalafixDependencies ++= List(
      "com.github.vovapolu" %% "scaluzzi" % "0.1.23"
    ),
  )
)

lazy val ciGenerateGithubWorkflowV2 = Def.task {
  val _ = ciGenerateGithubWorkflow.value

  IO.append(
    new File(s".github/workflows/ci.yml"),
    """|  deploy-website:
       |    name: Deploy website
       |    runs-on: ubuntu-latest
       |    continue-on-error: false
       |    permissions:
       |      pages: write
       |      id-token: write
       |    environment:
       |      name: github-pages
       |      url: ${{ steps.deployment.outputs.page_url }}
       |    needs:
       |    - release
       |    steps:
       |    - name: Deploy to GitHub Pages
       |      uses: actions/deploy-pages@v4
       |""".stripMargin,
  )
}

lazy val commonSettings = List(
  libraryDependencies ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq(compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"))
      case _            => Seq()
    }
  },
  javacOptions ++= Seq("-source", "17"),
  Compile / scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("-Ymacro-annotations", "-Xsource:3")
      case _            => Seq("-source:future")
    }
  },
  Compile / scalacOptions --= sys.env.get("CI").fold(Seq("-Xfatal-warnings"))(_ => Nil),
  Test / scalafixConfig := Some(new File(".scalafix_test.conf")),
  Test / scalacOptions --= Seq("-Xfatal-warnings"),
) ++ scalafixSettings

val noPublishSettings = List(
  publish         := {},
  publishLocal    := {},
  publishArtifact := false,
  publish / skip  := true,
)

lazy val root =
  (project in file("."))
    .aggregate(
      zioPubsub.jvm,
      zioPubsub.native,
      zioPubsubGoogle,
      zioPubsubGoogleTest,
      zioPubsubTestkit,
      zioPubsubSerdeCirce.jvm,
      zioPubsubSerdeCirce.native,
      zioPubsubSerdeZioSchema.jvm,
      zioPubsubSerdeZioSchema.native,
      zioPubsubSerdeVulcan,
      zioPubsubTest.jvm,
      zioPubsubTest.native,
    )
    .settings(commonSettings)
    .settings(noPublishSettings)
    .settings(
      coverageDataDir := {
        val scalaVersionMajor = scalaVersion.value.head
        target.value / s"scala-$scalaVersionMajor"
      }
    )

lazy val zioPubsub = crossProject(JVMPlatform, NativePlatform)
  .in(file("zio-pubsub"))
  .settings(moduleName := "zio-pubsub")
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio"         % zioVersion.value,
      "dev.zio" %%% "zio-streams" % zioVersion.value,
    )
  )

val vulcanVersion = "1.11.1"
lazy val zioPubsubSerdeVulcan = (project in file("zio-pubsub-serde-vulcan"))
  .settings(moduleName := "zio-pubsub-serde-vulcan")
  .dependsOn(zioPubsub.jvm)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.fd4s" %% "vulcan"         % vulcanVersion,
      "com.github.fd4s" %% "vulcan-generic" % vulcanVersion,
    )
  )

val circeVersion = "0.14.10"
lazy val zioPubsubSerdeCirce = crossProject(JVMPlatform, NativePlatform)
  .in(file("zio-pubsub-serde-circe"))
  .settings(moduleName := "zio-pubsub-serde-circe")
  .dependsOn(zioPubsub)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core"    % circeVersion,
      "io.circe" %%% "circe-parser"  % circeVersion,
      "io.circe" %%% "circe-generic" % circeVersion,
    )
  )

val zioSchemaVersion = "1.6.1"
lazy val zioPubsubSerdeZioSchema = crossProject(JVMPlatform, NativePlatform)
  .in(file("zio-pubsub-serde-zio-schema"))
  .settings(moduleName := "zio-pubsub-serde-zio-schema")
  .dependsOn(zioPubsub)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-schema" % zioSchemaVersion
    )
  )

val googleCloudPubsubVersion = "1.137.0"
lazy val zioPubsubGoogle = (project in file("zio-pubsub-google"))
  .settings(moduleName := "zio-pubsub-google")
  .dependsOn(zioPubsub.jvm)
  .aggregate(zioPubsub.jvm)
  .settings(commonSettings)
  .settings(
    scalacOptions --= List("-Wunused:nowarn"),
    libraryDependencies ++= Seq(
      "com.google.cloud" % "google-cloud-pubsub" % googleCloudPubsubVersion
    ),
  )

lazy val zioPubsubGoogleTest = project
  .in(file("zio-pubsub-google-test"))
  .dependsOn(zioPubsub.jvm, zioPubsubGoogle, zioPubsubTestkit, zioPubsubSerdeCirce.jvm, zioPubsubSerdeVulcan)
  .settings(moduleName := "zio-pubsub-google-test")
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(testDeps)
  .settings(
    coverageEnabled            := true,
    (Test / parallelExecution) := true,
    (Test / fork)              := true,
  )

// TODO remove dependency on zioPubsubGoogle
lazy val zioPubsubTestkit =
  (project in file("zio-pubsub-testkit"))
    .dependsOn(zioPubsub.jvm, zioPubsubGoogle)
    .settings(moduleName := "zio-pubsub-testkit")
    .settings(commonSettings)
    .settings(
      scalafixConfig := Some(new File(".scalafix_test.conf")),
      libraryDependencies ++= Seq(
        "dev.zio" %% "zio-test" % zioVersion.value
      ),
    )

lazy val zioPubsubTest =
  crossProject(JVMPlatform, NativePlatform)
    .in(file("zio-pubsub-test"))
    .dependsOn(zioPubsub, zioPubsubSerdeCirce)
    .settings(moduleName := "zio-pubsub-test")
    .settings(commonSettings)
    .settings(noPublishSettings)
    .settings(testDeps)
    .jvmSettings(coverageEnabled := true)
    .nativeSettings(coverageEnabled := false)

lazy val examples = (project in file("examples"))
  .dependsOn(zioPubsubGoogle)
  .settings(noPublishSettings)
  .settings(
    scalaVersion       := _scala3,
    crossScalaVersions := Seq(_scala3),
    coverageEnabled    := false,
    fork               := true,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-json" % "0.7.1"
    ),
  )

lazy val testDeps = Seq(
  libraryDependencies ++= Seq(
    "dev.zio" %%% "zio-test"     % zioVersion.value % Test,
    "dev.zio" %%% "zio-test-sbt" % zioVersion.value % Test,
  )
)

lazy val docs = project
  .in(file("zio-pubsub-docs"))
  .settings(
    moduleName := "zio-pubsub-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    projectName                                := "ZIO Google Cloud Pub/Sub",
    mainModuleName                             := (zioPubsub.jvm / moduleName).value,
    projectStage                               := ProjectStage.Development,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(zioPubsub.jvm),
    readmeContribution :=
      """|If you have any question or problem feel free to open an issue or discussion.
         |
         |People are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md) when discussing on the GitHub issues or PRs.""".stripMargin,
    readmeSupport       := "Open an issue or discussion on [GitHub](https://github.com/AnyMindGroup/zio-pubsub/issues)",
    readmeCodeOfConduct := "See the [Code of Conduct](CODE_OF_CONDUCT.md)",
    readmeCredits := """|Inspired by libraries like [zio-kafka](https://github.com/zio/zio-kafka) 
                        |and [fs2-pubsub](https://github.com/permutive-engineering/fs2-pubsub) to provide a similar experience.""".stripMargin,
    // docusaurusPublishGhpages := docusaurusPublishGhpages.value,
  )
  .enablePlugins(WebsitePlugin)
  .dependsOn(zioPubsub.jvm, zioPubsubGoogle)
