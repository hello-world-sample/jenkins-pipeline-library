package com.example

/**
 * Shared GitOps helpers for the hello-world-deploy repository.
 * Used from vars/*.groovy as: def gitOps = new com.example.MsGitOps(this)
 */
class MsGitOps implements Serializable {
    def steps

    MsGitOps(steps) {
        this.steps = steps
    }

    String deployUrl() {
        return steps.env.DEPLOY_GIT_REPO_URL ?: 'https://github.com/hello-world-sample/hello-world-deploy.git'
    }

    String deployBranch() {
        return steps.env.DEPLOY_GIT_BRANCH ?: 'main'
    }

    String gitCreds() {
        return steps.env.GIT_CREDENTIALS_ID ?: 'github-pat'
    }

    void checkoutDeployRepo() {
        steps.dir('deploy') {
            steps.deleteDir()
            steps.git branch: deployBranch(), credentialsId: gitCreds(), url: deployUrl()
        }
    }

    String readAppVersion(String versionsFile, String app) {
        def v = steps.sh(
            script: """awk -F': *' -v app='${app}' '\$1 == app { gsub(/[" ]/, "", \$2); print \$2; exit }' ${versionsFile}""",
            returnStdout: true
        ).trim()
        if (!v) {
            steps.error("No version for '${app}' in ${versionsFile}")
        }
        return v
    }

    void setAppVersion(String versionsFile, String app, String version) {
        steps.sh """
            python3 - <<'PY'
from pathlib import Path
path = Path('${versionsFile}')
app = '${app}'
version = '${version}'
lines = path.read_text().splitlines()
out = []
found = False
for line in lines:
    stripped = line.strip()
    if not stripped or stripped.startswith('#') or ':' not in line:
        out.append(line)
        continue
    key = line.split(':', 1)[0].strip()
    if key == app:
        out.append(f'{app}: "{version}"')
        found = True
    else:
        out.append(line)
if not found:
    out.append(f'{app}: "{version}"')
path.write_text("\\n".join(out) + "\\n")
print(f'Updated {path}: {app}={version}')
PY
        """
    }

    void pushDeployVersions(String message) {
        steps.withCredentials([steps.usernamePassword(
            credentialsId: gitCreds(),
            usernameVariable: 'GIT_USER',
            passwordVariable: 'GIT_PASS'
        )]) {
            steps.sh """
                set +x
                cd deploy
                git config user.email 'jenkins@local'
                git config user.name 'Jenkins'
                git add helm/versions-qa.yaml helm/versions-prod.yaml
                git diff --cached --quiet && echo 'No version file changes' && exit 0
                git commit -m '${message}'
                REMOTE_PATH=\$(git config --get remote.origin.url | sed -E 's#https?://##' | sed -E 's#git@([^:]+):#\\1/#')
                git push "https://\${GIT_USER}:\${GIT_PASS}@\${REMOTE_PATH}" HEAD:${deployBranch()}
            """
        }
    }

    static String bumpSemVer(String version, String bumpType) {
        def parts = version.tokenize('.').collect { it as int }
        while (parts.size() < 3) {
            parts << 0
        }
        if (bumpType == 'major') {
            parts[0]++
            parts[1] = 0
            parts[2] = 0
        } else if (bumpType == 'minor') {
            parts[1]++
            parts[2] = 0
        } else {
            parts[2]++
        }
        return parts.join('.')
    }
}
