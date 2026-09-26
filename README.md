# jenkins-pipeline-library

Shared Jenkins Pipeline Library for microservices (`hello-world`, `good-night-world`, …).

## Vars

| Call | Job type |
|------|----------|
| `microserviceCi(...)` | Multibranch CI |
| `microserviceRelease(...)` | Manual release |
| `microserviceDeployQa(...)` | Promote / redeploy QA |
| `microserviceDeployProd(...)` | Promote / redeploy PROD |

## App Jenkinsfile example

```groovy
@Library('pipeline-library') _

microserviceCi(
  app: 'hello-world',
  image: 'adamko034/hello-world'
)
```

Optional keys: `chart` (default `helm/<app>`), `gitCredentialsId`, `dockerCredentialsId`.

## Jenkins setup

Configured via CasC as global library `pipeline-library` from this repo (`main`).
