import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Application Windows (Compose Desktop) : poste fixe de surveillance.
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(21) }

dependencies {
  implementation(project(":core"))
  implementation(compose.desktop.currentOs)
  implementation(compose.material3)
  implementation(compose.materialIconsExtended)
  implementation(libs.jna.platform)
  implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
  implementation(kotlin("reflect")) // aligne kotlin-reflect sur la version de Kotlin (moshi-kotlin tire une 1.8)
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
  testImplementation(compose.uiTest)
}

compose.desktop {
  application {
    mainClass = "fr.alerteresidents.desktop.MainKt"
    // Réseaux d'établissement : utiliser le proxy configuré dans Windows (ignoré par Java sinon).
    jvmArgs += listOf("-Djava.net.useSystemProxies=true")
    nativeDistributions {
      targetFormats(TargetFormat.Msi, TargetFormat.Exe)
      packageName = "AlerteResidents"
      packageVersion = "2.1.1"
      description = "Surveillance des résidents équipés de balises Weenect"
      vendor = "Alerte Résidents"
      modules("java.naming", "java.sql", "jdk.crypto.ec", "java.desktop", "java.instrument", "jdk.unsupported", "java.logging", "java.net.http")
      windows {
        menuGroup = "Alerte Résidents"
        shortcut = true
        menu = true
        perUserInstall = true
        dirChooser = false
        // Identifiant fixe : permet les mises à jour par-dessus une version installée.
        upgradeUuid = "6F5B8E2A-3C1D-4E9B-9A7F-2D4C8B1E5A30"
        iconFile.set(project.file("src/main/resources/icon.ico"))
      }
    }
  }
}

tasks.test {
  // Jamais le vrai dossier de données de l'utilisateur pendant les tests.
  systemProperty("alerteresidents.dataDir", layout.buildDirectory.dir("test-data").get().asFile.path)
  project.findProperty("screenshotDir")?.let { systemProperty("screenshotDir", it.toString()) }
}
