#!/usr/bin/env groovy

pipeline {
    agent any

    environment {
        APPLICATION_NAME = 'helse-sykmelding-router'
        DISABLE_SLACK_MESSAGES = true
        ZONE = 'fss'
        DOCKER_SLUG='helse'
        KUBECONFIG="kubeconfig-teamsykefravr"
    }

    stages {
        stage('initialize') {
            steps {
                script {
                    init action: 'default'
                    sh './gradlew clean'
                    applicationVersionGradle = sh(script: './gradlew -q printVersion', returnStdout: true).trim()
                    env.APPLICATION_VERSION = "${applicationVersionGradle}.${env.BUILD_ID}-${env.COMMIT_HASH_SHORT}"
                    init action: 'updateStatus'
                }
            }
        }
        stage('build') {
            steps {
                sh './gradlew build -x test'
            }
        }
        stage('run tests (unit & intergration)') {
            steps {
                sh './gradlew test'
            }
        }
        stage('create uber jar') {
            steps {
                sh './gradlew shadowJar'
                slackStatus status: 'passed'
            }
        }
        stage('deploy') {
            steps {
                dockerUtils action: 'createPushImage'
                deployApp action: 'kubectlDeploy', cluster: 'preprod-fss'
            }
        }
        stage('deploy to production') {
            when { environment name: 'DEPLOY_TO', value: 'production' }

            steps {
                deployApp action: 'kubectlDeploy', cluster: 'prod-fss'
            }
        }
    }
    post {
        always {
            postProcess action: 'always'
        }
        success {
            postProcess action: 'success'
        }
        failure {
            postProcess action: 'failure'
        }
    }
}
