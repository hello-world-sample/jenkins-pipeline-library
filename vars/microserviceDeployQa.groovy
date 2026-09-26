#!/usr/bin/env groovy

/**
 * Promote / redeploy to QA using helm/versions-qa.yaml.
 *
 *   @Library('pipeline-library') _
 *   microserviceDeployQa(app: 'hello-world', image: 'adamko034/hello-world')
 */
def call(Map config = [:]) {
    String app = config.app ?: error('microserviceDeployQa: app is required')
    String image = config.image ?: "adamko034/${app}"
    String chart = config.chart ?: "helm/${app}"
    String gitCreds = config.gitCredentialsId ?: 'github-pat'

    pipeline {
        agent any

        parameters {
            string(
                name: 'VERSION',
                defaultValue: '',
                description: 'Optional. Empty = deploy tag from versions-qa.yaml. Set to change versions-qa.yaml then deploy.'
            )
        }

        environment {
            DOCKER_IMAGE = "${image}"
            GIT_CREDENTIALS_ID = "${gitCreds}"
            APP_NAME = "${app}"
            CHART_REL = "${chart}"
        }

        stages {
            stage('Checkout deploy repo') {
                steps {
                    script {
                        def gitOps = new com.example.MsGitOps(this)
                        gitOps.checkoutDeployRepo()
                    }
                }
            }

            stage('Resolve version') {
                environment {
                    KUBECONFIG = credentials('minikube-kubeconfig')
                }
                steps {
                    script {
                        def gitOps = new com.example.MsGitOps(this)
                        def fromFile = gitOps.readAppVersion('deploy/helm/versions-qa.yaml', env.APP_NAME)
                        def version = params.VERSION?.trim()
                        if (version) {
                            if (version.endsWith('-SNAPSHOT')) {
                                error "SNAPSHOT versions are not allowed for QA: ${version}"
                            }
                            gitOps.setAppVersion('deploy/helm/versions-qa.yaml', env.APP_NAME, version)
                            gitOps.pushDeployVersions("chore(qa): ${env.APP_NAME} ${version}")
                        } else {
                            version = fromFile
                        }

                        def currentQa = sh(
                            script: """
                                helm get values ${env.APP_NAME} -n qa -o json 2>/dev/null \
                                  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('image',{}).get('tag',''))" \
                                  2>/dev/null || true
                            """,
                            returnStdout: true
                        ).trim()
                        env.CURRENT_QA_VERSION = currentQa ?: 'none'
                        env.VERSION = version

                        echo "versions-qa.yaml ${env.APP_NAME}: ${env.VERSION}"
                        echo "Current cluster QA: ${env.CURRENT_QA_VERSION}"
                    }
                }
            }

            stage('Confirm QA deploy') {
                options {
                    timeout(time: 1, unit: 'HOURS')
                }
                steps {
                    input message: "Deploy ${env.APP_NAME} ${env.VERSION} to QA? (cluster: ${env.CURRENT_QA_VERSION})"
                }
            }

            stage('Deploy QA') {
                environment {
                    KUBECONFIG = credentials('minikube-kubeconfig')
                }
                steps {
                    sh '''
                        helm upgrade --install ${APP_NAME} ./deploy/${CHART_REL} \
                          -n qa \
                          -f ./deploy/${CHART_REL}/values-qa.yaml \
                          --set image.repository=${DOCKER_IMAGE} \
                          --set image.tag=${VERSION} \
                          --create-namespace
                    '''
                }
            }
        }
    }
}
