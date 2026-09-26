#!/usr/bin/env groovy

/**
 * Redeploy PROD from helm/versions-prod.yaml (no build parameters).
 *
 *   @Library('pipeline-library') _
 *   microserviceDeployProd(app: 'hello-world', image: 'adamko034/hello-world', namespace: 'hello-world-prod')
 */
def call(Map config = [:]) {
    String app = config.app ?: error('microserviceDeployProd: app is required')
    String image = config.image ?: "adamko034/${app}"
    String chart = config.chart ?: "helm/${app}"
    String namespace = config.namespace ?: error('microserviceDeployProd: namespace is required')
    String gitCreds = config.gitCredentialsId ?: 'github-pat'

    pipeline {
        agent any

        environment {
            DOCKER_IMAGE = "${image}"
            GIT_CREDENTIALS_ID = "${gitCreds}"
            APP_NAME = "${app}"
            CHART_REL = "${chart}"
            K8S_NAMESPACE = "${namespace}"
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
                        def version = gitOps.readAppVersion('deploy/helm/versions-prod.yaml', env.APP_NAME)
                        if (!version) {
                            error "No version for ${env.APP_NAME} in versions-prod.yaml"
                        }
                        if (version.endsWith('-SNAPSHOT')) {
                            error "SNAPSHOT versions are not allowed for PROD: ${version}"
                        }

                        def currentProd = sh(
                            script: """
                                helm get values ${env.APP_NAME} -n ${env.K8S_NAMESPACE} -o json 2>/dev/null \
                                  | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('image',{}).get('tag',''))" \
                                  2>/dev/null || true
                            """,
                            returnStdout: true
                        ).trim()
                        env.CURRENT_PROD_VERSION = currentProd ?: 'none'
                        env.VERSION = version

                        echo "versions-prod.yaml ${env.APP_NAME}: ${env.VERSION}"
                        echo "Current cluster PROD: ${env.CURRENT_PROD_VERSION}"
                    }
                }
            }

            stage('Confirm PROD deploy') {
                options {
                    timeout(time: 1, unit: 'HOURS')
                }
                steps {
                    input message: "Deploy ${env.APP_NAME} ${env.VERSION} to PROD? (cluster: ${env.CURRENT_PROD_VERSION})"
                }
            }

            stage('Deploy PROD') {
                environment {
                    KUBECONFIG = credentials('minikube-kubeconfig')
                }
                steps {
                    sh '''
                        helm upgrade --install ${APP_NAME} ./deploy/${CHART_REL} \
                          -n ${K8S_NAMESPACE} \
                          -f ./deploy/${CHART_REL}/values-prod.yaml \
                          --set image.repository=${DOCKER_IMAGE} \
                          --set image.tag=${VERSION} \
                          --create-namespace
                    '''
                }
            }
        }
    }
}
