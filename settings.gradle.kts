pluginManagement {
    repositories {
        maven { url = uri("http://dl.bintray.com/kotlin/kotlin-eap") }

        mavenCentral()

        maven { url = uri("https://plugins.gradle.org/m2/") }

        maven { url = uri("https://kotlin.bintray.com/kotlinx") }
    }
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "kotlinx-serialization" -> {
                    useModule("org.jetbrains.kotlin:kotlin-serialization:${requested.version}")
                }
            }
        }
    }
}
rootProject.name = "helse-sykmelding-router"

