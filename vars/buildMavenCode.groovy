import net.something.jenkins.workflow.Version
import net.something.jenkins.workflow.BuildConfig

def call(def body = [:]) {
    // evaluate the body block, and collect configuration into the object
    // Supports closure or map
    config = BuildConfig.resolve(body)
    awsAccountVars = [:]

    userInput = ''

    // Set up artifactory configs
    def server = Artifactory.server "artifactory"
    def rtMaven = Artifactory.newMavenBuild()
    def buildInfo = Artifactory.newBuildInfo()

    // Tool name from Jenkins configuration
    rtMaven.tool = "maven"

    // Set Artifactory repositories for dependencies resolution and artifacts deployment.
    rtMaven.resolver server: server, releaseRepo: 'libs-release', snapshotRepo: 'libs-snapshot'
    rtMaven.deployer server: server, releaseRepo: 'libs-release-local', snapshotRepo:'libs-snapshot-local'
    rtMaven.deployer.deployArtifacts = false

    // This is where the magic happens - put your pipeline snippets in here, get variables from config.
    ansiColor {
        timestamps {
            node {
                try {
                    stage('Checkout SCM & Setup Environment') {
                        // Delete Workspace before checkout
                        deleteDir()
                        scmUtils.checkout()
                        // Setup shared pipeline vars (Git info, User info)
                        sharedVars.set(config)
                        // Print available Env Vars
                        echo "Environment Variables:"
                        echo sh(returnStdout: true, script: 'env')
                    }
                }
                catch (err) {
                    throw err
                }
                try {
                    stage('Build & Test') {
                        sh("ls -l")
                        echo 'Increment version: ${params.VERSION_INCREMENT}'
                        nextVersion = Version.incrementVersion(readMavenPom().getVersion(), params.VERSION_INCREMENT)

                        withMaven(maven: 'maven') {
                            sh "mvn versions:set -DnewVersion=${nextVersion} -DgenerateBackupPoms=false"
                        }
                        // Build and test
                        withSonarQubeEnv('sonar-ecs') {
                            buildInfo = rtMaven.run pom: 'pom.xml', goals: 'clean test install -Ddependency-check-format=XML -Dsonar.branch=${BRANCH_NAME} -Dsonar.dependencyCheck.reportPath=${WORKSPACE}/target/dependency-check-report.xml sonar:sonar'
                        }
                        // Publish Owasp report in jenkins
                        step([
                                $class: 'DependencyCheckPublisher',
                                canComputeNew: false,
                                canRunOnFailed: true,
                                shouldDetectModules: true,
                                defaultEncoding: '',
                                healthy: '',
                                pattern: 'target/dependency-check-report.xml',
                                unHealthy: ''
                        ])
                        stash name: 'workspace', useDefaultExcludes: false
                    }
                }
                catch (err) {
                    throw err
                }
            }
            // No need to occupy a node
            stage("Quality Gate") {
                try {
                    timeout(time: 1, unit: 'HOURS') { // Just in case something goes wrong, pipeline will be killed after a timeout
                        qg = waitForQualityGate() // Reuse taskId previously collected by withSonarQubeEnv
                        if (qg.status == 'ERROR') { //allow OK and WARN
                            error "Pipeline aborted due to quality gate failure: ${qg.status}"
                        }
                    }
                }
                catch (err) {
                    node {
                        throw err
                    }
                }
            }
            node {
                echo(jobCauses.getCauses().join(','))
                if (BRANCH_NAME != 'develop') {
                    echo("INFO: Build is either not on branch develop or caused by Branch Indexing and previous commiter was 'bot', no need to continue")
                    currentBuild.result = 'SUCCESS'
                    return
                } else {
                    stage('Commit Git & Publish to Artifactory') {
                        try {
                            // Best practise - Stash/Unstash workspace before and after input due to potential new node. Keep 'make' happy by touching with date.
                            // https://www.cloudbees.com/sites/default/files/2016-jenkins-world-no_you_shouldnt_do_that_lessons_from_using_pipeline.pdf
                            deleteDir()
                            unstash 'workspace'
                            sh('find . -exec touch -d "`date`" {} \\;')

                            // Commit, Tag and Push
                            def tagName = nextVersion
                            scmUtils.commit(pwd(), "Jenkins Pipeline - Updated release version to ${nextVersion}", ['pom.xml'], env.BRANCH_NAME)
                            scmUtils.tag(pwd(), tagName, tagName)
                            scmUtils.push(pwd(), env.BRANCH_NAME)

                            buildInfo.env.capture = true
                            rtMaven.deployer.deployArtifacts buildInfo
                            server.publishBuildInfo buildInfo
                        }
                        catch (err) {
                            throw err
                        }
                        finally {
                            deleteDir()
                        }
                    }
                }
            }
        }
    }
}

def skippedStages() {
    stage('Checkout SCM & Setup Environment') {echo 'INFO: Skipped stage, deploying existing artifact'}
    stage('Build & Test') {echo 'INFO: Skipped stage, deploying existing artifact'}
    stage('Quality Gate') {echo 'INFO: Skipped stage, deploying existing artifact'}
    stage('Commit Git & Publish to Artifactory') {echo 'INFO Skipped stage, deploying existing artifact'}
}