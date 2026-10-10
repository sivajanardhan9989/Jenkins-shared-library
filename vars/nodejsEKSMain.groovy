def call(Map configMap) {
    // Pipeline-scoped Groovy variables. These are deliberately outside the
    // Declarative environment block; arbitrary def declarations do not belong there.
    def appVersion = ''
    def env_ENVIRONMENT = ''
    def env_COMMIT_ID = ''
    def env_COMPONENT = ''
    def env_PROJECT = ''
    def env_VERSION = ''
    def env_CR_NUMBER = ''
    def acc_id = '209115427888'
    def project = configMap.get('project') ?: 'roboshop'
    def component = configMap.get('component') ?: 'catalogue'

    pipeline {
        agent {
            node {
                label 'ROBOSHOP'
            }
        }
        parameters {
            /* 'dev' is first, so it's the default when this build is triggered
               automatically by a push to main (no parameters supplied). */
            choice(name: 'ENVIRONMENT', choices: ['dev', 'sit', 'uat', 'prod'], description: 'Environment to deploy to')
            string(name: 'COMMIT_ID', defaultValue: '', description: 'Commit SHA to promote (required for sit/uat/prod)')
            string(name: 'COMPONENT', defaultValue: 'catalogue', description: 'Component to deploy')
            string(name: 'PROJECT', defaultValue: 'roboshop', description: 'Project name')
            string(name: 'CR_NUMBER', defaultValue: '', description: 'Change Request number (required for prod deploy)')
            string(name: 'VERSION', defaultValue: '', description: 'Release version to tag the commit with on a successful prod deploy, e.g. v1.4.0 (required for prod deploy)')
        }
        options {
            disableConcurrentBuilds()
            timeout(time: 5, unit: 'HOURS')
        }
        stages {
            // Resolve values from Jenkins Build with Parameters.
            stage('resolve-inputs') {
                steps {
                    script {
                        env_ENVIRONMENT = params.ENVIRONMENT?.trim()
                        env_COMMIT_ID   = params.COMMIT_ID?.trim()
                        env_COMPONENT   = params.COMPONENT?.trim() ?: component
                        env_PROJECT     = params.PROJECT?.trim() ?: project
                        env_VERSION     = params.VERSION?.trim()
                        env_CR_NUMBER   = params.CR_NUMBER?.trim()

                        // Keep the common names aligned with the selected parameters.
                        component = env_COMPONENT
                        project = env_PROJECT

                        echo "Resolved inputs: ENVIRONMENT=${env_ENVIRONMENT} COMMIT_ID=${env_COMMIT_ID} COMPONENT=${env_COMPONENT} PROJECT=${env_PROJECT}"
                    }
                }
            }
            stage('read-version'){
                when {
                    expression { env_ENVIRONMENT == 'dev' }
                }
                steps{
                    script {
                        def packageJson = readJSON file: 'package.json'
                        appVersion = packageJson.version
                        echo "The application version is: ${appVersion}"
                    }
                }
            }
            stage('dev-deploy') {
                when {
                    expression { env_ENVIRONMENT == 'dev' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws eks update-kubeconfig --name roboshop --region us-east-1

                                    helm upgrade --install ${component} ./helm \
                                        -f ./helm/values-dev.yaml \
                                        --namespace roboshop-dev \
                                        --create-namespace \
                                        --set deployment.imageVersion=${appVersion} \
                                        --wait --timeout 5m

                                    kubectl rollout status deployment/${component} -n roboshop-dev --timeout=120s
                                """
                            }
                            utils.updateCommitStatus('success', 'Deployed to roboshop-dev', 'dev-deploy')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Deploy to roboshop-dev failed', 'dev-deploy')
                            throw e
                        }
                    }
                }
            }
            stage('api-tests') {
                when {
                    expression { env_ENVIRONMENT == 'dev' }
                }
                options {
                    timeout(time: 2, unit: 'MINUTES')
                }
                steps {
                    script {
                        try {
                            build job: 'ROBOSHOP/catalogue-api-tests', parameters: [
                                string(name: 'NAMESPACE', value: 'roboshop-dev'),
                                string(name: 'COMMIT_ID', value: env.GIT_COMMIT)
                            ], wait: true, propagate: true
                            utils.updateCommitStatus('success', 'catalogue-api-tests passed', 'api-tests')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'catalogue-api-tests failed', 'api-tests')
                            throw e
                        }
                    }
                }
            }
            /* Promote the same image that just passed dev/api-tests by retagging it
               with the short commit SHA in ECR. */
            stage('promote-image') {
                when {
                    expression { env_ENVIRONMENT == 'dev' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com

                                    docker pull ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion}
                                    docker tag ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${appVersion} ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${env.GIT_COMMIT}
                                    docker push ${acc_id}.dkr.ecr.us-east-1.amazonaws.com/${project}/${component}:${env.GIT_COMMIT}
                                """
                            }
                            utils.updateCommitStatus('success', "Promoted image as ${env.GIT_COMMIT}", 'promote-image')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Image promotion failed', 'promote-image')
                            throw e
                        }
                    }
                }
            }
            stage('validate-commit-status') {
                when {
                    expression { env_ENVIRONMENT in ['sit', 'uat', 'prod'] }
                }
                steps {
                    script {
                        if (!env_COMMIT_ID?.trim()) {
                            error("COMMIT_ID is required when deploying to ${env_ENVIRONMENT}")
                        }

                        def requiredContexts = ['dev-deploy', 'api-tests']
                        if (env_ENVIRONMENT in ['uat', 'prod']) {
                            requiredContexts += ['sit-deploy', 'sit-integration-tests']
                        }
                        if (env_ENVIRONMENT == 'prod') {
                            requiredContexts += ['uat-deploy', 'uat-regression-tests']
                        }
                        utils.validateCommitStatus(env_COMMIT_ID.trim(), requiredContexts)
                    }
                }
            }
            stage('sit-deploy') {
                when {
                    expression { env_ENVIRONMENT == 'sit' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws eks update-kubeconfig --name roboshop --region us-east-1

                                    helm upgrade --install ${env_COMPONENT} ./helm \
                                        -f ./helm/values-sit.yaml \
                                        --namespace roboshop-sit \
                                        --create-namespace \
                                        --set deployment.imageVersion=${env_COMMIT_ID} \
                                        --wait --timeout 5m

                                    kubectl rollout status deployment/${env_COMPONENT} -n roboshop-sit --timeout=120s
                                """
                            }
                            utils.updateCommitStatus('success', "Deployed ${env_COMMIT_ID} to roboshop-sit", 'sit-deploy')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Deploy to roboshop-sit failed', 'sit-deploy')
                            throw e
                        }
                    }
                }
            }
            stage('sit-integration-tests') {
                when {
                    expression { env_ENVIRONMENT == 'sit' }
                }
                options {
                    timeout(time: 2, unit: 'MINUTES')
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh "aws eks update-kubeconfig --name roboshop --region us-east-1"

                                /* The Jenkins agent can't resolve *.svc.cluster.local from
                                   roboshop-sit, but it's in the same VPC as the EKS pods —
                                   route by pod IP instead of relying on cluster DNS. */
                                def catalogueIp = utils.getPodIP('roboshop-sit', 'catalogue')
                                def cartIp      = utils.getPodIP('roboshop-sit', 'cart')
                                def userIp      = utils.getPodIP('roboshop-sit', 'user')
                                def shippingIp  = utils.getPodIP('roboshop-sit', 'shipping')
                                def paymentIp   = utils.getPodIP('roboshop-sit', 'payment')

                                build job: 'ROBOSHOP/roboshop-integration-tests', parameters: [
                                    string(name: 'NAMESPACE', value: 'roboshop-sit'),
                                    string(name: 'CATALOGUE_URL', value: "http://${catalogueIp}:8080"),
                                    string(name: 'CART_URL', value: "http://${cartIp}:8080"),
                                    string(name: 'USER_URL', value: "http://${userIp}:8080"),
                                    string(name: 'SHIPPING_URL', value: "http://${shippingIp}:8080"),
                                    string(name: 'PAYMENT_URL', value: "http://${paymentIp}:8080")
                                ], wait: true, propagate: true
                            }
                            utils.updateCommitStatus('success', 'roboshop-integration-tests passed', 'sit-integration-tests')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'roboshop-integration-tests failed', 'sit-integration-tests')
                            throw e
                        }
                    }
                }
            }
            stage('uat-deploy') {
                when {
                    expression { env_ENVIRONMENT == 'uat' }
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh """
                                    aws eks update-kubeconfig --name roboshop --region us-east-1

                                    helm upgrade --install ${env_COMPONENT} ./helm \
                                        -f ./helm/values-uat.yaml \
                                        --namespace roboshop-uat \
                                        --create-namespace \
                                        --set deployment.imageVersion=${env_COMMIT_ID} \
                                        --wait --timeout 5m

                                    kubectl rollout status deployment/${env_COMPONENT} -n roboshop-uat --timeout=120s
                                """
                            }
                            utils.updateCommitStatus('success', "Deployed ${env_COMMIT_ID} to roboshop-uat", 'uat-deploy')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Deploy to roboshop-uat failed', 'uat-deploy')
                            throw e
                        }
                    }
                }
            }
            stage('uat-regression-tests') {
                when {
                    expression { env_ENVIRONMENT == 'uat' }
                }
                options {
                    timeout(time: 2, unit: 'MINUTES')
                }
                steps {
                    script {
                        try {
                            withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                                sh "aws eks update-kubeconfig --name roboshop --region us-east-1"

                                /* The Jenkins agent can't resolve *.svc.cluster.local from
                                   roboshop-uat, but it's in the same VPC as the EKS pods —
                                   route by pod IP instead of relying on cluster DNS. */
                                def catalogueIp = utils.getPodIP('roboshop-uat', 'catalogue')
                                def cartIp      = utils.getPodIP('roboshop-uat', 'cart')
                                def userIp      = utils.getPodIP('roboshop-uat', 'user')
                                def shippingIp  = utils.getPodIP('roboshop-uat', 'shipping')
                                def paymentIp   = utils.getPodIP('roboshop-uat', 'payment')

                                build job: 'ROBOSHOP/roboshop-regression-tests', parameters: [
                                    string(name: 'NAMESPACE', value: 'roboshop-uat'),
                                    string(name: 'CATALOGUE_URL', value: "http://${catalogueIp}:8080"),
                                    string(name: 'CART_URL', value: "http://${cartIp}:8080"),
                                    string(name: 'USER_URL', value: "http://${userIp}:8080"),
                                    string(name: 'SHIPPING_URL', value: "http://${shippingIp}:8080"),
                                    string(name: 'PAYMENT_URL', value: "http://${paymentIp}:8080")
                                ], wait: true, propagate: true
                            }
                            utils.updateCommitStatus('success', 'roboshop-regression-tests passed', 'uat-regression-tests')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'roboshop-regression-tests failed', 'uat-regression-tests')
                            throw e
                        }
                    }
                }
            }
            /* CR gate: number + version must be supplied, deploy must fall inside the
               approved window, and a human has to click approve. Runs with its own
               timeout so waiting on a person doesn't get killed by the pipeline's
               overall 15-minute budget. */
            stage('change-request-check') {
                when {
                    expression { env_ENVIRONMENT == 'prod' }
                }
                options {
                    timeout(time: 4, unit: 'HOURS')
                }
                steps {
                    script {
                        if (!env_CR_NUMBER?.trim()) {
                            error("CR_NUMBER is required for a prod deploy")
                        }
                        if (!env_VERSION?.trim()) {
                            error("VERSION is required for a prod deploy")
                        }

                        /* Dummy deployment-window check — placeholder until this is wired
                           up to a real CR system. Blocks weekend prod deploys for now. */
                        def dayOfWeek = sh(script: 'date +%u', returnStdout: true).trim().toInteger()
                        if (dayOfWeek >= 6) {
                            error("CR ${env_CR_NUMBER}: outside the approved deployment window (no weekend prod deploys) — dummy check, replace with a real CR window lookup")
                        }
                        echo "CR ${env_CR_NUMBER}: within deployment window"

                        input message: "Approve prod deploy of ${env_COMPONENT}@${env_COMMIT_ID} as ${env_VERSION} under CR ${env_CR_NUMBER}?", ok: 'Approve'
                    }
                }
            }
            stage('prod-deploy') {
                when {
                    expression { env_ENVIRONMENT == 'prod' }
                }
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                            sh "aws eks update-kubeconfig --name roboshop --region us-east-1"

                            /* Only attempt a rollback if there's a prior successful release
                               to roll back to — a failed first-ever deploy has nothing behind it. */
                            def releaseExists = sh(
                                script: "helm status ${env_COMPONENT} -n roboshop-prod > /dev/null 2>&1",
                                returnStatus: true
                            ) == 0
                            try {
                                sh """
                                    helm upgrade --install ${env_COMPONENT} ./helm \
                                        -f ./helm/values-prod.yaml \
                                        --namespace roboshop-prod \
                                        --create-namespace \
                                        --set deployment.imageVersion=${env_COMMIT_ID} \
                                        --wait --timeout 5m

                                    kubectl rollout status deployment/${env_COMPONENT} -n roboshop-prod --timeout=120s
                                """
                                utils.updateCommitStatus('success', "Deployed ${env_COMMIT_ID} to roboshop-prod (CR ${env_CR_NUMBER})", 'prod-deploy')
                            }
                            catch (Exception e) {
                                if (releaseExists) {
                                    echo "prod-deploy failed on an existing release — rolling back ${env_COMPONENT} in roboshop-prod"
                                    sh "helm rollback ${env_COMPONENT} 0 -n roboshop-prod --wait --timeout 5m"
                                } else {
                                    echo "prod-deploy failed on the first-ever release of ${env_COMPONENT} — nothing to roll back to"
                                }
                                utils.updateCommitStatus('failure', 'Deploy to roboshop-prod failed', 'prod-deploy')
                                throw e
                            }
                        }
                    }
                }
            }
            /* Only reached if prod-deploy succeeded — declarative pipeline stops
               running further stages once one fails. */
            stage('tag-release') {
                when {
                    expression { env_ENVIRONMENT == 'prod' }
                }
                steps {
                    script {
                        try {
                            utils.tagCommit(env_COMMIT_ID.trim(), env_VERSION.trim())
                            echo "Tagged ${env_COMMIT_ID} as ${env_VERSION} (CR ${env_CR_NUMBER})"
                            utils.updateCommitStatus('success', "Tagged as ${env_VERSION}", 'tag-release')
                        }
                        catch (Exception e) {
                            utils.updateCommitStatus('failure', 'Tagging release failed', 'tag-release')
                            throw e
                        }
                    }
                }
            }
        }

        post {
            success {
                /* slackSend(
                    channel: '#test-ci',
                    color: 'good',
                    tokenCredentialId: 'slack-token',
                    message: "✅ *${env_COMPONENT ?: component}* ${env_ENVIRONMENT} deploy succeeded — commit `${env_COMMIT_ID ?: env.GIT_COMMIT}` (<${env.BUILD_URL}console|console>)"
                ) */
                echo "success"
            }
            failure {
                /* slackSend(
                    channel: '#test-ci',
                    color: 'danger',
                    tokenCredentialId: 'slack-token',
                    message: "❌ *${env_COMPONENT ?: component}* ${env_ENVIRONMENT} deploy failed — commit `${env_COMMIT_ID ?: env.GIT_COMMIT}` (<${env.BUILD_URL}console|console>)"
                ) */
                echo "failure"
            }
        }
    }
}
