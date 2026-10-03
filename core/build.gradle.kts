import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Cœur partagé Android / Windows : modèles, règles de surveillance, client Weenect, sauvegarde.
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.google.devtools.ksp)
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

dependencies {
  api(libs.kotlinx.coroutines.core)
  api(libs.retrofit)
  api(libs.converter.moshi)
  api(libs.moshi)
  api(libs.okhttp)
  api(libs.okio)
  implementation(libs.logging.interceptor)
  // Annotations Room (@Entity…) : l'app Android génère la base, le module reste pur JVM.
  api(libs.androidx.room.common)
  "ksp"(libs.moshi.kotlin.codegen)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.mockwebserver)
}
