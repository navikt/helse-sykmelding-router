import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val artemisVersion = "2.6.2"
val ibmMqVersion = "9.1.0.0"
val ktorVersion = "1.0.0"
val logbackVersion = "1.3.0-alpha4"
val logstashLogbackEncoderVersion = "5.2"
val spekVersion = "2.0.0-rc.1"
val prometheusVersion = "0.5.0"
val serializationVersion = "0.8.3-rc13"

plugins {
    kotlin("jvm") version "1.3.0-rc-190"
    id("kotlinx-serialization") version "1.3.0-rc-190"
    id("com.github.johnrengelman.shadow") version "4.0.3"
}

group = "no.nav.helse"
version = "1.0-SNAPSHOT"

tasks.withType<Jar> {
    manifest.attributes["Main-Class"] = "no.nav.helse.JmsRouterKt"
}

repositories {
    mavenLocal()
    mavenCentral()
    jcenter()

    maven { url = uri("https://dl.bintray.com/kotlin/ktor/") }

    maven { url = uri("https://kotlin.bintray.com/kotlinx") }

    maven { url = uri("https://dl.bintray.com/kotlin/kotlin-eap") }

    maven { url = uri("https://dl.bintray.com/spekframework/spek-dev") }
}

dependencies {
    compile(kotlin("stdlib-jdk8"))
    implementation("com.ibm.mq:com.ibm.mq.allclient:$ibmMqVersion")

    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-jackson:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:$serializationVersion")

    implementation("io.prometheus:simpleclient_common:$prometheusVersion")
    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashLogbackEncoderVersion")

    testImplementation("org.apache.activemq:artemis-server:$artemisVersion")
    testImplementation("org.apache.activemq:artemis-jms-client:$artemisVersion")

    testImplementation("org.amshove.kluent:kluent:1.39")
    testImplementation("org.spekframework.spek2:spek-dsl-jvm:$spekVersion")

    testRuntimeOnly("org.spekframework.spek2:spek-runtime-jvm:$spekVersion")
    testRuntimeOnly("org.spekframework.spek2:spek-runner-junit5:$spekVersion")
}

tasks.withType<Test> {
    useJUnitPlatform {
        includeEngines("spek2")
    }
    testLogging {
        showStandardStreams = true
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks {
    "printVersion" {
        println(project.version)
    }
}
