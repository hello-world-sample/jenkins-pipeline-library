#!/usr/bin/env groovy

/**
 * Manual release from master: bump, Docker, tag, versions-qa, optional QA helm, sync develop.
 *
 *   @Library('pipeline-library') _
 *   microserviceRelease(app: 'hello-world', image: 'adamko034/hello-world')
 */
def call(Map config = [:]) {
    String app = config.app ?: error('microserviceRelease: app is required')
    String image = config.image ?: "adamko034/${app}"
    String chart = config.chart ?: "helm/${app}"
    String gitCreds = config.gitCredentialsId ?: 'github-pat'
    String dockerCreds = config.dockerCredentialsId ?: 'dockerhub-cred'
    String deployQaJob = config.deployQaJob ?: "${app}-deploy-qa"

    pipeline {
        agent any

        parameters {
            choice(
                name: 'BUMP',
                choices: ['patch', 'minor', 'major'],
                description: 'Release bump from current master SNAPSHOT.'
            )
            booleanParam(
                name: 'DEPLOY_QA',
                defaultValue: true,
                description: "Deploy this release to QA. Uncheck to skip (use ${deployQaJob} later)."
            )
        }

        environment {
            DOCKER_IMAGE = "${image}"
            GIT_CREDENTIALS_ID = "${gitCreds}"
            APP_NAME = "${app}"
            CHART_REL = "${chart}"
            DOCKER_CREDENTIALS_ID = "${dockerCreds}"
        }

        stages {
            stage('Checkout master') {
                steps {
                    checkout scm
                }
            }

            stage('Maven build') {
                steps {
                    sh 'mvn clean compile'
                }
            }

            stage('Maven test') {
                steps {
                    sh 'mvn test'
                }
            }

            stage('Set release version') {
                steps {
                    script {
                        def bump = params.BUMP?.trim()
                        if (!(bump in ['patch', 'minor', 'major'])) {
                            error "Invalid BUMP: ${params.BUMP}"
                        }
                        echo "Release bump: ${bump}"

                        def current = sh(
                            script: "mvn -q -DforceStdout help:evaluate -Dexpression=project.version",
                            returnStdout: true
                        ).trim()

                        if (!current.endsWith('-SNAPSHOT')) {
                            error "Expected a SNAPSHOT version on master, got: ${current}"
                        }

                        def base = current.replace('-SNAPSHOT', '')
                        def releaseVersion = (bump == 'patch') ? base : com.example.MsGitOps.bumpSemVer(base, bump)
                        def nextSnapshot = com.example.MsGitOps.bumpSemVer(releaseVersion, 'patch') + '-SNAPSHOT'

                        env.RELEASE_VERSION = releaseVersion
                        env.NEXT_SNAPSHOT = nextSnapshot
                        env.IMAGE_TAG = releaseVersion

                        echo "Current: ${current}"
                        echo "Release version: ${releaseVersion}"
                        echo "Next SNAPSHOT: ${nextSnapshot}"

                        sh "mvn -q versions:set -DnewVersion=${releaseVersion} -DgenerateBackupPoms=false"
                    }
                }
            }

            stage('Maven package') {
                steps {
                    sh 'mvn package -DskipTests'
                }
            }

            stage('Docker, tag, push') {
                steps {
                    script {
                        docker.withRegistry('', env.DOCKER_CREDENTIALS_ID) {
                            def img = docker.build("${env.DOCKER_IMAGE}:${env.IMAGE_TAG}")
                            img.push()
                        }

                        sh """
                            git config user.email 'jenkins@local'
                            git config user.name 'Jenkins'
                            git add pom.xml
                            git commit -m "Release ${env.RELEASE_VERSION}" || true
                            git tag -a ${env.RELEASE_VERSION} -m "Release ${env.RELEASE_VERSION}"
                        """

                        sh "mvn -q versions:set -DnewVersion=${env.NEXT_SNAPSHOT} -DgenerateBackupPoms=false"

                        sh """
                            git add pom.xml
                            git commit -m "Prepare next development version ${env.NEXT_SNAPSHOT}"
                        """

                        withCredentials([usernamePassword(
                            credentialsId: env.GIT_CREDENTIALS_ID,
                            usernameVariable: 'GIT_USER',
                            passwordVariable: 'GIT_PASS'
                        )]) {
                            sh '''
                                set +x
                                REMOTE_PATH=$(git config --get remote.origin.url | sed -E 's#https?://##' | sed -E 's#git@([^:]+):#\\1/#')
                                git push "https://${GIT_USER}:${GIT_PASS}@${REMOTE_PATH}" HEAD:master
                                git push "https://${GIT_USER}:${GIT_PASS}@${REMOTE_PATH}" "${RELEASE_VERSION}"
                            '''
                        }
                    }
                }
            }

            stage('Update versions-qa + Deploy QA') {
                environment {
                    KUBECONFIG = credentials('minikube-kubeconfig')
                }
                steps {
                    script {
                        def gitOps = new com.example.MsGitOps(this)
                        gitOps.checkoutDeployRepo()
                        gitOps.setAppVersion('deploy/helm/versions-qa.yaml', env.APP_NAME, env.RELEASE_VERSION)
                        gitOps.pushDeployVersions("chore(qa): ${env.APP_NAME} ${env.RELEASE_VERSION}")

                        if (params.DEPLOY_QA) {
                            sh '''
                                helm upgrade --install ${APP_NAME} ./deploy/${CHART_REL} \
                                  -n qa \
                                  -f ./deploy/${CHART_REL}/values-qa.yaml \
                                  --set image.repository=${DOCKER_IMAGE} \
                                  --set image.tag=${RELEASE_VERSION} \
                                  --create-namespace
                            '''
                        } else {
                            echo 'DEPLOY_QA=false — versions-qa.yaml updated; skipping Helm deploy.'
                        }
                    }
                }
            }

            stage('Sync master → develop') {
                steps {
                    script {
                        withCredentials([usernamePassword(
                            credentialsId: env.GIT_CREDENTIALS_ID,
                            usernameVariable: 'GIT_USER',
                            passwordVariable: 'GIT_PASS'
                        )]) {
                            def synced = sh(
                                script: '''
                                    set +x
                                    REMOTE_PATH=$(git config --get remote.origin.url | sed -E 's#https?://##' | sed -E 's#git@([^:]+):#\\1/#')
                                    AUTH_URL="https://${GIT_USER}:${GIT_PASS}@${REMOTE_PATH}"

                                    git config user.email 'jenkins@local'
                                    git config user.name 'Jenkins'

                                    git fetch "${AUTH_URL}" +refs/heads/master:refs/remotes/origin/master \
                                                          +refs/heads/develop:refs/remotes/origin/develop

                                    git checkout -B develop origin/develop

                                    set +e
                                    git merge origin/master -m "Merge master into develop after release ${RELEASE_VERSION}"
                                    MERGE_STATUS=$?
                                    set -e

                                    if [ "$MERGE_STATUS" -ne 0 ]; then
                                        git merge --abort 2>/dev/null || true
                                        echo "ERROR: Conflict merging master into develop after release ${RELEASE_VERSION}."
                                        echo "Resolve manually: checkout develop, merge master, fix conflicts (usually pom.xml), push develop."
                                        exit 1
                                    fi

                                    git push "${AUTH_URL}" HEAD:develop
                                ''',
                                returnStatus: true
                            )
                            if (synced != 0) {
                                error "Failed to sync master → develop after release ${env.RELEASE_VERSION}."
                            }
                            echo "Synced master → develop after release ${env.RELEASE_VERSION}"
                        }
                    }
                }
            }
        }
    }
}
