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
  image: 'adamko034/hello-world',
  namespace: 'hello-world-dev'
)
```

Required: `app`, `namespace`. Optional: `image` (default `adamko034/<app>`), `chart` (default `helm/<app>`), `gitCredentialsId`, `dockerCredentialsId`.

Both apps share the same namespaces per env (`hello-world-dev` / `hello-world-qa` / `hello-world-prod`).

## Jenkins setup

Configured via CasC as global library `pipeline-library` from this repo (`main`).
