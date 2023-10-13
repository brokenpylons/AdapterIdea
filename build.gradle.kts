plugins {
    id("java")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()

    ivy {
        url = uri("https://test.lpm.feri.um.si")
        patternLayout {
            artifact("/[module].[ext]")
        }
        metadataSources { artifact() }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("ears:ears")
}

tasks.test {
    useJUnitPlatform()
}