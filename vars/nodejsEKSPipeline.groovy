
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
            timeout(time: 15, unit: 'MINUTES')
        }

        stages {
            stage('read-version') {
                steps {
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appVersion = packageJson.version
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
                            utils.updateCommitStatus(
                                'success',
                                'Unit tests passed',
                                'unit-tests'
                            )
                        } catch (Exception e) {
                            echo "Unit tests or status reporting failed: ${e.message}"
                            throw e
                        }
                    }
                }
            }

            /*
            stage('sonar-analysis') {
                steps {
                    script {
                        def scannerHome = tool 'sonar-scanner'
                        withSonarQubeEnv('sonar-server') {
                            sh """
                                "${scannerHome}/bin/sonar-scanner" \
                                  -Dsonar.projectKey=Roboshop-catalogue \
                                  -Dsonar.projectName=Roboshop-catalogue \
                                  -Dsonar.sources=. \
                                  -Dsonar.exclusions=node_modules/**,coverage/**,.git/**
                            """
                        }
                    }
                }
            }

            stage('sonar-quality-gate') {
                steps {
                    timeout(time: 10, unit: 'MINUTES') {
                        script {
                            def qg = waitForQualityGate()
                            if (qg.status != 'OK') {
                                error "SonarQube Quality Gate failed: ${qg.status}"
                            }
                        }
                    }
                }
            }
            */

            stage('library-scan') {
                steps {
                    script {
                        try {
                            withCredentials([
                                string(
                                    credentialsId: 'github-token',
                                    variable: 'GH_TOKEN'
                                )
                            ]) {
                                withEnv([
                                    "SCAN_REPO=sivajanardhan9989/Cataloguee"
                                ]) {
                                    sh '''
                                        set -eu

                                        HTTP_STATUS=$(curl -sS -L \
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
                                            echo "Unexpected Dependabot API response:"
                                            jq '.' alerts.json
                                            rm -f alerts.json
                                            exit 1
                                        fi

                                        echo "---- Open Dependabot Alerts ----"

                                        jq -r '.[] |
                                            [
                                                (.number | tostring),
                                                .security_vulnerability.severity,
                                                .dependency.package.name,
                                                .security_advisory.ghsa_id
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

                            utils.updateCommitStatus(
                                'success',
                                'Dependabot scan passed',
                                'library-scan'
                            )
                        } catch (Exception e) {
                            echo "Dependency scan failed: ${e.message}"
                            throw e
                        }
                    }
                }
            }

            stage('build-image') {
                steps {
                    script {
                        withAWS(
                            credentials: 'aws-creds',
                            region: 'us-east-1'
                        ) {
                            sh """
                                set -eu

                                aws ecr get-login-password --region us-east-1 |
                                    docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com

                                docker build \
                                    -t ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion} .
                            """
                        }

                        utils.updateCommitStatus(
                            'success',
                            'Docker image built',
                            'build-image'
                        )
                    }
                }
            }

            /*
            stage('trivy-scan') {
                steps {
                    script {
                        def image = "${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}"

                        def osScan = sh(
                            script: """
                                trivy image --scanners vuln --pkg-types os \
                                  --severity HIGH,CRITICAL --exit-code 1 \
                                  --format table --output trivy-os-report.txt \
                                  ${image}
                            """,
                            returnStatus: true
                        )

                        def dockerfileScan = sh(
                            script: '''
                                trivy config --severity HIGH,CRITICAL \
                                  --exit-code 1 --format table \
                                  --output trivy-dockerfile-report.txt Dockerfile
                            ''',
                            returnStatus: true
                        )

                        archiveArtifacts(
                            artifacts: 'trivy-*.txt',
                            allowEmptyArchive: true
                        )

                        if (osScan != 0 || dockerfileScan != 0) {
                            error "Trivy scan failed: OS=${osScan}, Dockerfile=${dockerfileScan}"
                        }

                        utils.updateCommitStatus(
                            'success',
                            'Trivy scan passed',
                            'trivy-scan'
                        )
                    }
                }
            }
            */

            stage('push-image-to-ecr') {
                steps {
                    script {
                        withAWS(
                            credentials: 'aws-creds',
                            region: 'us-east-1'
                        ) {
                            sh """
                                docker push ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                            """
                        }

                        utils.updateCommitStatus(
                            'success',
                            'Image pushed to ECR',
                            'push-image'
                        )
                    }
                }
            }

            stage('dev-deploy') {
                steps {
                    script {
                        try {
                            withAWS(
                                credentials: 'aws-creds',
                                region: 'us-east-1'
                            ) {
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

                            utils.updateCommitStatus(
                                'success',
                                'Deployed to roboshop-dev',
                                'dev-deploy'
                            )
                        } catch (Exception e) {
                            echo "Dev deployment failed: ${e.message}"
                            throw e
                        }
                    }
                }
            }

            stage('api-tests') {
                options {
                    timeout(time: 2, unit: 'MINUTES')
                }
                steps {
                    script {
                        build job: 'ROBOSHOP/catalogue-api-tests',
                            parameters: [
                                string(
                                    name: 'NAMESPACE',
                                    value: 'roboshop-dev'
                                ),
                                string(
                                    name: 'COMMIT_ID',
                                    value: env.GIT_COMMIT
                                )
                            ],
                            wait: true,
                            propagate: true

                        utils.updateCommitStatus(
                            'success',
                            'Catalogue API tests passed',
                            'api-tests'
                        )
                    }
                }
            }

            stage('raise-pr') {
                when {
                    not {
                        branch 'main'
                    }
                }
                steps {
                    script {
                        withCredentials([
                            string(
                                credentialsId: 'github-token',
                                variable: 'GH_TOKEN'
                            )
                        ]) {
                            withEnv([
                                "PR_HEAD=${env.BRANCH_NAME}",
                                "PR_REPO=sivajanardhan9989/Cataloguee",
                                "PR_TITLE=${component}: ${env.BRANCH_NAME} -> main",
                                "PR_BODY=Automated PR after successful dev deployment and API tests. Build: ${env.BUILD_URL}console"
                            ]) {
                                sh '''
                                    set -eu

                                    # Reuse an existing open PR for this branch if present.
                                    curl -fsSL \
                                        -H "Authorization: Bearer $GH_TOKEN" \
                                        -H "Accept: application/vnd.github+json" \
                                        -H "X-GitHub-Api-Version: 2022-11-28" \
                                        "https://api.github.com/repos/$PR_REPO/pulls?state=open&base=main&head=$PR_REPO_OWNER:$PR_HEAD" \
                                        -o prs.json
                                '''
                            }
                        }

                        echo 'Check GitHub pull request permissions and repository owner configuration before enabling automated PR creation.'
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
