// vim: set filetype=groovy:

@Library(["camunda-ci", "zeebe-jenkins-shared-library"]) _

def buildName = "${env.JOB_BASE_NAME.replaceAll("%2F", "-").replaceAll("\\.", "-").take(20)}-${env.BUILD_ID}"

def masterBranchName = 'master'
def isMasterBranch = env.BRANCH_NAME == masterBranchName
def developBranchName = 'develop'
def isDevelopBranch = env.BRANCH_NAME == developBranchName

//for develop branch keep builds for 7 days to be able to analyse build errors, for all other branches, keep the last 10 builds
def daysToKeep = isDevelopBranch ? '7' : '-1'
def numToKeep = isDevelopBranch ? '-1' : '10'

//the develop branch should be run hourly to detect flaky tests and instability, other branches only on commit
def cronTrigger = isDevelopBranch ? '@hourly' : ''

pipeline {
    agent {
        kubernetes {
            cloud 'zeebe-ci'
            label "zeebe-ci-build_${buildName}"
            defaultContainer 'jnlp'
            yamlFile '.ci/podSpecs/distribution.yml'
        }
    }

    environment {
        NEXUS = credentials("camunda-nexus")
        SONARCLOUD_TOKEN = credentials('zeebe-sonarcloud-token')
    }

    triggers {
        cron(cronTrigger)
    }

    options {
        buildDiscarder(logRotator(daysToKeepStr: daysToKeep, numToKeepStr: numToKeep))
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
    }

    stages {
        stage('Prepare') {
            steps {
                setHumanReadableBuildDisplayName()

                prepareMavenContainer()
                prepareMavenContainer('jdk8')
                container('golang') {
                    sh '.ci/scripts/distribution/prepare-go.sh'
                }

            }
        }

        stage('Build (Java)') {
            environment {
                VERSION = readMavenPom(file: 'parent/pom.xml').getVersion()
            }
            steps {
                runMavenContainerCommand('.ci/scripts/distribution/build-java.sh')
                container('maven') {
                    sh 'cp dist/target/zeebe-distribution-*.tar.gz zeebe-distribution.tar.gz'
                }
                stash name: "zeebe-build", includes: "m2-repository/io/zeebe/*/${VERSION}/*"
                stash name: "zeebe-distro", includes: "zeebe-distribution.tar.gz"
            }
        }

        stage('Prepare Tests') {
            environment {
                IMAGE = "camunda/zeebe"
                VERSION = readMavenPom(file: 'parent/pom.xml').getVersion()
                TAG = 'current-test'
            }

            steps {
                container('docker') {
                    sh '.ci/scripts/docker/build.sh'
                    sh '.ci/scripts/docker/build_zeebe-hazelcast-exporter.sh'
                }
            }
        }


        stage('Test') {
            parallel {
                stage('Go') {
                    steps {
                        container('golang') {
                            sh '.ci/scripts/distribution/build-go.sh'
                        }

                        container('golang') {
                            sh '.ci/scripts/distribution/test-go.sh'
                        }
                    }

                    post {
                        always {
                            junit testResults: "**/*/TEST-go.xml", keepLongStdio: true
                        }
                    }
               }

               stage('Analyse (Java)') {
                      steps {
                          runMavenContainerCommand('.ci/scripts/distribution/analyse-java.sh')
                      }
                }

                stage('Unit (Java)') {
                    environment {
                        SUREFIRE_REPORT_NAME_SUFFIX = 'java-testrun'
                    }

                    steps {
                        runMavenContainerCommand('.ci/scripts/distribution/test-java.sh')
                    }

                    post {
                        always {
                            junit testResults: "**/*/TEST*${SUREFIRE_REPORT_NAME_SUFFIX}*.xml", keepLongStdio: true
                        }
                    }
                }
                stage('Unit 8 (Java 8)') {
                    environment {
                        SUREFIRE_REPORT_NAME_SUFFIX = 'java8-testrun'
                    }

                    steps {
                        runMavenContainerCommand('.ci/scripts/distribution/test-java8.sh', 'jdk8')
                    }

                    post {
                        always {
                            junit testResults: "**/*/TEST*${SUREFIRE_REPORT_NAME_SUFFIX}*.xml", keepLongStdio: true
                        }
                    }
                }

                stage('IT (Java)') {
                    agent {
                        kubernetes {
                            cloud 'zeebe-ci'
                            label "zeebe-ci-build_${buildName}_it"
                            defaultContainer 'jnlp'
                            yamlFile '.ci/podSpecs/distribution.yml'
                        }
                    }

                    environment {
                        SUREFIRE_REPORT_NAME_SUFFIX = 'it-testrun'
                        IMAGE = "camunda/zeebe"
                        VERSION = readMavenPom(file: 'parent/pom.xml').getVersion()
                        TAG = 'current-test'
                    }

                    steps {
                        prepareMavenContainer()
                        unstash name: "zeebe-build"
                        unstash name: "zeebe-distro"
                        container('docker') {
                            sh '.ci/scripts/docker/build.sh'
                        }
                        runMavenContainerCommand('.ci/scripts/distribution/it-java.sh')
                    }

                    post {
                        always {
                            junit testResults: "**/*/TEST*${SUREFIRE_REPORT_NAME_SUFFIX}*.xml", keepLongStdio: true
                        }
                    }
                }

                stage('BPMN TCK') {
                    steps {
                        runMavenContainerCommand('.ci/scripts/distribution/test-tck.sh')
                    }

                    post {
                        always {
                            junit testResults: "bpmn-tck/**/*/TEST*.xml", keepLongStdio: true
                        }
                    }
                }
            }

            post {
                always {
                    jacoco(
                            execPattern: '**/*.exec',
                            classPattern: '**/target/classes',
                            sourcePattern: '**/src/main/java,**/generated-sources/protobuf/java,**/generated-sources/assertj-assertions,**/generated-sources/sbe',
                            exclusionPattern: '**/io/zeebe/gateway/protocol/**,'
                                    + '**/*Encoder.class,**/*Decoder.class,**/MetaAttribute.class,'
                                    + '**/io/zeebe/protocol/record/**/*Assert.class,**/io/zeebe/protocol/record/Assertions.class,', // classes from generated resources
                            runAlways: true
                    )
                    zip zipFile: 'test-coverage-reports.zip', archive: true, glob: "**/target/site/jacoco/**"
                }
                failure {
                    zip zipFile: 'test-reports.zip', archive: true, glob: "**/*/surefire-reports/**"
                    archive "**/hs_err_*.log"

                    script {
                        if (fileExists('./target/FlakyTests.txt')) {
                            currentBuild.description = "Flaky Tests: <br>" + readFile('./target/FlakyTests.txt').split('\n').join('<br>')
                        }
                    }
                }
            }
        }

        stage('Upload') {
            when { allOf { branch developBranchName; not { triggeredBy 'TimerTrigger' } } }
            steps {
                retry(3) {
                    runMavenContainerCommand('.ci/scripts/distribution/upload.sh')
                }
            }
        }

        stage('Post') {
            when { not { triggeredBy 'TimerTrigger' } }

            parallel {
                stage('Docker') {
                    when { branch developBranchName }

                    environment {
                        VERSION = readMavenPom(file: 'parent/pom.xml').getVersion()
                    }

                    steps {
                        retry(3) {
                            build job: 'zeebe-docker', parameters: [
                                    string(name: 'BRANCH', value: env.BRANCH_NAME),
                                    string(name: 'VERSION', value: env.VERSION),
                                    booleanParam(name: 'IS_LATEST', value: isMasterBranch),
                                    booleanParam(name: 'PUSH', value: isDevelopBranch)
                            ]
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            // Retrigger the build if there were connection issues
            script {
                if (agentDisconnected()) {
                    currentBuild.result = 'ABORTED'
                    currentBuild.description = "Aborted due to connection error"

                    build job: currentBuild.projectName, propagate: false, quietPeriod: 60, wait: false
                }

                String userReason = null
                if (currentBuild.description ==~ /.*Flaky Tests.*/) {
                    userReason = 'flaky-tests'
                }
                org.camunda.helper.CIAnalytics.trackBuildStatus(this, userReason)
            }
        }
        failure {
            script {
                if (env.BRANCH_NAME != 'develop' || agentDisconnected()) {
                    return
                }

                sendZeebeSlackMessage()
            }
        }
        changed {
            script {
                if (env.BRANCH_NAME != 'develop' || agentDisconnected()) {
                    return
                }
                if (currentBuild.currentResult == 'FAILURE') {
                    return // already handled above
                }
                if (!hasBuildResultChanged()) {
                    return
                }

                sendZeebeSlackMessage()
            }
        }
    }
}

//////////////////// Helper functions ////////////////////

def getMavenContainerNameForJDK(String jdk = null) {
    "maven${jdk ? '-'+jdk : ''}"
}

def prepareMavenContainer(String jdk = null) {
    container(getMavenContainerNameForJDK(jdk)) {
        sh '.ci/scripts/distribution/prepare.sh'
    }
}

def runMavenContainerCommand(String shellCommand, String jdk = null) {
    container(getMavenContainerNameForJDK(jdk)) {
        configFileProvider([configFile(fileId: 'maven-nexus-settings-zeebe-local-repo', variable: 'MAVEN_SETTINGS_XML')]) {
            sh shellCommand
        }
    }
}

// TODO: can be extracted to zeebe-jenkins-shared-library
def setHumanReadableBuildDisplayName(int maximumLength = 45) {
    script {
        commit_summary = sh([returnStdout: true, script: 'git show -s --format=%s']).trim()
        displayNameFull = "#${env.BUILD_NUMBER}: ${commit_summary}"

        if (displayNameFull.length() <= maximumLength) {
            currentBuild.displayName = displayNameFull
        } else {
            displayStringHardTruncate = displayNameFull.take(maximumLength)
            currentBuild.displayName = displayStringHardTruncate.take(displayStringHardTruncate.lastIndexOf(' '))
        }
    }
}

// TODO: can be extracted to zeebe-jenkins-shared-library
def sendZeebeSlackMessage() {
    echo "Send slack message"
    slackSend(
        channel: "#zeebe-ci${jenkins.model.JenkinsLocationConfiguration.get()?.getUrl()?.contains('stage') ? '-stage' : ''}",
        message: "Zeebe ${env.BRANCH_NAME} build ${currentBuild.absoluteUrl} changed status to ${currentBuild.currentResult}")
}
