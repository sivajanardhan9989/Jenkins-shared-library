def updateCommitStatus(String state, String description, String context = 'Jenkins CI') {
    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        def repoUrl = sh(script: 'git remote get-url origin', returnStdout: true).trim()
        def repoPath = repoUrl.replaceAll(/.*github\.com[\/:]/, '').replaceAll(/\.git$/, '')

        // GitHub's Commit Status API only accepts lowercase state values
        // (error|failure|pending|success); anything else gets a 422.
        withEnv([
            "COMMIT_STATE=${state.toLowerCase()}",
            "COMMIT_DESC=${description}",
            "COMMIT_CONTEXT=${context}",
            "REPO_PATH=${repoPath}",
            "COMMIT_SHA=${env.GIT_COMMIT}",
            "BUILD_LINK=${env.BUILD_URL}"
        ]) {
            sh '''
                HTTP_STATUS=$(jq -n \
                    --arg state   "$COMMIT_STATE" \
                    --arg url     "$BUILD_LINK" \
                    --arg desc    "$COMMIT_DESC" \
                    --arg context "$COMMIT_CONTEXT" \
                    '{state: $state, target_url: $url, description: $desc, context: $context}' \
                | curl -s \
                       -o commit-status-response.json \
                       -w "%{http_code}" \
                       -X POST \
                       -H "Authorization: Bearer $GITHUB_TOKEN" \
                       -H "Accept: application/vnd.github+json" \
                       -H "Content-Type: application/json" \
                       -H "X-GitHub-Api-Version: 2022-11-28" \
                       --data @- \
                       "https://api.github.com/repos/$REPO_PATH/statuses/$COMMIT_SHA")

                if [ "$HTTP_STATUS" -lt 200 ] || [ "$HTTP_STATUS" -ge 300 ]; then
                    echo "GitHub commit status update failed (HTTP $HTTP_STATUS) for context '$COMMIT_CONTEXT':"
                    cat commit-status-response.json
                    rm -f commit-status-response.json
                    exit 1
                fi
                rm -f commit-status-response.json
            '''
        }
    }
}


def validateCommitStatus(String commitSha, List requiredContexts) {
    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        def repoUrl = sh(script: 'git remote get-url origin', returnStdout: true).trim()
        def repoPath = repoUrl.replaceAll(/.*github\.com[\/:]/, '').replaceAll(/\.git$/, '')

        withEnv([
            "REPO_PATH=${repoPath}",
            "COMMIT_SHA=${commitSha}",
            "REQUIRED_CONTEXTS=${requiredContexts.join(',')}"
        ]) {
            sh '''
                set -e

                curl -sf \
                    -H "Authorization: Bearer $GITHUB_TOKEN" \
                    -H "Accept: application/vnd.github+json" \
                    -H "X-GitHub-Api-Version: 2022-11-28" \
                    "https://api.github.com/repos/$REPO_PATH/commits/$COMMIT_SHA/status" \
                    -o commit-status.json

                FAILED=0
                for CTX in $(echo "$REQUIRED_CONTEXTS" | tr ',' ' '); do
                    STATE=$(jq -r --arg ctx "$CTX" '.statuses[] | select(.context == $ctx) | .state' commit-status.json | head -n1)
                    echo "context=$CTX state=${STATE:-missing}"
                    if [ "$STATE" != "success" ]; then
                        FAILED=1
                    fi
                done

                rm -f commit-status.json

                if [ "$FAILED" -eq 1 ]; then
                    echo "One or more required status checks are not successful for commit $COMMIT_SHA"
                    exit 1
                fi
            '''
        }
    }
}


/ Jenkins agent doesn't resolve *.svc.cluster.local (it's not using the cluster's
// CoreDNS as its resolver), but it IS in the same VPC as the EKS nodes, and EKS pods
// get real routable VPC IPs — so route to the pod directly by IP instead of by DNS name.
def getPodIP(String namespace, String component) {
    def ip = sh(
        script: "kubectl get pod -n ${namespace} -l component=${component} -o jsonpath='{.items[0].status.podIP}'",
        returnStdout: true
    ).trim()
    if (!ip) {
        error("Could not resolve a pod IP for component '${component}' in namespace '${namespace}' — is it deployed and running?")
    }
    return ip
}

def tagCommit(String commitSha, String tag) {
    withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
        def repoUrl = sh(script: 'git remote get-url origin', returnStdout: true).trim()
        def repoPath = repoUrl.replaceAll(/.*github\.com[\/:]/, '').replaceAll(/\.git$/, '')

        withEnv([
            "REPO_PATH=${repoPath}",
            "COMMIT_SHA=${commitSha}",
            "TAG_NAME=${tag}"
        ]) {
            sh '''
                HTTP_STATUS=$(jq -n \
                    --arg ref "refs/tags/$TAG_NAME" \
                    --arg sha "$COMMIT_SHA" \
                    '{ref: $ref, sha: $sha}' \
                | curl -s \
                       -o tag-response.json \
                       -w "%{http_code}" \
                       -X POST \
                       -H "Authorization: Bearer $GITHUB_TOKEN" \
                       -H "Accept: application/vnd.github+json" \
                       -H "X-GitHub-Api-Version: 2022-11-28" \
                       --data @- \
                       "https://api.github.com/repos/$REPO_PATH/git/refs")

                if [ "$HTTP_STATUS" -lt 200 ] || [ "$HTTP_STATUS" -ge 300 ]; then
                    echo "Failed to tag commit $COMMIT_SHA as $TAG_NAME (HTTP $HTTP_STATUS):"
                    cat tag-response.json
                    rm -f tag-response.json
                    exit 1
                fi
                rm -f tag-response.json
            '''
        }
    }
}
