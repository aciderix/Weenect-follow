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
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
  testImplementation(compose.uiTest)
}

compose.desktop {
  application {
    mainClass = "fr.alerteresidents.desktop.MainKt"
    nativeDistributions {
      targetFormats(TargetFormat.Msi, TargetFormat.Exe)
      packageName = "AlerteResidents"
      packageVersion = "2.0.0"
      description = "Surveillance des résidents équipés de balises Weenect"
      vendor = "Alerte Résidents"
      modules("java.naming", "java.sql", "jdk.crypto.ec", "java.desktop")
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
