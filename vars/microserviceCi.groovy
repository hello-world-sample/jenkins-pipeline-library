#!/usr/bin/env groovy

/**
 * Multibranch CI: feature (Maven), develop (Docker + DEV helm), master (CI gate).
 *
 * Usage in app Jenkinsfile:
 *   @Library('pipeline-library') _
 *   microserviceCi(app: 'hello-world', image: 'adamko034/hello-world')
 */
def call(Map config = [:]) {
    String app = config.app ?: error('microserviceCi: app is required')
    String image = config.image ?: "adamko034/${app}"
    String chart = config.chart ?: "helm/${app}"
    String gitCreds = config.gitCredentialsId ?: 'github-pat'
    String dockerCreds = config.dockerCredentialsId ?: 'dockerhub-cred'
    String releaseJob = config.releaseJob ?: "${app}-release"

    pipeline {
        agent any

        options {
            overrideIndexTriggers(false)
        }

        environment {
            DOCKER_IMAGE = "${image}"
            GIT_CREDENTIALS_ID = "${gitCreds}"
            APP_NAME = "${app}"
            CHART_REL = "${chart}"
            RELEASE_JOB = "${releaseJob}"
            DOCKER_CREDENTIALS_ID = "${dockerCreds}"
        }

        stages {
            stage('Checkout') {
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

            stage('Maven package') {
                steps {
                    sh 'mvn package -DskipTests'
                }
            }

            stage('Feature branch done') {
                when {
                    allOf {
                        not { branch 'develop' }
                        not { branch 'master' }
                    }
                }
                steps {
                    echo "Feature branch ${env.BRANCH_NAME}: Maven only — no Docker/deploy."
                }
            }

            stage('Master: CI gate') {
                when { branch 'master' }
                steps {
                    echo "Master CI gate passed. Release via ${env.RELEASE_JOB}; promote via deploy-qa / deploy-prod."
                }
            }

            stage('Develop: Docker build & push') {
                when { branch 'develop' }
                steps {
                    script {
                        env.IMAGE_TAG = sh(
                            script: "mvn -q -DforceStdout help:evaluate -Dexpression=project.version",
                            returnStdout: true
                        ).trim()
                        echo "Develop image tag: ${env.IMAGE_TAG}"

                        docker.withRegistry('', env.DOCKER_CREDENTIALS_ID) {
                            def img = docker.build("${env.DOCKER_IMAGE}:${env.IMAGE_TAG}")
                            img.push()
                        }
                    }
                }
            }

            stage('Develop: Deploy DEV') {
                when { branch 'develop' }
                environment {
                    KUBECONFIG = credentials('minikube-kubeconfig')
                }
                steps {
                    script {
                        def gitOps = new com.example.MsGitOps(this)
                        gitOps.checkoutDeployRepo()
                    }
                    sh '''
                        helm upgrade --install ${APP_NAME} ./deploy/${CHART_REL} \
                          -n dev \
                          -f ./deploy/${CHART_REL}/values-dev.yaml \
                          --set image.repository=${DOCKER_IMAGE} \
                          --set image.tag=${IMAGE_TAG} \
                          --create-namespace
                    '''
                }
            }
        }
    }
}
