package my.com.ctos

import groovy.transform.Field


@Field def defaultInf=[
	SONARQUBE_HOST : 'http://10.4.11.40:9000/',
	SONARQUBE_CREDENTIAL_ID : 'sonarqube-credential',
	TWISTLOCK_HOST : 'https://twistlock-console-cicd.apps.osd-gic-np.v0t5.p1.openshiftapps.com',
	TWISTLOCK_CREDENTIAL_ID : 'twistlock-credential',
	SVC_ACCT_CREDENTIAL_ID : 'sysdevclb',
	SCM_PROTOCOL : 'http',
	SCM_HOST : 'git',
	REGISTRY_DEV : 'http://10.2.39.6:8080/',
	REGISTRY_PROD : 'http://10.2.39.7:8080/'
]

def checkout_scm(Map args = [:]){
	def finalInf = defaultInf + args
	withCredentials([usernamePassword(credentialsId: "$finalInf.SVC_ACCT_CREDENTIAL_ID", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
		def scm_full_url = "${finalInf.SCM_PROTOCOL}://${USERNAME}:${PASSWORD}@${finalInf.SCM_HOST}/${args.project}/${args.repository}.git"
		sh "git clone $scm_full_url"
		sh "git remote set-url origin $scm_full_url && git fetch && git checkout $args.refs"
		if (args.subdirectory_path!=null){
			def subdirectory_path = "${finalInf.subdirectory}"
			sh "cd $subdirectory_path "
		}
		sh "ls -ltrha"
	}
}

def sonar(Map args = [:], Closure body){
	def finalInf = defaultInf + args
	if(args.scan){
		def project_version = currentBuild.displayName
		withCredentials([usernamePassword(credentialsId: "$finalInf.SONARQUBE_CREDENTIAL_ID", passwordVariable: 'sonar_password', usernameVariable: 'sonar_username')]) {
				sh "pwd && ls -ltrha && sonar-scanner -X -Dcom.sun.net.ssl.checkRevocation=false -Dsonar.projectBaseDir=. -Dsonar.projectName=${args.key} -Dsonar.projectVersion=${project_version} -Dsonar.projectKey=${args.key} -Dsonar.java.binaries=. -Dsonar.sources=. -Dsonar.host.url=${finalInf.SONARQUBE_HOST} -Dsonar.sourceEncoding=UTF-8 -Dsonar.login=${sonar_username} -Dsonar.password=${sonar_password}"
		}
	}
	sh "sonar-scanner --version || true"
	body.call()
}

def maven(Map args = [:], Closure body){
	sh "mvn --version"
	if(args.debug){
		sh "sleep ${defaultInf.DEBUG_SECOND}"
	}
	if(args.compile){
		sh "mvn dependency:tree"
		def test = !args.test? "-DskipTests":""
		sh "mvn -e ${test} clean install"
	}
	body.call()
}

def artifactoryDeploy(Map args = [:], Closure body){
	def finalInf = defaultInf + args
	def version_locator = finalInf.versionLocator
	def artifact_ext = finalInf.artifactExtension
	def version = ""
	def result_directory = ""
	if (${version_locator} == "pom"){
		//getting version from maven
		version = sh(script: 'mvn org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version',returnStdout: true).trim()
		result_directory = getResultDirectory("maven")
	} else {
		//if no version will use YYYYMMDD as version
		version = date '+%Y%m%d'
	}
	
	//get artifact file
	def artifact_file = getArtifactFileName(result_directory,artifact_ext)
	withCredentials([usernamePassword(credentialsId: "$finalInf.SVC_ACCT_CREDENTIAL_ID", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
		sh "curl -u ${USERNAME}:${PASSWORD} -X PUT '${REGISTRY_DEV}/artifactory/ctos_artifacts/${finalInf.project}/${version}/${artifact_file}" -T ${result_directory}${artifact_file}"
	}
	body.call()
}

def serverDeploy(Map args = [:], Closure body){
	def finalInf = defaultInf + args
	def result_directory = getResultDirectory(finalInf.compiler)	
	def artifact_file = getArtifactFileName(result_directory,artifact_ext)
	def target_directory = ${finalInf.targetDir}
	def ssh_username = ${finalInf.sshUser}
	def ssh_host = ${finalInf.sshHost}
	if (args.backup){
		def backup_directory = ${finalInf.backupDir}
		//will be appended with _YYYYMMDD
		def renamed_artifact = artifact_file + "_" + date '+%Y%m%d'
		sh "ssh ${ssh_username}@${ssh_host} -t 'mv ${target_directory}/${artifact_file} ${backup_directory}/${renamed_artifact}; bash -l' "
	}
	if (args.prescript!=null){
		sh "${args.prescript}"
	}
	sh "scp ${result_directory}/${artifact_file} ${ssh_username}@${ssh_host}:${target_directory}"
	if (args.postscript!=null){
		sh "${args.postscript}"
	}
}

def getResultDirectory(String compiler){
	def result_directory
	if (compiler == "maven"){
		result_directory = "target/"
	}
	return result_directory
}

def getArtifactFileName(String result_directory, String artifact_ext){
	def artifact_file = sh(script: "ls  ./"+result_directory+ " | grep '."+artifact_ext+"'",returnStdout: true).trim()
	return artifact_file
}