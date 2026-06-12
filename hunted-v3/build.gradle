plugins {
    id 'java'
    id 'net.neoforged.gradle.userdev' version '7.0.171'
}

group   = 'com.hunted'
version = '2.0.0'

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

runs {
    server {
        workingDirectory project.file('run')
        systemProperty 'forge.logging.console.level', 'debug'
        modSources project.sourceSets.main
    }
}

dependencies {
    implementation "net.neoforged:neoforge:21.1.172"
}

jar {
    manifest {
        attributes([
            'Specification-Title'   : 'hunted',
            'Specification-Version' : '1',
            'Implementation-Title'  : project.name,
            'Implementation-Version': project.version
        ])
    }
}
