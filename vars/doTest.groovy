// vars/doTest.groovy



def jobStartedByWhat() {
    def startedByWhat = ''
    try {
        def buildCauses = currentBuild.rawBuild.getCauses()
        for ( buildCause in buildCauses ) {
            if (buildCause != null) {
                def causeDescription = buildCause.getShortDescription()
                echo "shortDescription: ${causeDescription}"
                if (causeDescription.contains("Started by timer")) {
                    startedByWhat = 'timer'
                    echo "shortDescription: ===== Timer ===="
                }
                if (causeDescription.contains("Started by user")) {
                    startedByWhat = 'user'
                    echo "shortDescription: ===== User ===="
                }
            }
        }
    } catch(theError) {
        echo "Error getting build cause: ${theError}"
    }

    return startedByWhat
}

def call(){

    def TEST_STATUS = 'SUCCESS'
    def failREASON = ''
    def GIT_URL = ''
    def GIT_BRANCH = ''
    def jenkinsURL = ''
    def eMailBody = ''
    def eMailSubject = ''
    def eMailTO='abdelghany_elaziz@hotmail.com'
    def eMailCC='abdelghany_elaziz@hotmail.com'
    def eMailBCC='abdelghany_elaziz@hotmail.com'
    def currentStep =  ''
    def testReportExist=false
    def logFileExist=false
    def emailEnabled=true
    def executeAgent = 'master'    
    def enableNightlyRun = 'true'

    def startedByWhat = jobStartedByWhat() 

    node('master') {
        
            stage ('Initialize') {
                
                echo "====== Initializing...... =========="                  

                script{currentStep = 'Initialize'}
            
                sh '''
                    echo "Used JDK = ${JAVA_HOME}"
                    echo "Used MAVEN = ${MAVEN_HOME}"
                '''
                    script {
                    GIT_URL = scm.getUserRemoteConfigs()[0].getUrl().trim()
                    GIT_BRANCH = scm.branches[0].name.trim()
                    GIT_BRANCH = GIT_BRANCH.replace("*/","")          
                } 

                
                echo "Used git URL = ${GIT_URL}"
                echo "Used git Branch = ${GIT_BRANCH}"
                
                
            }
            
            stage ('Clone') {
                
                echo "====== Cloning...... =========="

                script{currentStep = 'Clone'}
                
                sh 'git clean -fdx'                    
                
                git branch: "${GIT_BRANCH}", url: "${GIT_URL}",credentialsId: 'TestCredential'

                dir("${env.WORKSPACE}"){
                    fileOperations([fileDeleteOperation(excludes: '', includes: '**/logfile.log')])
                }
                
            }

        stage('Checkout properties'){     

                echo "====== Properties Checkout...... =========="

                script{            
                    
                    def props = readProperties  file: './Jenkinsfile.properties'
                    
                     // check if allowed nightly execution
                    if(props.enableNightlyRun != '') {
                       enableNightlyRun = props.enableNightlyRun
                    }    
                    
                    
                    if(startedByWhat == 'user') { // do execution
                        echo('Execution Started by user.!!!')
                    }else if(enableNightlyRun == 'true' && startedByWhat == 'timer'){ // timer execution
                        if(env.JOB_NAME.contains('_Dev')){
                            echo('Skipping timer execution, nightly run is disabled.!')                      
                            currentBuild.result = 'ABORTED'  
                            currentStep = 'ABORTED'
                            return
                        }else{echo('Execution Started by timer.!!!')}                        
                    }else if(enableNightlyRun == 'false' && startedByWhat == 'timer'){ // abort execution      
                        echo('Skipping timer execution, nightly run is disabled.!')                      
                        currentBuild.result = 'ABORTED'  
                        currentStep = 'ABORTED'
                        return
                    }
                    
                   

                    // assign node name for execution

                    if(props.machineName != ''){
                        // check if the node is online and assigne its name
                        executeAgent= props.machineName
                        try {
                            timeout(time: 1, unit: 'SECONDS') {
                                node(executeAgent) {
                                    echo "Node ${executeAgent} is up. Performing optional step."
                                }
                            }
                            node(executeAgent) {
                                echo 'This is an optional step.'                                
                            }
                        } catch (e) {
                            echo "Time out on optional step. Node ${executeAgent} is down?"
                            executeAgent = 'master'                           
                        }
                       

                    }  
                           
                }                    
        }
        
        
        
    }

    pipeline {
        
        agent { label executeAgent }
        
        environment {
            REVISION = "0.0.${env.BUILD_ID}"
            htmlReportFile = "Test_Automation_Report"
        }
            
        triggers {
            cron '''TZ=Asia/Riyadh
            H 22 * * 0-4'''
        }
        
        options {
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '260'))
        }
        
        tools {
            maven 'MAVEN_3.5.3'
            jdk 'JDK_10'
        }
        
        stages {
            
            stage ('Build') {
                steps {
                    echo "====== Building...... =========="

                    script{currentStep = 'Build'}

                    sh 'mvn clean package -DskipTests -s "C:/Users/aelsayed/.m2/settings.xml"'
                }
            }
            
            stage('Run Test'){
                steps{
                    echo "=========== Testing...... ======================="

                    script{currentStep = 'Testing'}

                    script{
                        def pom = readMavenPom file: './pom.xml'
                        jarFileName = pom.artifactId+"-"+pom.version+".jar"
                    }

                    timeout(activity: true, time: 30, unit: 'MINUTES') {
                        sh "java -jar ./'${jarFileName}'"
                    }
                }
            }
            
            stage('Publish Test Result'){
                steps{
                    echo "=========== Publishing...... ======================="

                    script{currentStep = 'Publishing'}

                    script{
                        if (fileExists('./test-output/Reports/Automation_Result/Automation_Report.html')) {

                            dir("${env.JENKINS_HOME}") {
                                fileOperations([folderCreateOperation("${JENKINS_HOME}\\wwwroot\\${JOB_NAME}\\${BUILD_NUMBER}")])
                            }
                            
                            fileOperations([folderCopyOperation(destinationFolderPath: '${JENKINS_HOME}\\wwwroot\\${JOB_NAME}\\${BUILD_NUMBER}\\Automation_Result',                            
                            sourceFolderPath: '.\\test-output\\Reports\\Automation_Result')])

                            publishHTML([allowMissing: false, alwaysLinkToLastBuild: true, keepAll: true,escapeUnderscores: false,
                            reportDir: "./test-output/Reports/Automation_Result",
                            reportFiles: 'Automation_Report.html',
                            reportName: "${env.htmlReportFile}",
                            reportTitles: ''])

                            testReportExist=true

                        } else {
                            echo 'File Not Exist (./test-output/Reports/Automation_Result/Automation_Report.html)'
                        }
                    }

                    script{
                        if (fileExists('./test-output/testng-results.xml')) {

                            step([$class: 'Publisher', reportFilenamePattern: './test-output/testng-results.xml'])
                        }
                    }
                    

                    //logfile.log 
                    script{
                        if (fileExists("${env.WORKSPACE}\\logfile.log")) { 
                            fileOperations([fileCopyOperation(excludes: '', flattenFiles: false, includes: '**/logfile.log',
                                targetLocation: '${JENKINS_HOME}\\wwwroot\\${JOB_NAME}\\${BUILD_NUMBER}\\Logs')])
                            
                            dir("${JENKINS_HOME}\\wwwroot\\${JOB_NAME}\\${BUILD_NUMBER}\\Logs") {
                                fileOperations([fileRenameOperation(destination: 'logfile.html', source: 'logfile.log')])
                            }

                            logFileExist = true
                        }

                        
                    }

                    
                    
                    
                }
            }
            
            stage('Email Test Results'){
                steps{
                    echo "=========== Emailing...... ======================="

                    script{currentStep = 'Emailing'}

                    script{
                        TEST_STATUS = currentBuild.currentResult
                        if (TEST_STATUS == 'UNSTABLE') {
                            TEST_STATUS = 'FAILURE'
                        }
                    }

                    script{
                        def logz = currentBuild.rawBuild.getLog(10000);
                        def result = logz.find { it.contains('NoSuchElementException') }
                        if (result) {
                            failREASON = 'Failing due to exception like : ' + result.trim() +'\n'
                            TEST_STATUS = 'FAILURE'
                        }else {
                            result = logz.find { it.contains('no such element') }
                            if (result) {
                                failREASON = 'Failing due to element locators like : [ ' + result.trim() +' ]\n'
                                TEST_STATUS = 'FAILURE'
                            }
                        }

                        def testResult = logz.find { it.contains('[FAIL]') }
                        if (testResult) {
                            TEST_STATUS = 'FAILURE'
                        }else {
                            testResult = logz.find { it.contains('Driver Error') }
                            if (testResult) {
                                TEST_STATUS = 'FAILURE'
                            }
                        }

                        jenkinsURL = JENKINS_URL
                        jenkinsURL = jenkinsURL.replace("8080","80")
                        
                        if(emailEnabled){
                            def props = readProperties  file: './Jenkinsfile.properties'
                            if(props.eMailTO != ''){eMailTO= props.eMailTO}
                            if(props.eMailCC != ''){eMailCC= props.eMailCC}
                            if(props.eMailBCC != ''){eMailBCC= eMailBCC +','+ props.eMailBCC}
                            
                        }
                    }
                    
                    
                    script {                                        
                        eMailSubject = "Test Automation Nightly Execution # ${env.BUILD_NUMBER} | ${env.JOB_NAME}"
                        eMailBody = "Dear,\nPlease be informed that status is [ ${TEST_STATUS} ], For Test Automation Nightly Execution # ${env.BUILD_NUMBER} , For Project [ ${env.JOB_NAME} ].\n\n"
                        if(failREASON != ''){
                            eMailBody = eMailBody + "${failREASON}\n"
                        }                        
                        if(testReportExist){
                            eMailBody = eMailBody + "Please Check Test automation report at: ${jenkinsURL}${env.JOB_NAME}/${env.BUILD_NUMBER}/Automation_Result/Automation_Report.html\n"
                        }
                        
                        if(logFileExist){                            
                            eMailBody = eMailBody + "Please Check Test automation LOG at: ${jenkinsURL}${env.JOB_NAME}/${env.BUILD_NUMBER}/Logs/logfile.html \n"
                        }
                        eMailBody = eMailBody + "For more info Please Check console output at: ${env.BUILD_URL}console.\n\n"
                        eMailBody = eMailBody + "Kind regards,\nDCS-QA\nTest Automation Team."
                        
                        echo "Mail Subject : ${eMailSubject}"
                        echo "Mail Body : ${eMailBody}"
                    }

                    script{currentStep = 'Done'}
                }
            }
            
        }
        
        post {
            
            always {
                script {
                    if(currentStep != 'Done')
                    {
                        TEST_STATUS = 'FAILURE'
                        if(currentStep == 'ABORTED'){TEST_STATUS = 'ABORTED'}
                        
                        eMailSubject = "Test Automation Nightly Execution # ${env.BUILD_NUMBER} | ${env.JOB_NAME}"
                        eMailBody = "Dear,\nPlease be informed that status is [ ${TEST_STATUS} ], For Test Automation Nightly Execution # ${env.BUILD_NUMBER} , For Project [ ${env.JOB_NAME} ].\n\n"
                        eMailBody = eMailBody + "Jenkins Pipeline Failed at stage : [ ${currentStep} ].\n\n"
                        if(logFileExist){                            
                            eMailBody = eMailBody + "Please Check Test automation LOG at: ${jenkinsURL}${env.JOB_NAME}/${env.BUILD_NUMBER}/Logs/logfile.html \n"
                        }
                        eMailBody = eMailBody + "For more info Please Check console output at: ${env.BUILD_URL}console.\n\n"
                        eMailBody = eMailBody + "Kind regards,\nDCS-QA.\nTest Automation Team."
                        
                        echo "Mail Subject : ${eMailSubject}"
                        echo "Mail Body : ${eMailBody}"
                    }
                    
                    try{
                        mail bcc: "${eMailBCC}", body: "${eMailBody}", cc: "${eMailCC}", from: 'JenkinsQA <abdelghany_elaziz@hotmail.com>', replyTo: '', subject: "${eMailSubject}", to: "${eMailTO}"
                    }catch(exc){
                        mail bcc: "", 
                        body: "Dear,\n a Fatal Error happend\n Please Check console output at: ${env.BUILD_URL}console.\n\n ", cc: "", 
                        from: 'JenkinsQA <abdelghany_elaziz@hotmail.com>', replyTo: '',
                        subject: "${eMailSubject}", to: "abdelghany_elaziz@hotmail.com"
                    }
                    
                    try {
                        if(TEST_STATUS == 'FAILURE'){
                            currentBuild.result = 'FAILURE'
                        }
                    }
                    catch (exc) {
                        currentBuild.result = 'FAILURE'
                    }
                }
            }
        }

    }
}
