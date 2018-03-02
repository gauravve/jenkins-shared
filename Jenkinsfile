#!groovy
// Example Jenkinsfile, should be actual code repo
@Library('shared') _

properties()

// Both of these are valid using the BuildConfig library
buildMavenCode {
    product='com',
}

// Both of these are valid using the BuildConfig library
buildMavenCode ([
    "product": 'com'
])

def properties() {
    // Set Jenkins job properties
    properties([
        buildDiscarder(logRotator(numToKeepStr: '20')),
        parameters([
            choice(name: 'VERSION_INCREMENT', choices: ['PATCH', 'MINOR', 'MAJOR'].join('\n'), description: 'Develop Only'),
        ])
    ])
}