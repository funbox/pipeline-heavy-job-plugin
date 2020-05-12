pipeline {
    agent { label 'docker' }
    stages {
        stage("Test") {
            steps {
                sh 'docker run --rm -v $PWD:/app -w /app --user $(id -u):$(id -g) -e MAVEN_CONFIG=/app maven:3.3-jdk-8 mvn -Duser.home=/app test'
            }
        }
    }
}
