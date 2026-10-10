def call(Map configMap = [:]) {
    def appVersion = ''
    def acc_id = configMap.get('acc_id') ?: '209115427888'
    def project = configMap.get('project') ?: 'roboshop'
    def component = configMap.get('component') ?: 'catalogue'

    pipeline {
        agent {
            node {
                label 'ROBOSHOP'
            }
        }

        options {
            disableConcurrentBuilds()
            timeout(time: 30, unit: 'MINUTES')
        }

        stages {
            stage('read-version') {
                steps {
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appVersion = packageJson.version

                        if (!appVersion) {
                            error 'Version is missing from package.json'
                        }

                        echo "Application version: ${appVersion}"
                    }
                }
            }

            stage('install-dependencies') {
                steps {
                    sh 'npm install'
                }
            }

            stage('unit-tests') {
                steps {
                    script {
                        try {
                            sh 'npm test'
                            utils.updateCommitStatus('success', 'Unit tests passed', 'unit-tests')
                        } catch (Exception e) {
                            echo "Unit tests failed: ${e.message}"
                            utils.updateCommitStatus('failure', 'Unit tests failed', 'unit-tests')
                            throw e
                        }
                    }
                }
            }

            stage('library-scan') {
                steps {
                    script {
                        try {
                            withCredentials([
                                string(credentialsId: 'github-token', variable: 'GH_TOKEN')
                            ]) {
                                withEnv(['SCAN_REPO=sivajanardhan9989/Cataloguee']) {
                                    sh '''
                                        set -eu

                                        HTTP_STATUS=$(curl -sS \
                                          -o alerts.json \
                                          -w "%{http_code}" \
                                          -H "Accept: application/vnd.github+json" \
                                          -H "Authorization: Bearer $GH_TOKEN" \
                                          -H "X-GitHub-Api-Version: 2022-11-28" \
                                          "https://api.github.com/repos/$SCAN_REPO/dependabot/alerts?state=open&per_page=100")

                                        if [ "$HTTP_STATUS" -lt 200 ] || [ "$HTTP_STATUS" -ge 300 ]; then
                                            echo "Dependabot API failed: HTTP $HTTP_STATUS"
                                            jq -r '.message // .' alerts.json
                                            rm -f alerts.json
                                            exit 1
                                        fi

                                        if ! jq -e 'type == "array"' alerts.json >/dev/null; then
                                            echo "Unexpected GitHub API response:"
                                            jq '.' alerts.json
                                            rm -f alerts.json
                                            exit 1
                                        fi

                                        echo "---- Open Dependabot Alerts ----"
                                        jq -r '.[] | [
                                          (.number | tostring),
                                          (.security_vulnerability.severity // "unknown"),
                                          (.dependency.package.name // "unknown"),
                                          (.security_advisory.ghsa_id // "unknown")
                                        ] | @tsv' alerts.json

                                        HIGH_CRITICAL_COUNT=$(jq '[
                                          .[] |
                                          select(
                                            .security_vulnerability.severity == "high" or
                                            .security_vulnerability.severity == "critical"
                                          )
                                        ] | length' alerts.json)

                                        echo "High/Critical alert count: $HIGH_CRITICAL_COUNT"
                                        rm -f alerts.json

                                        if [ "$HIGH_CRITICAL_COUNT" -gt 0 ]; then
                                            echo "High/Critical dependency alerts found."
                                            exit 1
                                        fi

                                        echo "No High/Critical dependency alerts found."
                                    '''
                                }
                            }

                            utils.updateCommitStatus('success', 'Dependabot scan passed', 'library-scan')
                        } catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Dependabot scan failed', 'library-scan')
                            echo "Dependency scan failed: ${e.message}"
                            throw e
                        }
                    }
                }
            }

            stage('build-image') {
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                            sh """
                                set -eu
                                aws ecr get-login-password --region us-east-1 | \
                                  docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com

                                docker build -t ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion} .
                            """
                        }

                        utils.updateCommitStatus('success', 'Docker image built', 'build-image')
                    }
                }
            }

            stage('push-image-to-ecr') {
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                            sh """
                                set -eu
                                docker push ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                            """
                        }

                        utils.updateCommitStatus('success', 'Image pushed to ECR', 'push-image')
                    }
                }
            }

            stage('dev-deploy') {
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    set -eu
                                    aws eks update-kubeconfig --name roboshop --region us-east-1

                                    helm upgrade --install ${component} ./helm \
                                      -f ./helm/values-dev.yaml \
                                      --namespace roboshop-dev \
                                      --create-namespace \
                                      --set deployment.imageVersion=${appVersion} \
                                      --wait --timeout 5m

                                    kubectl rollout status deployment/${component} \
                                      -n roboshop-dev --timeout=120s
                                """
                            }

                            utils.updateCommitStatus('success', 'Deployed to roboshop-dev', 'dev-deploy')
                        } catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Dev deployment failed', 'dev-deploy')
                            echo "Dev deployment failed: ${e.message}"
                            throw e
                        }
                    }
                }
            }

            stage('api-tests') {
                options {
                    timeout(time: 5, unit: 'MINUTES')
                }
                steps {
                    script {
                        try {
                            build job: 'ROBOSHOP/catalogue-api-tests',
                                parameters: [
                                    string(name: 'NAMESPACE', value: 'roboshop-dev'),
                                    string(name: 'COMMIT_ID', value: env.GIT_COMMIT ?: '')
                                ],
                                wait: true,
                                propagate: true

                            utils.updateCommitStatus('success', 'Catalogue API tests passed', 'api-tests')
                        } catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Catalogue API tests failed', 'api-tests')
                            echo "API tests failed: ${e.message}"
                            throw e
                        }
                    }
                }
            }

            stage('raise-pr') {
                when {
                    expression {
                        env.BRANCH_NAME && env.BRANCH_NAME != 'main'
                    }
                }
                steps {
                    withCredentials([
                        string(credentialsId: 'github-token', variable: 'GH_TOKEN')
                    ]) {
                        withEnv([
                            "PR_HEAD=${env.BRANCH_NAME}",
                            'PR_REPO=sivajanardhan9989/Cataloguee',
                            'PR_REPO_OWNER=sivajanardhan9989',
                            "PR_TITLE=${component}: ${env.BRANCH_NAME} -> main",
                            "PR_BODY=Automated PR after successful dev deployment and API tests. Build: ${env.BUILD_URL}console"
                        ]) {
                            sh '''
                                set -eu

                                curl -fsSL --get \
                                  -H "Authorization: Bearer $GH_TOKEN" \
                                  -H "Accept: application/vnd.github+json" \
                                  -H "X-GitHub-Api-Version: 2022-11-28" \
                                  --data-urlencode "state=open" \
                                  --data-urlencode "base=main" \
                                  --data-urlencode "head=$PR_REPO_OWNER:$PR_HEAD" \
                                  "https://api.github.com/repos/$PR_REPO/pulls" \
                                  -o prs.json

                                if ! jq -e 'type == "array"' prs.json >/dev/null; then
                                    echo "Unexpected response while checking pull requests:"
                                    jq '.' prs.json
                                    rm -f prs.json
                                    exit 1
                                fi

                                PR_NUMBER=$(jq -r '.[0].number // empty' prs.json)
                                rm -f prs.json

                                if [ -n "$PR_NUMBER" ]; then
                                    echo "Open pull request already exists: #$PR_NUMBER"
                                    exit 0
                                fi

                                jq -n \
                                  --arg title "$PR_TITLE" \
                                  --arg head "$PR_HEAD" \
                                  --arg base "main" \
                                  --arg body "$PR_BODY" \
                                  '{title:$title, head:$head, base:$base, body:$body}' \
                                  > pr-payload.json

                                HTTP_STATUS=$(curl -sS \
                                  -o pr-response.json \
                                  -w "%{http_code}" \
                                  -X POST \
                                  -H "Authorization: Bearer $GH_TOKEN" \
                                  -H "Accept: application/vnd.github+json" \
                                  -H "X-GitHub-Api-Version: 2022-11-28" \
                                  -H "Content-Type: application/json" \
                                  --data @pr-payload.json \
                                  "https://api.github.com/repos/$PR_REPO/pulls")

                                if [ "$HTTP_STATUS" -lt 200 ] || [ "$HTTP_STATUS" -ge 300 ]; then
                                    echo "Failed to create pull request: HTTP $HTTP_STATUS"
                                    jq -r '.message // .' pr-response.json
                                    rm -f pr-payload.json pr-response.json
                                    exit 1
                                fi

                                echo "Pull request created:"
                                jq -r '.number, .html_url' pr-response.json
                                rm -f pr-payload.json pr-response.json
                            '''
                        }
                    }
                }
            }
        }

        post {
            success {
                echo "Catalogue pipeline succeeded: ${component}"
            }
            failure {
                echo "Catalogue pipeline failed: ${component}"
            }
        }
    }
}
