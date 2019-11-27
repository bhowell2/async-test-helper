import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
  `java-library`
  jacoco
  `maven-publish`
  signing
//  id("io.codearte.nexus-staging") version("0.21.1")
}

group = "io.github.bhowell2"
version = "1.2.0"

repositories {
  // Use jcenter for resolving dependencies.
  // You can declare any Maven/Ivy/file repository here.
  jcenter()
  mavenCentral()
}

dependencies {
  // Use JUnit Jupiter API for testing.
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0-M1")

  // Use JUnit Jupiter Engine for testing.
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.0-M1")
}

val test by tasks.getting(Test::class) {
  // Use junit platform for unit tests
  testLogging {
    events.add(TestLogEvent.FAILED)
    exceptionFormat = TestExceptionFormat.SHORT
  }
  useJUnitPlatform()
}

tasks.jacocoTestReport {
  reports {
    xml.setEnabled(true)
    html.setEnabled(false)
  }
}

tasks.check {
  dependsOn.add(tasks.jacocoTestReport)
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.register<Jar>("sourcesJar") {
  from(sourceSets.main.get().allJava)
  archiveClassifier.set("sources")
}

tasks.register<Jar>("javadocJar") {
  from(tasks.javadoc)
  archiveClassifier.set("javadoc")
}

// set by environment variables: ORG_GRADLE_PROJECT_sonatypeUsername, ORG_GRADLE_PROJECT_sonatypePassword
val sonatypeUsername: String? by project
val sonatypePassword: String? by project

publishing {
  publications {
    create<MavenPublication>("mavenJava") {
      artifactId = project.name
      from(components["java"])
      artifact(tasks["sourcesJar"])
      artifact(tasks["javadocJar"])
      versionMapping {
        usage("java-api") {
          fromResolutionOf("runtimeClasspath")
        }
        usage("java-runtime") {
          fromResolutionResult()
        }
      }
      pom {
        name.set("Debouncer")
        description.set("A simple debouncer for java.")
        url.set("https://github.com/bhowell2/debouncer")
//        properties.set(mapOf(
//          "myProp" to "value",
//          "prop.with.dots" to "anotherValue"
//        ))
        licenses {
          license {
            name.set("MIT License")
            url.set("https://choosealicense.com/licenses/mit/")
          }
        }
        developers {
          developer {
            id.set("bhowell2")
            name.set("Blake Howell")
            email.set("bhowell2.github.io@gmail.com")
          }
        }
        scm {
          connection.set("scm:git:git://github.com:bhowell2/debouncer.git")
          developerConnection.set("scm:git:ssh://github.com:bhowell2/debouncer.git")
          url.set("https://github.com/bhowell2/debouncer")
        }
      }
    }
  }
  repositories {
    maven {
      val releasesRepoUrl = uri("https://oss.sonatype.org/service/local/staging/deploy/maven2")
      val snapshotsRepoUrl = uri("https://oss.sonatype.org/content/repositories/snapshots")
      url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
      credentials {
        username = sonatypeUsername
        password = sonatypePassword
      }
    }
  }
}

signing {
  // Set by the environmentVariables: ORG_GRADLE_PROJECT_signingKey, ORG_GRADLE_PROJECT_signingKeyPassword
  val signingKey: String? by project
  val signingKeyPassword: String? by project
  useInMemoryPgpKeys(signingKey, signingKeyPassword)
  sign(publishing.publications["mavenJava"])
}

//nexusStaging {
//  username = sonatypeUsername
//  password = sonatypePassword
//  stagingProfileId = "84eece2cca5792"
//}
//
